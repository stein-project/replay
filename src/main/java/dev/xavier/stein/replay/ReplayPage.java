/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Chat;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.ModConfig;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The Replay page on the Panel: the options, and the saved replays (clicking one says where to
 * watch it — outside a match —, the bin deletes it).
 */
final class ReplayPage {

    private final ModConfig<ReplayConfig> config;
    private final SavedReplays files;

    /** The page's list, read when the Panel opens. */
    private List<Recording> pageList = new ArrayList<>();

    ReplayPage(ModConfig<ReplayConfig> config, SavedReplays files) {
        this.config = config;
        this.files = files;
    }

    void build(Page page) {
        ReplayConfig c = config.get();
        page.icon("loop");
        page.section(tr("steinreplay.title"));
        page.option(
                Option.toggle(
                        tr("steinreplay.enabled"),
                        () -> c.enabled,
                        v -> {
                            c.enabled = v;
                            config.saveSoon();
                        }));
        page.option(
                Option.slider(
                        tr("steinreplay.seconds"),
                        5,
                        SteinReplay.MAX_SECONDS,
                        5,
                        () -> c.seconds,
                        v -> {
                            c.seconds = (int) Math.round(v);
                            config.saveSoon();
                        },
                        v -> Math.round(v) + " s"));
        page.option(
                Option.toggle(
                        tr("steinreplay.save"),
                        () -> c.save,
                        v -> {
                            c.save = v;
                            config.saveSoon();
                        }));
        page.restore(
                () -> {
                    c.reset();
                    config.save();
                });

        page.section(tr("steinreplay.saved"));
        pageList = files.saved();
        page.rows(this::rows, tr("steinreplay.none"));
    }

    private List<Page.Item> rows() {
        List<Page.Item> rows = new ArrayList<>();
        for (Recording r : pageList) {
            String when = new SimpleDateFormat("dd/MM HH:mm").format(new Date(r.when));
            String title = when + (r.server.isEmpty() ? "" : "  §7" + r.server);
            String detail = (r.killer.isEmpty() ? "" : r.killer + " · ") + r.length / 20 + " s";
            rows.add(
                    new Page.Item(
                            title,
                            detail,
                            false,
                            () -> Chat.print("§7" + tr("steinreplay.watchHint")),
                            () -> {
                                if (r.file.delete()) {
                                    pageList.remove(r);
                                }
                            }));
        }
        return rows;
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}
