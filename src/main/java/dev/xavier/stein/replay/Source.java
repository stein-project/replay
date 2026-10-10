/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/**
 * Where a replay reads its frames from: the live ring ({@link Recorder}) or a saved replay ({@link
 * Recording}).
 */
interface Source {

    int size();

    /** Frame i, from the oldest (0) to the newest (size - 1). */
    Frame frame(int i);
}
