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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;

/**
 * The world's particles and sounds, for the replay: a listener of the client world (like the game's
 * own world renderer) that keeps every particle asked for ({@code World.spawnParticle}: critical
 * hit, death dust, potion, footsteps, rain...) and every block effect ({@code playAuxSFX}: block
 * breaking, splash potion) up to {@link Recorder#RADIUS} blocks from you. The {@link Recorder}
 * moves what arrived in the tick into the frame, and the replay asks for all of it again at the
 * right time ({@link ReplayEffects}).
 *
 * <p>It only keeps numbers (type, position, speed and up to two parameters) in fixed arrays:
 * nothing is allocated per particle.
 */
final class ParticleTap implements IWorldAccess {

    /** Particles, block effects and sounds per tick, at most (the rest of the tick is left out). */
    static final int MAX_PARTICLES = 128;

    static final int MAX_AUX = 16;
    static final int MAX_SOUNDS = 32;

    private static final ParticleTap INSTANCE = new ParticleTap();
    private static World attached;

    /** The replay is asking for the recorded particles: they are not recorded again. */
    static boolean replaying;

    /** What arrived in this tick, not in a frame yet. */
    private final Events pending = new Events();

    private ParticleTap() {}

    /** Listens to the current world (once per world); a new world starts with nothing pending. */
    static void attach(World world) {
        if (world == null || world == attached) {
            return;
        }
        world.addWorldAccess(INSTANCE);
        attached = world;
        INSTANCE.pending.clear();
    }

    /** Moves what arrived since the last tick into the frame. */
    static void take(Events into) {
        into.copy(INSTANCE.pending);
        INSTANCE.pending.clear();
    }

    static void clear() {
        INSTANCE.pending.clear();
    }

    /** A world sound about to play ({@link SteinReplay#onPlaySound}): it goes into the tick. */
    static void sound(String name, float x, float y, float z, float volume, float pitch) {
        if (!replaying && near(x, y, z)) {
            INSTANCE.pending.addSound(name, x, y, z, volume, pitch);
        }
    }

    private static boolean near(double x, double y, double z) {
        EntityPlayer me = Minecraft.getMinecraft().thePlayer;
        if (me == null) {
            return false;
        }
        double dx = x - me.posX;
        double dy = y - me.posY;
        double dz = z - me.posZ;
        return dx * dx + dy * dy + dz * dz <= Recorder.RADIUS * Recorder.RADIUS;
    }

    @Override
    public void spawnParticle(
            int id,
            boolean ignoreRange,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            int... params) {
        if (replaying || !near(x, y, z)) {
            return;
        }
        pending.add(id, ignoreRange, x, y, z, dx, dy, dz, params);
    }

    @Override
    public void playAuxSFX(EntityPlayer player, int type, BlockPos pos, int data) {
        // Only the effects with particles (2000 and up: smoke, block breaking, splash potion...);
        // the 1000s are sound only.
        if (replaying || type < 2000 || !near(pos.getX(), pos.getY(), pos.getZ())) {
            return;
        }
        pending.addAux(type, pos.getX(), pos.getY(), pos.getZ(), data);
    }

    @Override
    public void markBlockForUpdate(BlockPos pos) {}

    @Override
    public void notifyLightSet(BlockPos pos) {}

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}

    @Override
    public void playSound(String sound, double x, double y, double z, float volume, float pitch) {}

    @Override
    public void playSoundToNearExcept(
            EntityPlayer except,
            String sound,
            double x,
            double y,
            double z,
            float volume,
            float pitch) {}

    @Override
    public void onEntityAdded(Entity entity) {}

    @Override
    public void onEntityRemoved(Entity entity) {}

    @Override
    public void playRecord(String record, BlockPos pos) {}

    @Override
    public void broadcastSound(int id, BlockPos pos, int data) {}

    @Override
    public void sendBlockBreakProgress(int breaker, BlockPos pos, int progress) {}
}
