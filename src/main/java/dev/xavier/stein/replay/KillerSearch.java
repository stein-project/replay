/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import static dev.xavier.stein.replay.Interp.sq;

/** Finding the death and the killer in a recording. */
final class KillerSearch {

    private KillerSearch() {}

    /**
     * Who killed you: the player nearest to you and swinging (the arm mid-swing) in the last second
     * before you took the last damage. With nobody swinging within 6 blocks, nobody.
     *
     * @return {id, frame where they were seen}
     */
    static int[] find(Source rec, int frames) {
        int last = frames - 1;
        for (int i = frames - 1; i >= 0; i--) { // the last hit you took
            Snap me = rec.frame(i).self();
            if (me != null && me.hurtTime > 0) {
                last = i;
                if (me.hurtTime >= 9) {
                    break;
                }
            }
        }
        int best = -1;
        int seen = -1;
        double bestD = 36;
        for (int i = Math.max(0, last - 20); i <= last; i++) {
            Frame f = rec.frame(i);
            Snap me = f.self();
            if (me == null) {
                continue;
            }
            for (int j = 0; j < f.count; j++) {
                Snap s = f.snaps[j];
                if (s.self || s.kind != Snap.PLAYER || s.swing <= 0) {
                    continue;
                }
                double d = sq(s.x - me.x) + sq(s.y - me.y) + sq(s.z - me.z);
                if (d < bestD) {
                    bestD = d;
                    best = s.id;
                    seen = i;
                }
            }
        }
        return new int[] {best, seen};
    }

    /**
     * The death frame: the first one where you appear dying (the body falling); without any (the
     * death replay starts before they arrive), the last frame now — the tick you died.
     */
    static int deathFrame(Source rec) {
        for (int i = 0; i < rec.size(); i++) {
            Snap me = rec.frame(i).self();
            if (me != null && me.deathTime > 0) {
                return i;
            }
        }
        return Math.max(0, rec.size() - 1);
    }

    /** The killer as last seen up to frame {@code from}, for the card (a copy), or null. */
    static Snap card(Source rec, int killer, int from) {
        if (killer < 0) {
            return null;
        }
        for (int i = from; i >= 0; i--) {
            Snap s = rec.frame(i).find(killer);
            if (s != null) {
                Snap card = new Snap();
                card.copy(s);
                return card;
            }
        }
        return null;
    }
}
