/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import net.minecraft.client.Minecraft;

/**
 * The death in slow motion, between dying and the replay: the client's clock runs slower (the
 * server keeps its pace), the {@link DyingScreen} turns red and fades to black, and the game keeps
 * recording (the body falling, the items).
 */
final class DeathSlowMotion {

    /** Ticks of the slow motion, the game's speed in it and the tick where it starts to fade. */
    static final int TICKS = 30;

    private static final float SPEED = 0.4F;
    private static final int FADE_FROM = 21;

    /** Ticks into the slow motion (-1: outside it). */
    private int ticks = -1;

    boolean active() {
        return ticks >= 0;
    }

    /** Starts it: the clock slows down. */
    void start(Minecraft mc) {
        ticks = 0;
        timerSpeed(mc, SPEED);
    }

    /** Stops it: the clock back to normal. */
    void stop(Minecraft mc) {
        ticks = -1;
        timerSpeed(mc, 1);
    }

    /** One tick more; true when it is over (the clock is back to normal then). */
    boolean tick(Minecraft mc) {
        if (++ticks >= TICKS) {
            stop(mc);
            return true;
        }
        return false;
    }

    /** The death red now (0 to 1): in two ticks, to see it at once (and not look frozen). */
    float red(float partialTicks) {
        return ticks < 0 ? 0 : Math.min(1, (ticks + partialTicks) / 2F);
    }

    /** The fraction of the death's fade now (0 to 1), on the frame. */
    float fade(float partialTicks) {
        return ticks < 0
                ? 1
                : Math.max(
                        0, Math.min(1, (ticks + partialTicks - FADE_FROM) / (TICKS - FADE_FROM)));
    }

    /**
     * The game clock's speed (1 = normal): only the client slows down, the server stays the same.
     */
    private static void timerSpeed(Minecraft mc, float speed) {
        mc.timer.timerSpeed = speed;
    }
}
