/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/** One tick of a recording: the entities in it, its particles and sounds, and the weather. */
final class Frame {

    final Snap[] snaps;
    int count;

    /** The tick's particles, block effects and sounds. */
    final Events events = new Events();

    /** The world time (-1 = not recorded, an old replay), the rain and the thunder (0 to 1). */
    long worldTime = -1;

    float rain;
    float thunder;

    Frame() {
        this(Recorder.MAX_SNAPS);
    }

    Frame(int capacity) {
        snaps = new Snap[capacity];
        for (int i = 0; i < snaps.length; i++) {
            snaps[i] = new Snap();
        }
    }

    /** The time and weather of another frame. */
    void copyWeather(Frame f) {
        worldTime = f.worldTime;
        rain = f.rain;
        thunder = f.thunder;
    }

    Snap find(int id) {
        for (int i = 0; i < count; i++) {
            if (snaps[i].id == id) {
                return snaps[i];
            }
        }
        return null;
    }

    Snap self() {
        for (int i = 0; i < count; i++) {
            if (snaps[i].self) {
                return snaps[i];
            }
        }
        return null;
    }
}
