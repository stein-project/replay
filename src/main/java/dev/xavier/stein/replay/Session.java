/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Hud;
import dev.xavier.stein.loader.api.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldSettings;

import java.util.Locale;
import java.util.UUID;

/**
 * A replay playing: the recorded frames, the time (in ticks, with fraction), the speed, the pause
 * and the camera.
 *
 * <p>The replay's players and things are copies in the client world ({@link Actors}), drawn by the
 * game like any entity. The camera is the {@link DroneCamera}; in the death's slow motion (half
 * speed from the final blow to the body falling), if the killer is in the scene, the camera goes to
 * their eyes, with their hand, item and swing ({@link KillerHand}). The recorded particles come out
 * again at their time ({@link ReplayEffects}). The death replay loops, fading between rounds, until
 * the player respawns.
 *
 * <p>The camera goes through blocks like the <b>spectator</b>'s: while the replay plays, your
 * player is a spectator only on your client (the game then draws seeing through the block the
 * camera is in). None of it goes to the server.
 */
final class Session {

    static final float[] SPEEDS = {0.25F, 0.5F, 1F, 2F};

    /** {@code -Dsteinreplay.debug=true}: time, death, you and the camera, every replay second. */
    private static final boolean DEBUG = Boolean.getBoolean("steinreplay.debug");

    /**
     * The death's slow motion: the fraction of the speed, the ticks before the death where it
     * starts to slow down and where it gets there, and the ticks after the death where it speeds up
     * again and where it is back to normal (the body falls in 20 ticks).
     */
    private static final float SLOW = 0.5F;

    private static final int SLOW_IN_FROM = 30;
    private static final int SLOW_IN_TO = 10;
    private static final int SLOW_OUT_FROM = 22;
    private static final int SLOW_OUT_TO = 40;

    /** Ticks of the fade between one round and the next, and held at the end before fading. */
    private static final int FADE_TICKS = 12;

    private static final int HOLD_END = 30;

    final Source rec;

    /** A saved replay (watched outside a match): it stops at the end instead of looping. */
    final boolean saved;

    /** Frames there are now (in the death replay frames still arrive: the body, the items). */
    int frames;

    /** The death frame: the slow motion is around it. */
    final int deathAt;

    /** Frames still arrive (death replay, in the seconds after dying). */
    boolean growing;

    /** Who killed you (entity id), or -1, and their name. */
    final int killer;

    final String killerName;

    /** The killer as they were at the final blow, for the card (or null). */
    final Snap killerCard;

    /** Current time in the replay, in ticks (0 to frames - 1). */
    float time;

    int speed = 2;
    boolean paused;

    /** The killer's eyes are where the drone was. */
    boolean killerView;

    private final Minecraft mc;
    private final Log log;
    private final Actors actors;
    private final DroneCamera drone;
    private final KillerHand hand;
    private final ReplayEffects effects;
    private final Entity oldView;
    private final int oldPerspective;
    private final boolean oldHideGui;

    /** Ticks held at the end, before fading into the next round. */
    private int hold;

    /**
     * The start of the death replay's rounds, and the fade: ticks of the round fading out (0 = no),
     * fading in.
     */
    float loopFrom;

    private int looping;
    private boolean fadingIn;
    private float fade;
    private float prevFade;

    /** The replay's speed in this tick (the pitch of the sounds). */
    private float playRate = 1;

    /** The game mode your player had in the list before the replay (to restore), or null. */
    private WorldSettings.GameType oldMode;

    /** You at the current time, interpolated ({@link #at}). */
    private final Snap scratch = new Snap();

    Session(Minecraft mc, Source rec, boolean saved, Log log) {
        this.mc = mc;
        this.rec = rec;
        this.saved = saved;
        this.log = log;
        this.frames = rec.size();
        this.deathAt = KillerSearch.deathFrame(rec);
        int[] k = KillerSearch.find(rec, frames);
        this.killer = k[0];
        Snap ks = killer >= 0 ? rec.frame(k[1]).find(killer) : null;
        this.killerName = ks != null && ks.profile != null ? ks.profile.getName() : null;
        this.killerCard = KillerSearch.card(rec, killer, Math.min(deathAt, frames - 1));
        this.actors = new Actors(mc, rec);
        this.effects = new ReplayEffects(mc, rec);
        this.hand = new KillerHand(mc);
        oldView = mc.getRenderViewEntity();
        oldPerspective = mc.gameSettings.thirdPersonView;
        oldHideGui = mc.gameSettings.hideGUI;
        drone = new DroneCamera(mc);
        ReplayPlayer.register(mc);
        mc.setRenderViewEntity(drone.cam);
        mc.gameSettings.hideGUI = true; // no hand (the dead player's) on the eyes camera
        Hud.setHidden(true); // the whole HUD: the game draws the HUD even with a screen open
        spectator(true);
        seek(0);
    }

    /**
     * The speed at time t: the chosen one, times the slow motion around the death — it slows down
     * in the last second and a half, stays slow for the blow and the body falling, and goes back to
     * the normal pace for the items scattering.
     */
    private float rate(float t) {
        float base = SPEEDS[speed];
        float d = t - deathAt; // negative before the death
        if (d <= -SLOW_IN_FROM || d >= SLOW_OUT_TO) {
            return base;
        }
        if (d < -SLOW_IN_TO) {
            return base * (SLOW + (1 - SLOW) * (-d - SLOW_IN_TO) / (SLOW_IN_FROM - SLOW_IN_TO));
        }
        if (d > SLOW_OUT_FROM) {
            return base * (SLOW + (1 - SLOW) * (d - SLOW_OUT_FROM) / (SLOW_OUT_TO - SLOW_OUT_FROM));
        }
        return base * SLOW;
    }

    /** A game tick: moves the replay, the camera and the fade between rounds. */
    void tick() {
        frames = rec.size();
        prevFade = fade;
        if (looping > 0) { // end of a round: fade out, back to the start, fade in
            looping++;
            fade = Math.min(1, looping / (float) FADE_TICKS);
            if (looping > FADE_TICKS) {
                looping = 0;
                time = loopFrom;
                effects.skipTo(time);
                actors.sync(time, time, frames);
                moveDrone(true);
                view();
                aimPlayer();
                prevFade = fade = 1;
                fadingIn = true;
            }
            return;
        }
        if (fadingIn) {
            fade = Math.max(0, fade - 1F / FADE_TICKS);
            fadingIn = fade > 0;
        }
        float step = paused ? 0 : rate(time);
        playRate = paused ? 1 : step;
        float now = time;
        time = Math.min(frames - 1, time + step);
        actors.sync(now, time, frames);
        moveDrone(false);
        view();
        aimPlayer();
        effects.play(time, frames, playRate);
        effects.weather(time, frames);
        Entity k = killerView ? actors.get(killer) : null;
        hand.tick(k);
        if (DEBUG
                && k instanceof EntityOtherPlayerMP p
                && (p.hurtTime > 0 || p.cameraYaw > 0.01F)) {
            log.info(
                    String.format(
                            Locale.ROOT,
                            "killer's eyes: hurt %d, bob %.3f, walked %.2f",
                            p.hurtTime,
                            p.cameraYaw,
                            p.distanceWalkedModified - p.prevDistanceWalkedModified));
        }
        if (!saved && time >= frames - 1 && !growing) {
            if (++hold > HOLD_END) {
                hold = 0;
                looping = 1; // death replay: loops until the player respawns
            }
        } else {
            hold = 0;
        }
        if (DEBUG && (int) now / 20 != (int) time / 20) {
            debug();
        }
    }

    private void debug() {
        Snap me = at(time);
        Entity cam = drone.cam;
        log.info(
                String.format(
                        Locale.ROOT,
                        "t=%.1f/%d death=%d eyes=%s you=%s camera at %.1f %.1f %.1f sounds=%d",
                        time,
                        frames,
                        deathAt,
                        killerView ? "killer" : "drone",
                        me == null
                                ? "-"
                                : String.format(Locale.ROOT, "%.1f %.1f %.1f", me.x, me.y, me.z),
                        cam.posX,
                        cam.posY,
                        cam.posZ,
                        effects.soundsPlayed));
    }

    /** The killer at the current time (the frame's snap), only in their eyes; otherwise null. */
    Snap killerNow() {
        return killerView ? rec.frame(clamp((int) Math.floor(time))).find(killer) : null;
    }

    /** Saved replay: stops at the end (the player goes back, rewatches or leaves). */
    void finished() {
        if (saved && time >= frames - 1) {
            paused = true;
        }
    }

    /** Starts dark and fades in (the death replay comes in like that, after the death's fade). */
    void fadeIn() {
        prevFade = fade = 1;
        fadingIn = true;
    }

    /** The fade on the frame (0 nothing, 1 black). */
    float fade(float partialTicks) {
        return prevFade + (fade - prevFade) * partialTicks;
    }

    void seek(float ticks) {
        time = Math.max(0, Math.min(frames - 1, time + ticks));
        actors.sync(time, time, frames);
        moveDrone(true);
        view();
        aimPlayer();
        effects.skipTo(time); // the skipped stretch does not release particles
    }

    // ---- the camera: the drone and, in the slow motion, the killer's eyes ----

    private void moveDrone(boolean jump) {
        Snap me = at(time);
        if (me != null) {
            drone.update(me.x, me.y, me.z, me.yaw, jump);
        }
    }

    /**
     * Whose eyes now: in the death's slow motion, if the killer is in the scene, theirs (their body
     * is not drawn, and the hand with the item comes in {@link #render}); otherwise, the drone.
     */
    private void view() {
        Entity k = killer >= 0 ? actors.get(killer) : null;
        killerView = k != null && time >= deathAt - SLOW_IN_FROM;
        Entity want = killerView ? k : drone.cam;
        if (mc.getRenderViewEntity() != want) {
            mc.setRenderViewEntity(want);
        }
    }

    /**
     * The client player's look the same as the camera's: the game turns particles (and other
     * drawings facing the screen) to the player's look, not to the camera drawing. The last look
     * sent to the server goes along, so the game does not send the new look: nothing leaves here.
     */
    private void aimPlayer() {
        EntityPlayerSP p = mc.thePlayer;
        Entity v = mc.getRenderViewEntity();
        if (p == null || v == null || v == p) {
            return;
        }
        p.prevRotationYaw = v.prevRotationYaw;
        p.rotationYaw = v.rotationYaw;
        p.prevRotationPitch = v.prevRotationPitch;
        p.rotationPitch = v.rotationPitch;
        p.lastReportedYaw = p.rotationYaw;
        p.lastReportedPitch = p.rotationPitch;
    }

    /** You at time t, between the two neighboring frames; null if you are not recorded there. */
    private Snap at(float t) {
        int i = (int) Math.floor(t);
        float f = t - i;
        Snap a = rec.frame(clamp(i)).self();
        if (a == null) {
            return null;
        }
        Snap b = rec.frame(clamp(i + 1)).self();
        if (b == null) {
            b = a;
        }
        Snap s = scratch;
        s.x = Interp.lerp(a.x, b.x, f);
        s.y = Interp.lerp(a.y, b.y, f);
        s.z = Interp.lerp(a.z, b.z, f);
        s.yaw = Interp.lerpAngle(a.yaw, b.yaw, f);
        s.pitch = Interp.lerp(a.pitch, b.pitch, f);
        return s;
    }

    private int clamp(int i) {
        return Math.max(0, Math.min(frames - 1, i));
    }

    /** The frame: first person (the drone and the killer's eyes are the camera's exact point). */
    void render() {
        mc.gameSettings.thirdPersonView = 0;
        if (killerView) {
            hand.begin(actors.get(killer));
        }
    }

    /** The client player back after the killer's hand. */
    void endHand() {
        hand.end();
    }

    boolean isActor(Object entity) {
        return entity == drone.cam || actors.contains(entity);
    }

    // ---- spectator (client only) ----

    /**
     * Sets your player as a spectator in your client's player list (or restores the previous mode):
     * the game knows from it whether to draw as a spectator.
     */
    private void spectator(boolean on) {
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null || mc.thePlayer == null) {
            return;
        }
        UUID id = mc.thePlayer.getGameProfile().getId();
        NetworkPlayerInfo info = handler.getPlayerInfo(id);
        if (info == null) {
            return;
        }
        WorldSettings.GameType mode;
        if (on) {
            oldMode = info.getGameType();
            mode = WorldSettings.GameType.SPECTATOR;
        } else {
            if (oldMode == null) {
                return;
            }
            mode = oldMode;
            oldMode = null;
        }
        PlayerListPackets.gameMode(handler, id, mode);
    }

    /** Restores the camera, the perspective and the HUD of before. */
    void close() {
        hand.close();
        if (!saved) {
            effects.restoreWeather();
        }
        mc.setRenderViewEntity(
                oldView != null && oldView.worldObj == mc.theWorld ? oldView : mc.thePlayer);
        mc.gameSettings.thirdPersonView = oldPerspective;
        mc.gameSettings.hideGUI = oldHideGui;
        Hud.setHidden(false);
        spectator(false);
        actors.clear();
    }
}
