/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

/**
 * {@code config/steinreplay.json}. The field names are the JSON keys players already have: do not
 * rename them.
 */
final class ReplayConfig {

    /** Record the fight, for the replay. */
    boolean enabled = true;

    /**
     * Show it in place of the death screen. Off by default: there are servers that do not allow it
     * during the match, and the mod no longer knows which; it stays recorded and saved anyway.
     */
    boolean showOnDeath = false;

    /** How many seconds to play. */
    int seconds = 15;

    /** Save every death replay. */
    boolean save = true;

    /** Back to the defaults. */
    void reset() {
        ReplayConfig d = new ReplayConfig();
        enabled = d.enabled;
        showOnDeath = d.showOnDeath;
        seconds = d.seconds;
        save = d.save;
    }
}
