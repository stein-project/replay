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
 * As particulas e os sons do mundo, para o replay: um ouvinte do mundo do cliente (como o RenderGlobal), que guarda cada
 * particula pedida ({@code World.spawnParticle}: golpe critico, poeira da morte, pocao, passos, chuva...) e cada efeito de
 * bloco ({@code playAuxSFX}: bloco quebrando, pocao arremessada estourando) ate {@link Recorder#RADIUS} blocos de voce.
 * O {@link Recorder} leva o que chegou no tick para o quadro, e a {@link Session} pede tudo de novo na hora certa.
 *
 * <p>So guarda numeros (tipo, posicao, velocidade e ate dois parametros) em vetores fixos: nada e alocado por particula.
 */
final class ParticleTap implements IWorldAccess {

    /** Particulas e efeitos de bloco por tick, no maximo (o resto do tick fica de fora). */
    static final int MAX_PARTICLES = 128;
    static final int MAX_AUX = 16;
    static final int MAX_SOUNDS = 32;

    private static final ParticleTap INSTANCE = new ParticleTap();
    private static World attached;
    /** O replay esta pedindo as particulas gravadas: nao sao gravadas de novo. */
    static boolean replaying;

    /** O que chegou neste tick, ainda sem quadro. */
    private final Recorder.Events pending = new Recorder.Events();

    private ParticleTap() {
    }

    /** Ouve o mundo de agora (uma vez por mundo); um mundo novo comeca sem nada pendente. */
    static void attach(World world) {
        if (world == null || world == attached) {
            return;
        }
        world.addWorldAccess(INSTANCE);
        attached = world;
        INSTANCE.pending.count = 0;
        INSTANCE.pending.auxCount = 0;
        INSTANCE.pending.soundCount = 0;
    }

    /** Leva o que chegou desde o ultimo tick para o quadro. */
    static void take(Recorder.Events into) {
        into.copy(INSTANCE.pending);
        INSTANCE.pending.count = 0;
        INSTANCE.pending.auxCount = 0;
        INSTANCE.pending.soundCount = 0;
    }

    static void clear() {
        INSTANCE.pending.count = 0;
        INSTANCE.pending.auxCount = 0;
        INSTANCE.pending.soundCount = 0;
    }

    /** Um som do mundo que vai tocar ({@link SteinReplay#onPlaySound}): entra no quadro do tick. */
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
    public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double dx, double dy, double dz,
            int... params) {
        if (replaying || !near(x, y, z)) {
            return;
        }
        pending.add(id, ignoreRange, x, y, z, dx, dy, dz, params);
    }

    @Override
    public void playAuxSFX(EntityPlayer player, int type, BlockPos pos, int data) {
        // So os efeitos com particula (2000 em diante: fumaca, bloco quebrando, pocao estourando...); os de 1000 sao
        // so som.
        if (replaying || type < 2000 || !near(pos.getX(), pos.getY(), pos.getZ())) {
            return;
        }
        pending.addAux(type, pos.getX(), pos.getY(), pos.getZ(), data);
    }

    @Override
    public void markBlockForUpdate(BlockPos pos) {
    }

    @Override
    public void notifyLightSet(BlockPos pos) {
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
    }

    @Override
    public void playSound(String sound, double x, double y, double z, float volume, float pitch) {
    }

    @Override
    public void playSoundToNearExcept(EntityPlayer except, String sound, double x, double y, double z, float volume,
            float pitch) {
    }

    @Override
    public void onEntityAdded(Entity entity) {
    }

    @Override
    public void onEntityRemoved(Entity entity) {
    }

    @Override
    public void playRecord(String record, BlockPos pos) {
    }

    @Override
    public void broadcastSound(int id, BlockPos pos, int data) {
    }

    @Override
    public void sendBlockBreakProgress(int breaker, BlockPos pos, int progress) {
    }
}
