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
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityPotion;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The replay's copies in the client world: players, items, XP, arrows, mobs and thrown things are
 * copies put into the client world with negative ids that the server never uses, and the game (and
 * Stein GFX) draws them like any entity, interpolating between their state before and now on every
 * frame. The real ones are hidden while the replay plays ({@link SteinReplay#onHideEntity}).
 */
final class Actors {

    /** The first fake id; the next ones go down from it. */
    private static final int FIRST_FAKE_ID = -100000;

    /** Ticks of a swing (the game's, without haste or fatigue). */
    private static final int SWING_TICKS = 6;

    private final Minecraft mc;
    private final Source rec;

    /** The copies in the world now, by recorded id, and the (negative) id each one went in with. */
    private final Map<Integer, Entity> inWorld = new HashMap<>();

    private final Map<Integer, Integer> fakeIds = new HashMap<>();

    /** The same copies, by identity: asked for every entity on every frame. */
    private final Set<Entity> actors = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<Integer> seen = new HashSet<>();
    private int nextFakeId = FIRST_FAKE_ID;
    private final Snap stA = new Snap();
    private final Snap stB = new Snap();

    /** The recorded "you" with its own UUID, in the client's player list while it plays. */
    private GameProfile selfCopy;

    Actors(Minecraft mc, Source rec) {
        this.mc = mc;
        this.rec = rec;
    }

    /** An entity id that is a replay copy (the negative ones it put in the world). */
    static boolean isFake(Entity e) {
        return e.getEntityId() <= FIRST_FAKE_ID;
    }

    boolean contains(Object entity) {
        return entity instanceof Entity e && actors.contains(e);
    }

    /** The copy of a recorded id in the world now, or null. */
    Entity get(int id) {
        return inWorld.get(id);
    }

    /**
     * The copies at time a (before) and b (now): they enter the client world the first time, and
     * whoever leaves the scene leaves the world. Called on every tick and when jumping in time; the
     * state is written after the world's tick, so the recording wins over the client's physics.
     */
    void sync(float a, float b, int frames) {
        if (mc.theWorld == null) {
            return;
        }
        int ib = (int) Math.floor(b);
        float fb = b - ib;
        Frame b0 = rec.frame(clamp(ib, frames));
        Frame b1 = rec.frame(clamp(ib + 1, frames));
        int ia = (int) Math.floor(a);
        float fa = a - ia;
        Frame a0 = rec.frame(clamp(ia, frames));
        Frame a1 = rec.frame(clamp(ia + 1, frames));
        seen.clear();
        for (int j = 0; j < b0.count; j++) {
            Snap s = b0.snaps[j];
            if (s.kind == Snap.PLAYER && (s.invisible || s.profile == null)) {
                // Invisible or without a profile (in the killer's eyes, the game does not draw
                // the view entity).
                continue;
            }
            Snap s1 = b1.find(s.id);
            stB.mix(s, s1 != null ? s1 : s, fb);
            Snap p0 = a0.find(s.id);
            Snap p1 = a1.find(s.id);
            if (p0 != null) {
                stA.mix(p0, p1 != null ? p1 : p0, fa);
            } else {
                stA.copy(stB);
            }
            if (s.kind == Snap.PLAYER || s.kind == Snap.MOB) {
                swing(s.id, a, b, frames);
            }
            Entity e = ensure(s);
            if (e == null) {
                continue;
            }
            setState(e, stA, stB);
            seen.add(s.id);
        }
        Iterator<Map.Entry<Integer, Entity>> it = inWorld.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entity> en = it.next();
            if (!seen.contains(en.getKey())) {
                mc.theWorld.removeEntityFromWorld(fakeIds.get(en.getKey()));
                actors.remove(en.getValue());
                it.remove();
            }
        }
    }

    private static int clamp(int i, int frames) {
        return Math.max(0, Math.min(frames - 1, i));
    }

    /**
     * The arm at time a and b, from the recorded start of each swing, in the replay's continuous
     * time: every swing the game showed appears whole, at any speed (mixing the arm of two frames
     * lost the swings that restart in the middle of the previous one, fast clicks). A new swing
     * between a and b starts from zero.
     */
    private void swing(int id, float a, float b, int frames) {
        int kb = swingStart(id, b, frames);
        int ka = swingStart(id, a, frames);
        stB.swing = kb >= 0 ? (b - kb) / SWING_TICKS : 0;
        stA.swing = ka >= 0 && ka == kb ? (a - ka) / SWING_TICKS : 0;
        if (stB.swing >= 1) {
            stB.swing = 0;
        }
        if (stA.swing >= 1) {
            stA.swing = 0;
        }
    }

    /** The frame where the swing in the air at time t started, or -1. */
    private int swingStart(int id, float t, int frames) {
        int i = (int) Math.floor(t);
        for (int k = i; k >= 0 && k > i - SWING_TICKS; k--) {
            if (k >= frames) {
                continue;
            }
            Snap s = rec.frame(k).find(id);
            if (s != null && s.swingStart) {
                return k;
            }
        }
        return -1;
    }

    /** The copy of a snap (created and put in the world the first time). */
    private Entity ensure(Snap s) {
        Entity e = inWorld.get(s.id);
        if (e != null && e.worldObj == mc.theWorld) {
            return e;
        }
        e = create(s);
        if (e == null) {
            return null;
        }
        int id = fakeIds.computeIfAbsent(s.id, k -> nextFakeId--);
        e.setPosition(s.x, s.y, s.z);
        mc.theWorld.addEntityToWorld(id, e);
        Entity old = inWorld.put(s.id, e);
        if (old != null) {
            actors.remove(old);
        }
        actors.add(e);
        return e;
    }

    private Entity create(Snap s) {
        switch (s.kind) {
            case Snap.PLAYER:
                return new ReplayPlayer(
                        mc.theWorld, s.self ? selfCopy(s.profile) : s.profile, s.profile.getName());
            case Snap.ITEM:
                return s.item == null ? null : new EntityItem(mc.theWorld, s.x, s.y, s.z, s.item);
            case Snap.XP:
                return new EntityXPOrb(mc.theWorld, s.x, s.y, s.z, Math.max(1, s.xp));
            case Snap.ARROW:
                return new EntityArrow(mc.theWorld, s.x, s.y, s.z);
            case Snap.MOB:
            case Snap.THROWN:
                Entity e = s.etype > 0 ? EntityList.createEntityByID(s.etype, mc.theWorld) : null;
                if (e instanceof EntityPotion potion) {
                    potion.setPotionDamage(s.xp);
                }
                return e;
            case Snap.HOOK:
                // The line goes to the angler's hand: only with their copy already in the scene.
                Entity angler = inWorld.get(s.xp);
                return angler instanceof EntityPlayer p
                        ? new EntityFishHook(mc.theWorld, s.x, s.y, s.z, p)
                        : null;
            default:
                return null;
        }
    }

    /**
     * The recorded "you" with a UUID of its own (and the same skin): your UUID is a spectator while
     * the replay plays, and the body recorded with it would be drawn as a spectator (only the head,
     * see-through). The copy goes into the client's player list while the replay plays, for the
     * skin to load, and leaves at the end.
     */
    private GameProfile selfCopy(GameProfile real) {
        if (selfCopy != null) {
            return selfCopy;
        }
        String key = "stein-replay:" + (real.getId() != null ? real.getId() : real.getName());
        selfCopy =
                new GameProfile(
                        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                        real.getName());
        selfCopy.getProperties().putAll(real.getProperties());
        PlayerListPackets.add(mc.getNetHandler(), List.of(selfCopy), null);
        return selfCopy;
    }

    /**
     * A copy with the state before (a) and now (b): the game interpolates between the two on every
     * frame, as with any entity.
     */
    private static void setState(Entity e, Snap a, Snap b) {
        e.prevPosX = e.lastTickPosX = a.x;
        e.prevPosY = e.lastTickPosY = a.y;
        e.prevPosZ = e.lastTickPosZ = a.z;
        e.setPosition(b.x, b.y, b.z);
        e.prevRotationYaw = a.yaw;
        e.rotationYaw = b.yaw;
        e.prevRotationPitch = a.pitch;
        e.rotationPitch = b.pitch;
        if (e instanceof EntityItem item) {
            if (b.item != null) {
                item.setEntityItemStack(b.item);
            }
            return;
        }
        if (!(e instanceof EntityLivingBase l)) {
            return;
        }
        l.prevRotationYawHead = a.headYaw;
        l.rotationYawHead = b.headYaw;
        l.prevRenderYawOffset = a.bodyYaw;
        l.renderYawOffset = b.bodyYaw;
        l.limbSwing = b.limbSwing;
        l.prevLimbSwingAmount = a.limbAmount;
        l.limbSwingAmount = b.limbAmount;
        if (e instanceof EntityOtherPlayerMP p) {
            setPlayerState(p, a, b);
            return;
        }
        // A mob: the rest of the state it has.
        l.prevSwingProgress = a.swing;
        l.swingProgress = b.swing;
        l.maxHurtTime = 10;
        l.hurtTime = b.hurtTime;
        l.deathTime = b.deathTime;
        for (int i = 0; i < 5; i++) {
            l.setCurrentItemOrArmor(i, b.equipment[i]);
        }
    }

    private static void setPlayerState(EntityOtherPlayerMP p, Snap a, Snap b) {
        // The camera bob while walking, in the killer's eyes: the entity's tick has already moved
        // the previous value into the prev fields.
        double moved = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        boolean ground = Math.abs(b.y - a.y) < 0.05;
        p.distanceWalkedModified = p.prevDistanceWalkedModified + (float) moved * 0.6F;
        p.cameraYaw =
                p.prevCameraYaw
                        + ((ground ? (float) Math.min(0.1, moved) : 0) - p.prevCameraYaw) * 0.4F;
        p.cameraPitch =
                p.prevCameraPitch
                        + ((float) Math.atan(-(b.y - a.y) * 0.2) * 15 - p.prevCameraPitch) * 0.8F;
        p.prevSwingProgress = a.swing;
        p.swingProgress = b.swing;
        p.maxHurtTime = 10; // the camera shake in their eyes divides by this (zero would be NaN)
        p.hurtTime = b.hurtTime;
        p.deathTime = b.deathTime;
        if (p instanceof ReplayPlayer r) {
            r.deathPrev = a.deathF;
            r.deathNow = b.deathF;
        }
        p.setSneaking(b.sneaking);
        for (int i = 0; i < 5; i++) {
            p.setCurrentItemOrArmor(i, b.equipment[i]);
        }
        if (b.using && b.equipment[0] != null) {
            if (!p.isUsingItem()) {
                p.setItemInUse(b.equipment[0], 72000);
            }
        } else if (p.isUsingItem()) {
            p.clearItemInUse();
        }
    }

    /** Takes every copy out of the world, and the recorded "you" out of the player list. */
    void clear() {
        if (selfCopy != null) {
            PlayerListPackets.remove(mc.getNetHandler(), selfCopy.getId());
            selfCopy = null;
        }
        if (mc.theWorld != null) {
            for (Integer id : inWorld.keySet()) {
                mc.theWorld.removeEntityFromWorld(fakeIds.get(id));
            }
        }
        inWorld.clear();
        actors.clear();
    }
}
