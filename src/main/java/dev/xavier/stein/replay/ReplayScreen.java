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
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * A tela por cima do replay: o card de quem te matou, a barra de baixo (tempo) e o escurecimento entre as voltas. O
 * mundo de tras e o replay, desenhado pela {@link Session}.
 *
 * <p>No replay da morte ela e a tela de morte: so se assiste (a camera e a velocidade sao as do replay), ele repete ate
 * o jogador renascer, e no canto de cima, a direita, ficam os botoes de renascer e de sair do servidor. No replay salvo:
 * espaco pausa, setas para os lados voltam e avancam 5 segundos, setas para cima e para baixo mudam a velocidade, clicar
 * na barra vai para aquele ponto e Esc volta para a lista.
 */
final class ReplayScreen extends GuiScreen {

    private static final int BAR_H = 34;
    /** Ticks ate os botoes valerem (como na tela de morte do jogo, para um clique perdido nao renascer na hora). */
    private static final int BUTTONS_AFTER = 20;

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
        buttonList.add(new GuiButton(0, width - 10 - w, 10, w, 20, Lang.tr("steinreplay.respawn")));
        buttonList.add(new GuiButton(1, width - 10 - w, 34, w, 20, Lang.tr("steinreplay.leave")));
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
        if (button.id == 0) {
            respawning = true;   // o replay fecha quando o jogador volta vivo
            enableButtons();
            mod.respawn();
        } else if (button.id == 1) {
            mod.leaveServer();
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        Session s = mod.session();
        if (s == null || !s.saved) {
            return;   // replay da morte: nem o Esc (e a tela de morte; sai pelos botoes)
        }
        switch (key) {
            case 1 -> mod.finish();              // Esc: volta para a lista
            case 57, 25 -> s.paused = !s.paused; // espaco, P
            case 203 -> s.seek(-100);            // esquerda
            case 205 -> s.seek(100);             // direita
            case 200 -> s.speed = Math.min(Session.SPEEDS.length - 1, s.speed + 1);
            case 208 -> s.speed = Math.max(0, s.speed - 1);
            default -> {
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        Session s = mod.session();
        if (s != null && s.saved && button == 0 && mouseY >= barY() - 6 && mouseY <= barY() + 8
                && mouseX >= barX() - 4 && mouseX <= barX() + barW() + 4) {
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
        s.endHand();   // a mao de quem te matou ja foi desenhada: o jogador do cliente volta
        killerHud(s);
        card(s);
        bar(s);
        float fade = s.fade(partialTicks);
        if (fade > 0) {
            Render.rect(0, 0, width, height, Render.alpha(0xFF000000, fade));
        }
        super.drawScreen(mouseX, mouseY, partialTicks);   // os botoes, por cima do escurecimento
    }

    private static final String ICONS = "minecraft:textures/gui/icons.png";
    private static final float PX = 1F / 256;

    /**
     * Nos olhos de quem te matou, a tela dele: a mira, os coracoes (piscando quando ele apanha) e a armadura, como o HUD
     * do jogo desenha. A vida e a que o servidor mandou dele.
     */
    private void killerHud(Session s) {
        Recorder.Snap k = s.killerNow();
        if (k == null) {
            return;
        }
        icon(width / 2 - 7, height / 2 - 7, 0, 0, 15);
        if (k.health < 0) {
            return;
        }
        int left = width / 2 - 91;
        int y = height - BAR_H - 12;
        int hp = (int) Math.ceil(k.health);
        int hearts = (int) Math.ceil(Math.max(20, hp) / 2.0);
        boolean blink = k.hurtTime > 0 && k.hurtTime / 3 % 2 == 1;
        for (int i = 0; i < hearts; i++) {
            int x = left + i % 10 * 8;
            int hy = y - i / 10 * 10;
            icon(x, hy, blink ? 25 : 16, 0, 9);
            if (i * 2 + 1 < hp) {
                icon(x, hy, 52, 0, 9);
            } else if (i * 2 + 1 == hp) {
                icon(x, hy, 61, 0, 9);
            }
        }
        int armor = 0;
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack it = k.equipment[slot];
            if (it != null && it.getItem() instanceof ItemArmor a) {
                armor += a.damageReduceAmount;
            }
        }
        if (armor > 0) {
            int ay = y - (hearts + 9) / 10 * 10;
            for (int i = 0; i < 10; i++) {
                int u = i * 2 + 1 < armor ? 34 : i * 2 + 1 == armor ? 25 : 16;
                icon(left + i * 8, ay, u, 9, 9);
            }
        }
    }

    private static void icon(int x, int y, int u, int v, int size) {
        Render.image(ICONS, x, y, size, size, u * PX, v * PX, (u + size) * PX, (v + size) * PX, 0xFFFFFFFF);
    }

    /** O card de quem te matou: a cabeca, o nome, o item na mao e a armadura, como estavam no golpe final. */
    private void card(Session s) {
        Recorder.Snap k = s.killerCard;
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
        // O item na mao primeiro, depois capacete, peitoral, calca e bota.
        int[] order = {0, 4, 3, 2, 1};
        for (int slot : order) {
            ItemStack it = k.equipment[slot];
            if (it != null) {
                Render.item(it, ix, iy, true);
                ix += 20;
            }
        }
    }

    /** A largura da linha do tempo, e onde ela fica (o centro de baixo). */
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
     * A linha do tempo, limpa: uma linha fina no centro de baixo com o ponto da morte em vermelho, o titulo e o tempo em
     * cima dela, numa pilula translucida; no salvo, tambem a velocidade e, acima, as teclas.
     */
    private void bar(Session s) {
        int w = barW();
        int x = barX();
        int y = barY();
        float last = Math.max(1, s.frames - 1);
        float f = Math.max(0, Math.min(1, s.time / last));
        float death = Math.max(0, Math.min(1, s.deathAt / last));
        Render.roundRect(x - 14, y - 17, w + 28, 26, 7, 0x58000000);
        String title = Lang.tr("steinreplay.title").toUpperCase(java.util.Locale.ROOT);
        Render.text(title, x, y - 12, 0xB0FFFFFF, Typeface.MONO, 6.5F);
        String time = clock(s.time) + " / " + clock(last)
                + (s.saved ? "   " + Session.SPEEDS[s.speed] + "x" : "")
                + (s.saved && s.paused ? "   " + Lang.tr("steinreplay.paused").toUpperCase(java.util.Locale.ROOT) : "");
        Render.text(time, x + w - Render.textWidth(time, Typeface.MONO, 6.5F), y - 12, 0xB0FFFFFF, Typeface.MONO, 6.5F);
        Render.roundRect(x, y, w, 2.5F, 1.25F, 0x40FFFFFF);
        Render.roundRect(x, y, Math.max(2.5F, w * f), 2.5F, 1.25F, 0xF0FFFFFF);
        Render.circle(x + w * death, y + 1.25F, 2.5F, 0xFFE04848);
        Render.circle(x + w * f, y + 1.25F, 3F, 0xFFFFFFFF);
        if (s.saved) {
            String keys = Lang.tr("steinreplay.keysSaved");
            Render.text(keys, (width - Render.textWidth(keys, Typeface.MONO, 6F)) / 2, y - 30, 0x90FFFFFF,
                    Typeface.MONO, 6F);
        }
    }

    private static String clock(float ticks) {
        int sec = Math.round(ticks / 20);
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }
}
