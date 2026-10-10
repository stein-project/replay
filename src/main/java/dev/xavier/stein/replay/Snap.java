/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.item.ItemStack;

import java.util.Arrays;

/**
 * One recorded entity in one tick: a player, or an item on the ground, an XP orb, an arrow, a mob
 * or something thrown. Snaps are created once and reused: recording a tick copies a few numbers.
 */
final class Snap {

    /** What a snap is. The values go to the replay files: do not change them. */
    static final int PLAYER = 0;

    static final int ITEM = 1;
    static final int XP = 2;
    static final int ARROW = 3;
    static final int MOB = 4;
    static final int THROWN = 5;
    static final int HOOK = 6;

    int id;
    int kind;

    /** The game's entity type (EntityList) of a mob or a thrown entity, to create the copy. */
    int etype;

    /** The item on the ground, or null. */
    ItemStack item;

    /**
     * The XP value (the orb size); the thrown potion's damage; the angler's id of a fishing hook.
     */
    int xp;

    boolean self;
    GameProfile profile;
    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    float headYaw;
    float bodyYaw;
    float limbSwing;
    float limbAmount;
    float swing;

    /**
     * A swing started in this tick (the arm went back to the start): the replay redoes each swing.
     */
    boolean swingStart;

    int hurtTime;
    int deathTime;

    /** The death time with its fraction, between two frames (only while playing; not saved). */
    float deathF;

    boolean sneaking;
    boolean using;
    boolean invisible;

    /** The health the server sent (-1 = unknown, an old replay). */
    float health = -1;

    final ItemStack[] equipment = new ItemStack[5];

    void copy(EntityPlayer p, boolean isSelf) {
        id = p.getEntityId();
        kind = PLAYER;
        etype = 0;
        item = null;
        self = isSelf;
        profile = isSelf ? serverProfile(p) : p.getGameProfile();
        health = p.getHealth();
        x = p.posX;
        y = p.posY;
        z = p.posZ;
        yaw = p.rotationYaw;
        pitch = p.rotationPitch;
        headYaw = p.rotationYawHead;
        bodyYaw = p.renderYawOffset;
        limbSwing = p.limbSwing;
        limbAmount = p.limbSwingAmount;
        swing = p.swingProgress;
        swingStart = p.isSwingInProgress && p.swingProgressInt == 0;
        hurtTime = p.hurtTime;
        deathTime = p.deathTime;
        sneaking = p.isSneaking();
        using = p.isUsingItem();
        invisible = p.isInvisible();
        for (int i = 0; i < 5; i++) {
            equipment[i] = p.getEquipmentInSlot(i);
        }
    }

    /** An item on the ground, an XP orb, an arrow, a mob or something thrown. */
    void copy(Entity e, int what) {
        id = e.getEntityId();
        kind = what;
        etype = what == MOB || what == THROWN ? EntityList.getEntityID(e) : 0;
        self = false;
        profile = null;
        x = e.posX;
        y = e.posY;
        z = e.posZ;
        yaw = e.rotationYaw;
        pitch = e.rotationPitch;
        item = what == ITEM ? ((EntityItem) e).getEntityItem() : null;
        xp =
                what == XP
                        ? ((EntityXPOrb) e).getXpValue()
                        : e instanceof EntityPotion potion
                                ? potion.getPotionDamage()
                                : e instanceof EntityFishHook hook && hook.angler != null
                                        ? hook.angler.getEntityId()
                                        : 0;
        swing = 0;
        swingStart = false;
        hurtTime = 0;
        deathTime = 0;
        sneaking = false;
        using = false;
        invisible = e.isInvisible();
        health = -1;
        Arrays.fill(equipment, null);
        if (e instanceof EntityLivingBase l) {
            headYaw = l.rotationYawHead;
            bodyYaw = l.renderYawOffset;
            limbSwing = l.limbSwing;
            limbAmount = l.limbSwingAmount;
            swing = l.swingProgress;
            swingStart = l.isSwingInProgress && l.swingProgressInt == 0;
            hurtTime = l.hurtTime;
            deathTime = l.deathTime;
            health = l.getHealth();
            for (int i = 0; i < 5; i++) {
                equipment[i] = l.getEquipmentInSlot(i);
            }
        }
    }

    void copy(Snap s) {
        id = s.id;
        kind = s.kind;
        etype = s.etype;
        item = s.item;
        xp = s.xp;
        self = s.self;
        profile = s.profile;
        x = s.x;
        y = s.y;
        z = s.z;
        yaw = s.yaw;
        pitch = s.pitch;
        headYaw = s.headYaw;
        bodyYaw = s.bodyYaw;
        limbSwing = s.limbSwing;
        limbAmount = s.limbAmount;
        swing = s.swing;
        swingStart = s.swingStart;
        hurtTime = s.hurtTime;
        deathTime = s.deathTime;
        deathF = s.deathF;
        sneaking = s.sneaking;
        using = s.using;
        invisible = s.invisible;
        health = s.health;
        System.arraycopy(s.equipment, 0, equipment, 0, 5);
    }

    /**
     * The state between two snaps of the same entity, at fraction f: positions and angles
     * interpolated, the rest from the nearer one.
     */
    void mix(Snap a, Snap b, float f) {
        copy(f < 0.5F ? a : b);
        x = Interp.lerp(a.x, b.x, f);
        y = Interp.lerp(a.y, b.y, f);
        z = Interp.lerp(a.z, b.z, f);
        yaw = Interp.lerpAngle(a.yaw, b.yaw, f);
        pitch = Interp.lerp(a.pitch, b.pitch, f);
        headYaw = Interp.lerpAngle(a.headYaw, b.headYaw, f);
        bodyYaw = Interp.lerpAngle(a.bodyYaw, b.bodyYaw, f);
        limbSwing = Interp.lerp(a.limbSwing, b.limbSwing, f);
        limbAmount = Interp.lerp(a.limbAmount, b.limbAmount, f);
        deathF = Interp.lerp(a.deathTime, b.deathTime, f);
        swing =
                Math.min(
                        1,
                        b.swing < a.swing
                                ? a.swing + (1 - a.swing) * f
                                : Interp.lerp(a.swing, b.swing, f));
    }

    /**
     * Your profile as the server sent it in the player list (with the skin): the game's own and the
     * account's lack the textures the server gives each player.
     */
    private static GameProfile serverProfile(EntityPlayer p) {
        NetHandlerPlayClient h = Minecraft.getMinecraft().getNetHandler();
        NetworkPlayerInfo info = h != null ? h.getPlayerInfo(p.getUniqueID()) : null;
        return info != null && info.getGameProfile() != null
                ? info.getGameProfile()
                : p.getGameProfile();
    }
}
