/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The recorded particles, block effects, sounds and weather, played again at their time. Before
 * that, new particles that are not the replay's are removed: the real world keeps running behind
 * (in the death replay), and the dust of your death would appear twice.
 */
final class ReplayEffects {

    private final Minecraft mc;
    private final Source rec;

    /** The last frame whose particles already came out. */
    private int lastEvents = -1;

    /** The particles on screen now that are the replay's (the others are the real world's). */
    private final Set<EntityFX> known = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Sounds played by the replay (the debug log shows them). */
    int soundsPlayed;

    /** The rain and thunder from before the replay (they come back at the end). */
    private float oldRain = -1;

    private float oldThunder;

    ReplayEffects(Minecraft mc, Source rec) {
        this.mc = mc;
        this.rec = rec;
    }

    /** A jump in time: the skipped stretch does not release its particles. */
    void skipTo(float time) {
        lastEvents = (int) Math.floor(time);
    }

    /**
     * The particles of the frames the replay just passed, asked of the game again the way the world
     * renderer would ask (the same maximum distance and the same particle option), and the sounds,
     * lower in slow motion.
     *
     * @param rate the replay's speed in this tick (the pitch of the sounds)
     */
    void play(float b, int frames, float rate) {
        int ib = (int) Math.floor(b);
        purgeForeign();
        if (ib < lastEvents) {
            lastEvents = ib;
            return;
        }
        ParticleTap.replaying = true;
        try {
            for (int i = Math.max(lastEvents + 1, ib - 3); i <= ib; i++) {
                play(rec.frame(Math.max(0, Math.min(frames - 1, i))).events, rate);
            }
        } finally {
            ParticleTap.replaying = false;
        }
        lastEvents = ib;
        remember();
    }

    private void play(Events e, float rate) {
        Entity view = mc.getRenderViewEntity();
        int setting = mc.gameSettings.particleSetting;
        for (int i = 0; i < e.count; i++) {
            double x = e.pos[i * 3];
            double y = e.pos[i * 3 + 1];
            double z = e.pos[i * 3 + 2];
            if (!e.ignoreRange[i]) {
                if (view != null && view.getDistanceSq(x, y, z) > 1024) {
                    continue;
                }
                int k = setting == 1 && mc.theWorld.rand.nextInt(3) == 0 ? 2 : setting;
                if (k > 1) {
                    continue;
                }
            }
            mc.effectRenderer.spawnEffectParticle(
                    e.type[i],
                    x,
                    y,
                    z,
                    e.vel[i * 3],
                    e.vel[i * 3 + 1],
                    e.vel[i * 3 + 2],
                    e.params(i));
        }
        // Block effects through the world, which hands them to the world renderer (the tap skips
        // them while replaying).
        for (int i = 0; i < e.auxCount * 5; i += 5) {
            mc.theWorld.playAuxSFX(
                    e.aux[i], new BlockPos(e.aux[i + 1], e.aux[i + 2], e.aux[i + 3]), e.aux[i + 4]);
        }
        // The sounds, with a lower pitch in slow motion (like a film in slow motion).
        float tone = Math.max(0.6F, Math.min(1, rate));
        soundsPlayed += e.soundCount;
        for (int i = 0; i < e.soundCount; i++) {
            float[] s = e.sound;
            mc.getSoundHandler()
                    .playSound(
                            new PositionedSoundRecord(
                                    new ResourceLocation(e.soundName[i]),
                                    s[i * 5 + 3],
                                    s[i * 5 + 4] * tone,
                                    s[i * 5],
                                    s[i * 5 + 1],
                                    s[i * 5 + 2]));
        }
    }

    /** Removes from the screen the particles that came in since the last tick and are not ours. */
    private void purgeForeign() {
        for (List<EntityFX>[] layer : mc.effectRenderer.fxLayers) {
            for (List<EntityFX> list : layer) {
                if (list != null) {
                    list.removeIf(fx -> !known.contains(fx));
                }
            }
        }
    }

    /** The particles on screen now are all the replay's. */
    private void remember() {
        known.clear();
        for (List<EntityFX>[] layer : mc.effectRenderer.fxLayers) {
            for (List<EntityFX> list : layer) {
                if (list != null) {
                    known.addAll(list);
                }
            }
        }
    }

    /** The world time, rain and thunder recorded at time t (without them, the real ones leak). */
    void weather(float t, int frames) {
        Frame f = rec.frame(Math.max(0, Math.min(frames - 1, (int) Math.floor(t))));
        if (f.worldTime < 0 || mc.theWorld == null) {
            return;
        }
        if (oldRain < 0) {
            oldRain = mc.theWorld.getRainStrength(1);
            oldThunder = mc.theWorld.getThunderStrength(1);
        }
        mc.theWorld.setWorldTime(f.worldTime);
        mc.theWorld.setRainStrength(f.rain);
        mc.theWorld.setThunderStrength(f.thunder);
    }

    /** The rain and thunder from before, back (the death replay, in the real world). */
    void restoreWeather() {
        if (oldRain >= 0 && mc.theWorld != null) {
            mc.theWorld.setRainStrength(oldRain);
            mc.theWorld.setThunderStrength(oldThunder);
        }
    }
}
