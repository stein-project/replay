/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Render;

import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * In the killer's eyes, their screen: the crosshair, the hearts (blinking when they get hit) and
 * the armor, as the game's HUD draws them. The health is the one the server sent about them.
 */
final class KillerHud {

    private static final String ICONS = "minecraft:textures/gui/icons.png";
    private static final float PX = 1F / 256;

    private KillerHud() {}

    /**
     * @param k the killer now, or null (not in their eyes)
     * @param width the screen width
     * @param height the screen height
     * @param y the top of the first row of hearts
     */
    static void draw(Snap k, int width, int height, int y) {
        if (k == null) {
            return;
        }
        icon(width / 2 - 7, height / 2 - 7, 0, 0, 15);
        if (k.health < 0) {
            return;
        }
        int left = width / 2 - 91;
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
        int armor = armor(k.equipment);
        if (armor > 0) {
            int ay = y - (hearts + 9) / 10 * 10;
            for (int i = 0; i < 10; i++) {
                int u = i * 2 + 1 < armor ? 34 : i * 2 + 1 == armor ? 25 : 16;
                icon(left + i * 8, ay, u, 9, 9);
            }
        }
    }

    /** The armor points of an equipment array (slots 1 to 4). */
    private static int armor(ItemStack[] equipment) {
        int armor = 0;
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack it = equipment[slot];
            if (it != null && it.getItem() instanceof ItemArmor a) {
                armor += a.damageReduceAmount;
            }
        }
        return armor;
    }

    private static void icon(int x, int y, int u, int v, int size) {
        Render.image(
                ICONS,
                x,
                y,
                size,
                size,
                u * PX,
                v * PX,
                (u + size) * PX,
                (v + size) * PX,
                0xFFFFFFFF);
    }
}
