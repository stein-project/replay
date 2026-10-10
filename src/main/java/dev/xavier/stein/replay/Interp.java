/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/** Interpolation and small math shared by the replay. */
final class Interp {

    private Interp() {}

    static double sq(double v) {
        return v * v;
    }

    static double lerp(double a, double b, float f) {
        return a + (b - a) * f;
    }

    static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    /** From angle a to angle b (degrees) the short way round. */
    static float lerpAngle(float a, float b, float f) {
        float d = ((b - a) % 360 + 540) % 360 - 180;
        return a + d * f;
    }

    /** The direction of a look (the game's yaw and pitch), as x, y, z. */
    static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[] {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }
}
