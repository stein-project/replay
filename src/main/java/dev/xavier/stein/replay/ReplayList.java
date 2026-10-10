/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Render;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.awt.Desktop;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The saved replays: one per row, newest first, with when, where, who killed you and the length,
 * and the watch and delete buttons (delete asks for a second click). Watching builds the replay's
 * world, so it only works outside a match — and that is where you reach this screen from, through
 * the Stein Loader menu.
 */
final class ReplayList extends GuiScreen {

    private static final int ROWS = 6;
    private static final int ROW_H = 30;
    private static final int WATCH = 100;
    private static final int DELETE = 200;
    private static final int PREV = 1;
    private static final int NEXT = 2;
    private static final int FOLDER = 3;
    private static final int BACK = 4;
    private static final int TOP = 50;
    private static final int WHITE = 0xFFFFFFFF;

    private final SteinReplay mod;
    private final GuiScreen parent;
    private List<Recording> list;
    private int page;

    /** The row that asked to be deleted (waiting for the second click) and until when. */
    private int confirm = -1;

    private long confirmUntil;
    private String message = "";

    /** The texts, in the game's font (as the game's own screens). */
    private final Runnable texts = this::drawTexts;

    ReplayList(SteinReplay mod, GuiScreen parent) {
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        list = mod.files().saved();
        page = Math.max(0, Math.min(page, pages() - 1));
        buttonList.clear();
        int x = width / 2 + 60;
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= list.size()) {
                break;
            }
            GuiButton watch =
                    new GuiButton(
                            WATCH + i,
                            x,
                            TOP + i * ROW_H + 3,
                            70,
                            20,
                            Lang.tr("steinreplay.watch"));
            watch.enabled = mc.theWorld == null;
            buttonList.add(watch);
            buttonList.add(
                    new GuiButton(
                            DELETE + i,
                            x + 74,
                            TOP + i * ROW_H + 3,
                            60,
                            20,
                            confirm == index
                                    ? "§c" + Lang.tr("steinreplay.confirm")
                                    : Lang.tr("steinreplay.delete")));
        }
        int bottom = height - 28;
        GuiButton prev = new GuiButton(PREV, width / 2 - 210, bottom, 60, 20, "<");
        prev.enabled = page > 0;
        GuiButton next = new GuiButton(NEXT, width / 2 - 146, bottom, 60, 20, ">");
        next.enabled = page < pages() - 1;
        buttonList.add(prev);
        buttonList.add(next);
        buttonList.add(
                new GuiButton(
                        FOLDER, width / 2 - 80, bottom, 120, 20, Lang.tr("steinreplay.folder")));
        buttonList.add(new GuiButton(BACK, width / 2 + 44, bottom, 120, 20, Lang.tr("gui.done")));
    }

    GuiScreen parent() {
        return parent;
    }

    private int pages() {
        return Math.max(1, (list.size() + ROWS - 1) / ROWS);
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == BACK) {
            mc.displayGuiScreen(parent);
        } else if (b.id == PREV) {
            page--;
            initGui();
        } else if (b.id == NEXT) {
            page++;
            initGui();
        } else if (b.id == FOLDER) {
            openFolder();
        } else if (b.id >= DELETE) {
            delete(page * ROWS + b.id - DELETE);
        } else if (b.id >= WATCH) {
            Recording r = list.get(page * ROWS + b.id - WATCH);
            message = mod.watch(r.file, this);
        }
    }

    private void openFolder() {
        File dir = mod.files().dir();
        dir.mkdirs();
        try {
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            message = e.toString();
        }
    }

    private void delete(int index) {
        if (confirm == index && System.currentTimeMillis() < confirmUntil) {
            File f = list.get(index).file;
            message = f.delete() ? "" : Lang.tr("steinreplay.deleteFailed");
            confirm = -1;
        } else {
            confirm = index;
            confirmUntil = System.currentTimeMillis() + 3000;
        }
        initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (confirm >= 0 && System.currentTimeMillis() >= confirmUntil) {
            confirm = -1;
            initGui();
        }
        Render.gameFont(texts);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawTexts() {
        centered(Lang.tr("steinreplay.saved"), 20);
        int x = width / 2 - 210;
        if (list.isEmpty()) {
            centered("§7" + Lang.tr("steinreplay.none"), TOP + 20);
        }
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= list.size()) {
                break;
            }
            Recording r = list.get(index);
            int y = TOP + i * ROW_H;
            Render.rect(x - 4, y, width / 2 + 198 - (x - 4), ROW_H - 4, 0x40000000);
            String when = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(r.when));
            Render.text(
                    when + "  §7" + (r.server.isEmpty() ? "" : r.server), x, y + 4, WHITE, true);
            String detail =
                    (r.killer.isEmpty()
                                    ? Lang.tr("steinreplay.noKiller")
                                    : Lang.tr("steinreplay.killedBy", r.killer))
                            + "  §7"
                            + r.length / 20
                            + " s";
            Render.text(detail, x, y + 15, WHITE, true);
        }
        if (mc.theWorld != null) {
            centered("§e" + Lang.tr("steinreplay.leaveToWatch"), height - 42);
        } else if (!message.isEmpty()) {
            centered("§c" + message, height - 42);
        }
    }

    /** Text centered on the screen, with a shadow (as the game's titles). */
    private void centered(String text, int y) {
        Render.text(text, width / 2 - Render.textWidth(text) / 2, y, WHITE, true);
    }
}
