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
 * A morte em camera lenta, antes do replay: uma tela transparente (o jogo, o HUD e a camera seguem como estavam, nada e
 * escondido nem mexido) so para a tela de morte do jogo nao abrir, que fica vermelha na hora da morte (para nao
 * parecer que o jogo travou) e escurece no fim. O jogo anda devagar enquanto ela esta aberta ({@link SteinReplay}).
 */
final class DyingScreen extends GuiScreen {

    /** O vermelho da morte, por cima do jogo. */
    private static final int RED = 0x70C00000;

    private final SteinReplay mod;

    DyingScreen(SteinReplay mod) {
        this.mod = mod;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char c, int key) {
        // nem o Esc: a morte segue ate o replay
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float red = mod.dyingRed(partialTicks);
        if (red > 0) {
            Render.rect(0, 0, width, height, Render.alpha(RED, red));
        }
        float f = mod.dyingFade(partialTicks);
        if (f > 0) {
            Render.rect(0, 0, width, height, Render.alpha(0xFF000000, f));
        }
    }
}
