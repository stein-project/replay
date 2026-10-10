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
import dev.xavier.stein.loader.api.Typeface;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import java.util.Locale;

/**
 * The screen over the replay: the killer's card, the bar at the bottom (time) and the fade between
 * rounds. The world behind is the replay, played by the {@link Session}.
 *
 * <p>In the death replay it is the death screen: you only watch (the camera and the speed are the
 * replay's), it loops until the player respawns, and the top right corner has the respawn and
 * leave-server buttons. In a saved replay: space pauses, left and right go back and forward 5
 * seconds, up and down change the speed, clicking the bar goes to that point and Esc goes back to
 * the list.
 */
final class ReplayScreen extends GuiScreen {

    private static final int BAR_H = 34;

    /**
     * Ticks until the buttons work (as on the game's death screen: a stray click does not respawn).
     */
    private static final int BUTTONS_AFTER = 20;

    private static final int RESPAWN = 0;
    private static final int LEAVE = 1;

    /** A seek with the arrows, in ticks (5 seconds). */
    private static final int SEEK = 100;

    private final SteinReplay mod;
    private int ticks;
    private boolean respawning;

    ReplayScreen(SteinReplay mod) {
        this.mod = mod;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        Session s = mod.session();
        if (s == null || s.saved) {
            return;
        }
        int w = 120;
        buttonList.add(
                new GuiButton(RESPAWN, width - 10 - w, 10, w, 20, Lang.tr("steinreplay.respawn")));
        buttonList.add(
                new GuiButton(LEAVE, width - 10 - w, 34, w, 20, Lang.tr("steinreplay.leave")));
        enableButtons();
    }

    private void enableButtons() {
        for (GuiButton b : buttonList) {
            b.enabled = ticks >= BUTTONS_AFTER && !respawning;
        }
    }

    @Override
    public void updateScreen() {
        ticks++;
        enableButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == RESPAWN) {
            respawning = true; // the replay closes when the player is alive again
            enableButtons();
            mod.respawn();
        } else if (button.id == LEAVE) {
            mod.leaveServer();
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        Session s = mod.session();
        if (s == null || !s.saved) {
            return; // death replay: not even Esc (it is the death screen; the buttons leave it)
        }
        switch (key) {
            case Keyboard.KEY_ESCAPE -> mod.finish(); // back to the list
            case Keyboard.KEY_SPACE, Keyboard.KEY_P -> s.paused = !s.paused;
            case Keyboard.KEY_LEFT -> s.seek(-SEEK);
            case Keyboard.KEY_RIGHT -> s.seek(SEEK);
            case Keyboard.KEY_UP -> s.speed = Math.min(Session.SPEEDS.length - 1, s.speed + 1);
            case Keyboard.KEY_DOWN -> s.speed = Math.max(0, s.speed - 1);
            default -> {}
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        Session s = mod.session();
        if (s != null
                && s.saved
                && button == 0
                && mouseY >= barY() - 6
                && mouseY <= barY() + 8
                && mouseX >= barX() - 4
                && mouseX <= barX() + barW() + 4) {
            float f = (mouseX - barX()) / (float) Math.max(1, barW());
            s.seek(Math.max(0, Math.min(1, f)) * (s.frames - 1) - s.time);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Session s = mod.session();
        if (s == null) {
            return;
        }
        s.endHand(); // the killer's hand is already drawn: the client player comes back
        KillerHud.draw(s.killerNow(), width, height, height - BAR_H - 12);
        card(s);
        bar(s);
        float fade = s.fade(partialTicks);
        if (fade > 0) {
            Render.rect(0, 0, width, height, Render.alpha(0xFF000000, fade));
        }
        super.drawScreen(mouseX, mouseY, partialTicks); // the buttons, over the fade
    }

    /** The killer's card: the head, the name, the held item and the armor, as at the final blow. */
    private void card(Session s) {
        Snap k = s.killerCard;
        if (k == null || k.profile == null) {
            return;
        }
        String name = k.profile.getName();
        String sub = Lang.tr("steinreplay.killedYou");
        int x = 10;
        int y = 10;
        int head = 26;
        int items = 0;
        for (ItemStack it : k.equipment) {
            items += it != null ? 1 : 0;
        }
        int textW = Math.max(Render.textWidth(name), Render.textWidth(sub));
        int w = Math.max(head + 22 + textW, 16 + items * 20);
        int h = 16 + head + (items > 0 ? 22 : 0);
        Render.surface(x, y, w, h, 0.85F);
        Render.playerHead(name, x + 8, y + 8, head, true);
        Render.text("§f" + name, x + head + 14, y + 10, 0xFFFFFFFF, true);
        Render.text("§c" + sub, x + head + 14, y + 22, 0xFFFFFFFF, true);
        int ix = x + 8;
        int iy = y + head + 14;
        // The held item first, then helmet, chestplate, leggings and boots.
        int[] order = {0, 4, 3, 2, 1};
        for (int slot : order) {
            ItemStack it = k.equipment[slot];
            if (it != null) {
                Render.item(it, ix, iy, true);
                ix += 20;
            }
        }
    }

    /** The timeline's width, and where it is (the bottom center). */
    private int barW() {
        return Math.min(300, width - 60);
    }

    private int barX() {
        return (width - barW()) / 2;
    }

    private int barY() {
        return height - BAR_H + 22;
    }

    /**
     * The timeline, clean: a thin line at the bottom center with the death point in red, the title
     * and the time above it, in a translucent pill; in a saved replay, also the speed and, above,
     * the keys.
     */
    private void bar(Session s) {
        int w = barW();
        int x = barX();
        int y = barY();
        float last = Math.max(1, s.frames - 1);
        float f = Math.max(0, Math.min(1, s.time / last));
        float death = Math.max(0, Math.min(1, s.deathAt / last));
        Render.roundRect(x - 14, y - 17, w + 28, 26, 7, 0x58000000);
        String title = Lang.tr("steinreplay.title").toUpperCase(Locale.ROOT);
        Render.text(title, x, y - 12, 0xB0FFFFFF, Typeface.MONO, 6.5F);
        String time =
                clock(s.time)
                        + " / "
                        + clock(last)
                        + (s.saved ? "   " + Session.SPEEDS[s.speed] + "x" : "")
                        + (s.saved && s.paused
                                ? "   " + Lang.tr("steinreplay.paused").toUpperCase(Locale.ROOT)
                                : "");
        Render.text(
                time,
                x + w - Render.textWidth(time, Typeface.MONO, 6.5F),
                y - 12,
                0xB0FFFFFF,
                Typeface.MONO,
                6.5F);
        Render.roundRect(x, y, w, 2.5F, 1.25F, 0x40FFFFFF);
        Render.roundRect(x, y, Math.max(2.5F, w * f), 2.5F, 1.25F, 0xF0FFFFFF);
        Render.circle(x + w * death, y + 1.25F, 2.5F, 0xFFE04848);
        Render.circle(x + w * f, y + 1.25F, 3F, 0xFFFFFFFF);
        if (s.saved) {
            String keys = Lang.tr("steinreplay.keysSaved");
            Render.text(
                    keys,
                    (width - Render.textWidth(keys, Typeface.MONO, 6F)) / 2,
                    y - 30,
                    0x90FFFFFF,
                    Typeface.MONO,
                    6F);
        }
    }

    private static String clock(float ticks) {
        int sec = Math.round(ticks / 20);
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }
}
