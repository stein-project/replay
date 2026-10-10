/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/** The particles, block effects and sounds of one tick ({@link ParticleTap}), in fixed arrays. */
final class Events {

    private static final int[] EMPTY = new int[0];

    int count;
    final int[] type = new int[ParticleTap.MAX_PARTICLES];
    final boolean[] ignoreRange = new boolean[ParticleTap.MAX_PARTICLES];

    /** x, y, z of each particle. */
    final double[] pos = new double[ParticleTap.MAX_PARTICLES * 3];

    /** The speed (or the color, depending on the type) of each particle. */
    final float[] vel = new float[ParticleTap.MAX_PARTICLES * 3];

    /** Up to two parameters (the block or the item of the fragments). */
    final byte[] argc = new byte[ParticleTap.MAX_PARTICLES];

    final int[] args = new int[ParticleTap.MAX_PARTICLES * 2];

    int auxCount;

    /** Type, x, y, z and data of each block effect. */
    final int[] aux = new int[ParticleTap.MAX_AUX * 5];

    /** The tick's sounds: the name ("domain:path") and x, y, z, volume and pitch of each. */
    int soundCount;

    final String[] soundName = new String[ParticleTap.MAX_SOUNDS];
    final float[] sound = new float[ParticleTap.MAX_SOUNDS * 5];

    void addSound(String name, float x, float y, float z, float volume, float pitch) {
        if (soundCount >= soundName.length) {
            return;
        }
        int i = soundCount++;
        soundName[i] = name;
        sound[i * 5] = x;
        sound[i * 5 + 1] = y;
        sound[i * 5 + 2] = z;
        sound[i * 5 + 3] = volume;
        sound[i * 5 + 4] = pitch;
    }

    void add(
            int id,
            boolean ignore,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            int[] params) {
        if (count >= type.length) {
            return;
        }
        int i = count++;
        type[i] = id;
        ignoreRange[i] = ignore;
        pos[i * 3] = x;
        pos[i * 3 + 1] = y;
        pos[i * 3 + 2] = z;
        vel[i * 3] = (float) dx;
        vel[i * 3 + 1] = (float) dy;
        vel[i * 3 + 2] = (float) dz;
        int n = params == null ? 0 : Math.min(2, params.length);
        argc[i] = (byte) n;
        for (int k = 0; k < n; k++) {
            args[i * 2 + k] = params[k];
        }
    }

    void addAux(int t, int x, int y, int z, int data) {
        if (auxCount * 5 >= aux.length) {
            return;
        }
        int i = auxCount++ * 5;
        aux[i] = t;
        aux[i + 1] = x;
        aux[i + 2] = y;
        aux[i + 3] = z;
        aux[i + 4] = data;
    }

    /** The parameters of particle i, as the game asks for them. */
    int[] params(int i) {
        int n = argc[i];
        return n == 0
                ? EMPTY
                : n == 1 ? new int[] {args[i * 2]} : new int[] {args[i * 2], args[i * 2 + 1]};
    }

    void clear() {
        count = 0;
        auxCount = 0;
        soundCount = 0;
    }

    void copy(Events e) {
        count = e.count;
        System.arraycopy(e.type, 0, type, 0, count);
        System.arraycopy(e.ignoreRange, 0, ignoreRange, 0, count);
        System.arraycopy(e.pos, 0, pos, 0, count * 3);
        System.arraycopy(e.vel, 0, vel, 0, count * 3);
        System.arraycopy(e.argc, 0, argc, 0, count);
        System.arraycopy(e.args, 0, args, 0, count * 2);
        auxCount = e.auxCount;
        System.arraycopy(e.aux, 0, aux, 0, auxCount * 5);
        soundCount = e.soundCount;
        System.arraycopy(e.soundName, 0, soundName, 0, soundCount);
        System.arraycopy(e.sound, 0, sound, 0, soundCount * 5);
    }
}
