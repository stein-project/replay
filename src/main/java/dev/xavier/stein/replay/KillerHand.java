/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldSettings;

/**
 * In the killer's eyes, their hand: the game draws the first-person hand from the client player, so
 * here, only between the end of the drawn world and the screen on top (where {@link #end} puts
 * everything back), the client player is a puppet with their item, swing, blocking and skin. The
 * rest of the game (the tick, the packets) never sees the puppet.
 */
final class KillerHand {

    private final Minecraft mc;

    /** The killer's arm lags a little when turning, like the game's (half the way per tick). */
    private float armYaw;

    private float armPitch;
    private float prevArmYaw;
    private float prevArmPitch;
    private boolean armSet;

    /** The client player in the killer's place, only while the hand is drawn. */
    private ReplayPlayer.Hand puppet;

    private EntityPlayerSP realPlayer;
    private boolean swapped;
    private ItemStack savedItem;
    private float savedEquip;
    private float savedPrevEquip;
    private WorldSettings.GameType savedMode;

    KillerHand(Minecraft mc) {
        this.mc = mc;
    }

    /** One tick of the arm lag, following the killer's copy (null: not in their eyes). */
    void tick(Entity k) {
        if (k == null) {
            armSet = false;
            return;
        }
        if (!armSet) {
            armYaw = k.rotationYaw;
            armPitch = k.rotationPitch;
            armSet = true;
        }
        prevArmYaw = armYaw;
        prevArmPitch = armPitch;
        armYaw += (k.rotationYaw - armYaw) * 0.5F;
        armPitch += (k.rotationPitch - armPitch) * 0.5F;
    }

    /** The puppet in place of the client player, as the killer's copy {@code e} is now. */
    void begin(Entity e) {
        if (swapped || mc.thePlayer == null || !(e instanceof EntityOtherPlayerMP k)) {
            return;
        }
        if (puppet == null || puppet.worldObj != mc.theWorld) {
            puppet = new ReplayPlayer.Hand(mc, mc.theWorld);
            puppet.setUuid(k.getUniqueID());
        }
        puppet.nick = k.getName();
        puppet.prevPosX = puppet.lastTickPosX = k.prevPosX;
        puppet.prevPosY = puppet.lastTickPosY = k.prevPosY;
        puppet.prevPosZ = puppet.lastTickPosZ = k.prevPosZ;
        puppet.setPosition(k.posX, k.posY, k.posZ);
        puppet.prevRotationYaw = k.prevRotationYaw;
        puppet.rotationYaw = k.rotationYaw;
        puppet.prevRotationPitch = k.prevRotationPitch;
        puppet.rotationPitch = k.rotationPitch;
        puppet.prevRenderArmYaw = prevArmYaw;
        puppet.renderArmYaw = armYaw;
        puppet.prevRenderArmPitch = prevArmPitch;
        puppet.renderArmPitch = armPitch;
        puppet.prevSwingProgress = k.prevSwingProgress;
        puppet.swingProgress = k.swingProgress;
        ItemStack held = k.getHeldItem();
        puppet.inventory.currentItem = 0;
        puppet.inventory.mainInventory[0] = held;
        if (k.isUsingItem() && held != null) {
            if (!puppet.isUsingItem()) {
                puppet.setItemInUse(held, 72000);
            }
        } else if (puppet.isUsingItem()) {
            puppet.clearItemInUse();
        }
        ItemRenderer ir = mc.getItemRenderer();
        savedItem = ir.itemToRender;
        savedEquip = ir.equippedProgress;
        savedPrevEquip = ir.prevEquippedProgress;
        ir.itemToRender = held;
        ir.equippedProgress = 1;
        ir.prevEquippedProgress = 1;
        savedMode = mc.playerController.currentGameType;
        // The spectator has no hand.
        mc.playerController.currentGameType = WorldSettings.GameType.SURVIVAL;
        realPlayer = mc.thePlayer;
        mc.thePlayer = puppet;
        mc.gameSettings.hideGUI = false;
        swapped = true;
    }

    /** The client player back (called by the screen right after the hand, and on the tick). */
    void end() {
        if (!swapped) {
            return;
        }
        swapped = false;
        mc.thePlayer = realPlayer;
        realPlayer = null;
        mc.gameSettings.hideGUI = true;
        ItemRenderer ir = mc.getItemRenderer();
        ir.itemToRender = savedItem;
        ir.equippedProgress = savedEquip;
        ir.prevEquippedProgress = savedPrevEquip;
        mc.playerController.currentGameType = savedMode;
    }

    /** Back for good (the replay closed). */
    void close() {
        end();
        puppet = null;
    }
}
