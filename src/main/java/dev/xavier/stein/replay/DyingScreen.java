/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Render;

import net.minecraft.client.gui.GuiScreen;

/**
 * The death in slow motion, before the replay: a transparent screen (the game, the HUD and the
 * camera go on as they were, nothing is hidden or moved) only so the game's death screen does not
 * open, which turns red at the moment of death (so it does not look like the game froze) and fades
 * to black at the end. The game runs slowly while it is open ({@link DeathSlowMotion}).
 */
final class DyingScreen extends GuiScreen {

    /** The death red, over the game. */
    private static final int RED = 0x70C00000;

    private final DeathSlowMotion dying;

    DyingScreen(DeathSlowMotion dying) {
        this.dying = dying;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char c, int key) {
        // Not even Esc: the death goes on until the replay.
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float red = dying.red(partialTicks);
        if (red > 0) {
            Render.rect(0, 0, width, height, Render.alpha(RED, red));
        }
        float f = dying.fade(partialTicks);
        if (f > 0) {
            Render.rect(0, 0, width, height, Render.alpha(0xFF000000, f));
        }
    }
}
