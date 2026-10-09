/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Lang;
import java.io.File;
import java.util.List;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * Os replays salvos: um por linha, do mais novo ao mais velho, com quando, onde, quem matou e a duracao, e os botoes
 * de assistir e apagar (apagar pede um segundo clique). Assistir monta o mundo do replay, entao so funciona fora de
 * uma partida — e daqui, pelo menu do Stein Loader, que se chega a ela.
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

    private final SteinReplay mod;
    private final GuiScreen parent;
    private List<Recording> list;
    private int page;
    /** A linha que pediu para apagar (espera o segundo clique) e ate quando. */
    private int confirm = -1;
    private long confirmUntil;
    private String message = "";

    ReplayList(SteinReplay mod, GuiScreen parent) {
        this.mod = mod;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        list = mod.saved();
        page = Math.max(0, Math.min(page, pages() - 1));
        buttonList.clear();
        int top = 50;
        int x = width / 2 + 60;
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= list.size()) {
                break;
            }
            GuiButton watch = new GuiButton(WATCH + i, x, top + i * ROW_H + 3, 70, 20, Lang.tr("steinreplay.watch"));
            watch.enabled = mc.theWorld == null;
            buttonList.add(watch);
            buttonList.add(new GuiButton(DELETE + i, x + 74, top + i * ROW_H + 3, 60, 20,
                    confirm == index ? "§c" + Lang.tr("steinreplay.confirm") : Lang.tr("steinreplay.delete")));
        }
        int bottom = height - 28;
        GuiButton prev = new GuiButton(PREV, width / 2 - 210, bottom, 60, 20, "<");
        prev.enabled = page > 0;
        GuiButton next = new GuiButton(NEXT, width / 2 - 146, bottom, 60, 20, ">");
        next.enabled = page < pages() - 1;
        buttonList.add(prev);
        buttonList.add(next);
        buttonList.add(new GuiButton(FOLDER, width / 2 - 80, bottom, 120, 20, Lang.tr("steinreplay.folder")));
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
            mod.replaysDir().mkdirs();
            try {
                java.awt.Desktop.getDesktop().open(mod.replaysDir());
            } catch (Exception e) {
                message = e.toString();
            }
        } else if (b.id >= DELETE) {
            int index = page * ROWS + b.id - DELETE;
            if (confirm == index && System.currentTimeMillis() < confirmUntil) {
                File f = list.get(index).file;
                message = f.delete() ? "" : Lang.tr("steinreplay.deleteFailed");
                confirm = -1;
            } else {
                confirm = index;
                confirmUntil = System.currentTimeMillis() + 3000;
            }
            initGui();
        } else if (b.id >= WATCH) {
            Recording r = list.get(page * ROWS + b.id - WATCH);
            message = mod.watch(r.file, this);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, Lang.tr("steinreplay.saved"), width / 2, 20, 0xFFFFFF);
        if (confirm >= 0 && System.currentTimeMillis() >= confirmUntil) {
            confirm = -1;
            initGui();
        }
        int top = 50;
        int x = width / 2 - 210;
        if (list.isEmpty()) {
            drawCenteredString(fontRendererObj, "§7" + Lang.tr("steinreplay.none"), width / 2, top + 20, 0xFFFFFF);
        }
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= list.size()) {
                break;
            }
            Recording r = list.get(index);
            int y = top + i * ROW_H;
            drawRect(x - 4, y, width / 2 + 198, y + ROW_H - 4, 0x40000000);
            String when = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date(r.when));
            drawString(fontRendererObj, when + "  §7" + (r.server.isEmpty() ? "" : r.server), x, y + 4, 0xFFFFFF);
            String detail = (r.killer.isEmpty() ? Lang.tr("steinreplay.noKiller")
                    : Lang.tr("steinreplay.killedBy", r.killer)) + "  §7" + r.length / 20 + " s";
            drawString(fontRendererObj, detail, x, y + 15, 0xFFFFFF);
        }
        if (mc.theWorld != null) {
            drawCenteredString(fontRendererObj, "§e" + Lang.tr("steinreplay.leaveToWatch"), width / 2, height - 42,
                    0xFFFFFF);
        } else if (!message.isEmpty()) {
            drawCenteredString(fontRendererObj, "§c" + message, width / 2, height - 42, 0xFFFFFF);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
