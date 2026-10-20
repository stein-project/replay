/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/**
 * The moment of death, between dying and the replay: the {@link DyingScreen} turns red and fades to
 * black while the game keeps recording (the body falling, the items). The game runs at its normal
 * pace: up to 0.1.x this slowed the client's clock down, which also slowed what the client sends
 * the server, and nothing here may change that. The slow motion is in the replay itself, around the
 * death frame ({@link Session}), which only draws what was recorded.
 */
final class DeathFade {

    /** Ticks from the death to the replay, and the tick where the fade to black starts. */
    static final int TICKS = 30;

    private static final int FADE_FROM = 21;

    /** Ticks into the fade (-1: outside it). */
    private int ticks = -1;

    boolean active() {
        return ticks >= 0;
    }

    void start() {
        ticks = 0;
    }

    void stop() {
        ticks = -1;
    }

    /** One tick more; true when it is over. */
    boolean tick() {
        if (++ticks >= TICKS) {
            stop();
            return true;
        }
        return false;
    }

    /** The death red now (0 to 1): in two ticks, to see it at once (and not look frozen). */
    float red(float partialTicks) {
        return ticks < 0 ? 0 : Math.min(1, (ticks + partialTicks) / 2F);
    }

    /** The fraction of the fade to black now (0 to 1), on the frame. */
    float fade(float partialTicks) {
        return ticks < 0
                ? 1
                : Math.max(
                        0, Math.min(1, (ticks + partialTicks - FADE_FROM) / (TICKS - FADE_FROM)));
    }
}
