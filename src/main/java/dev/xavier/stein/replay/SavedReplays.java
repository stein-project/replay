/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Log;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * The saved replays on disk: {@code steinloader/replays/<date>.steinreplay}, newest first, at most
 * {@link #MAX_SAVED} (past that, the oldest goes).
 */
final class SavedReplays {

    /** Saved replays kept. */
    static final int MAX_SAVED = 50;

    static final String EXT = ".steinreplay";

    private final Log log;

    SavedReplays(Log log) {
        this.log = log;
    }

    File dir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "steinloader/replays");
    }

    /** Writes on another thread (the game does not wait for the disk) and drops the oldest. */
    void saveLater(Recording r) {
        File f =
                new File(
                        dir(),
                        new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(r.when)) + EXT);
        Thread t =
                new Thread(
                        () -> {
                            try {
                                ReplayFile.save(r, f);
                                List<File> files = list();
                                for (int i = MAX_SAVED; i < files.size(); i++) {
                                    files.get(i).delete();
                                }
                                log.info(
                                        "replay saved: "
                                                + f.getName()
                                                + " ("
                                                + f.length() / 1024
                                                + " KB)");
                            } catch (Exception e) {
                                log.error("could not save the replay " + f, e);
                            }
                        },
                        "Stein Replay");
        t.setDaemon(true);
        t.start();
    }

    /** The replay files, newest first. */
    List<File> list() {
        File[] fs = dir().listFiles((d, n) -> n.endsWith(EXT));
        List<File> out = new ArrayList<>(fs == null ? List.of() : Arrays.asList(fs));
        out.sort(Comparator.comparing(File::getName).reversed());
        return out;
    }

    /** The saved replays (only each one's header), newest first. */
    List<Recording> saved() {
        List<Recording> out = new ArrayList<>();
        for (File f : list()) {
            try {
                out.add(ReplayFile.header(f));
            } catch (Exception e) {
                log.warn("unreadable replay: " + f.getName() + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }
}
