/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityThrowable;

import java.util.List;

/**
 * The last seconds around you, in a ring of frames (one per tick): position, rotations, arm, legs,
 * damage, death, sneaking, item in use and equipment of every player up to {@link #RADIUS} blocks
 * away, you included, plus nearby items, XP, arrows, mobs and thrown things, and the tick's
 * particles and sounds ({@link ParticleTap}).
 *
 * <p>It only reads what the client already has (what the server sent) and never asks the server
 * anything. Frames and snaps are created once and reused: recording a tick copies a few numbers per
 * entity, allocating nothing. Items are kept by reference — the game replaces the object when the
 * server sends new equipment.
 */
final class Recorder implements Source {

    /** How far players are recorded, in blocks. */
    static final double RADIUS = 48;

    /** Players per frame, at most (the nearest stay). */
    static final int MAX_PLAYERS = 48;

    /**
     * Everything else per frame, at most, and how far: items on the ground, XP, arrows, mobs and
     * thrown things (potion, pearl, snowball, the fishing line).
     */
    static final int MAX_OTHERS = 48;

    static final double OTHER_RADIUS = 32;
    static final int MAX_SNAPS = MAX_PLAYERS + MAX_OTHERS;

    private final Frame[] ring;

    /** Where the next frame goes, and how many there are. */
    private int next;

    private int size;
    private int dimension = Integer.MIN_VALUE;
    private final double[] dist = new double[MAX_PLAYERS];

    Recorder(int ticks) {
        ring = new Frame[ticks];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new Frame();
        }
    }

    void clear() {
        next = 0;
        size = 0;
        ParticleTap.clear();
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Frame frame(int i) {
        return ring[(next - size + i + ring.length * 2) % ring.length];
    }

    /** The newest frame has you in it (the replay needs you in the scene). */
    boolean hasSelf() {
        return size > 0 && frame(size - 1).self() != null;
    }

    /** Records the current tick. */
    void record(Minecraft mc) {
        EntityPlayer me = mc.thePlayer;
        if (me == null || mc.theWorld == null) {
            clear();
            return;
        }
        if (me.dimension != dimension) { // another world: what was recorded no longer applies
            dimension = me.dimension;
            clear();
        }
        ParticleTap.attach(mc.theWorld);
        fill(ring[next], mc, dist);
        next = (next + 1) % ring.length;
        size = Math.min(size + 1, ring.length);
    }

    /** What kind of snap an entity is, or -1 for what is not recorded (players go apart). */
    private static int kindOf(Entity e) {
        if (e instanceof EntityItem) {
            return Snap.ITEM;
        }
        if (e instanceof EntityXPOrb) {
            return Snap.XP;
        }
        if (e instanceof EntityArrow) {
            return Snap.ARROW;
        }
        if (e instanceof EntityFishHook) {
            return Snap.HOOK;
        }
        if (e instanceof EntityThrowable) {
            return Snap.THROWN;
        }
        return e instanceof EntityLivingBase
                        && !(e instanceof EntityPlayer)
                        && EntityList.getEntityID(e) > 0
                ? Snap.MOB
                : -1;
    }

    /**
     * The current tick into a frame: the players up to {@link #RADIUS} (the nearest, when there are
     * many), then items, XP, arrows, mobs and thrown things up to {@link #OTHER_RADIUS}, the world
     * time and weather, the particles and the sounds.
     */
    static void fill(Frame f, Minecraft mc, double[] dist) {
        EntityPlayer me = mc.thePlayer;
        f.count = 0;
        ParticleTap.take(f.events);
        f.worldTime = mc.theWorld.getWorldTime();
        f.rain = mc.theWorld.getRainStrength(1);
        f.thunder = mc.theWorld.getThunderStrength(1);
        List<EntityPlayer> players = mc.theWorld.playerEntities;
        for (EntityPlayer p : players) {
            if (Actors.isFake(p)) {
                continue; // the replay's own copies (in the world while it plays)
            }
            boolean isSelf = p == me;
            double d = isSelf ? 0 : p.getDistanceSqToEntity(me);
            if (d > RADIUS * RADIUS) {
                continue;
            }
            if (f.count < MAX_PLAYERS) {
                dist[f.count] = d;
                f.snaps[f.count++].copy(p, isSelf);
                continue;
            }
            // Full: replace the farthest one, if this one is nearer.
            int far = 0;
            for (int i = 1; i < MAX_PLAYERS; i++) {
                if (dist[i] > dist[far]) {
                    far = i;
                }
            }
            if (d < dist[far]) {
                dist[far] = d;
                f.snaps[far].copy(p, isSelf);
            }
        }
        List<Entity> all = mc.theWorld.loadedEntityList;
        for (int i = 0; i < all.size() && f.count < f.snaps.length; i++) {
            Entity e = all.get(i);
            // The cheap tests first: kindOf ends in a map lookup by class for every mob, and most
            // loaded entities are out of range. None of the three has side effects, so the order
            // does not change what is recorded.
            if (Actors.isFake(e) || !(e.getDistanceSqToEntity(me) <= OTHER_RADIUS * OTHER_RADIUS)) {
                continue;
            }
            int what = kindOf(e);
            if (what >= 0) {
                f.snaps[f.count++].copy(e, what);
            }
        }
    }
}
