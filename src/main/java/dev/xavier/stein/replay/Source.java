/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/** De onde o replay le os quadros: o anel de agora ({@link Recorder}) ou um replay salvo ({@link Recording}). */
interface Source {

    int size();

    /** O quadro i, do mais velho (0) ao mais novo (size - 1). */
    Recorder.Frame frame(int i);
}
