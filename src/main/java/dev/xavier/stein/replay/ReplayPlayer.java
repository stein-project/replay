/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import com.mojang.authlib.GameProfile;

import dev.xavier.stein.loader.api.Render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * A replay player, with the skin by nick as in the Tab list ({@link Render#playerSkin}): the one
 * the server sent and, on a server without authentication, the original account's skin for that
 * nick. The game alone would only find the skin by UUID in the player list — and in the replay the
 * recorded "you" has another UUID, and on an offline server nobody has a skin.
 */
final class ReplayPlayer extends EntityOtherPlayerMP {

    private static final String PREFIX = "stein_replay_";
    private static boolean registered;

    private final String nick;

    /** The death time (ticks, with fraction) before and now: the fall is smooth in slow motion. */
    float deathPrev;

    float deathNow;

    ReplayPlayer(World world, GameProfile profile, String nick) {
        super(world, profile);
        this.nick = nick;
    }

    @Override
    public ResourceLocation getLocationSkin() {
        return skin(nick, super.getLocationSkin());
    }

    /** The replay's body renderer ({@link Body}), once registered; otherwise the game's. */
    @Override
    public String getSkinType() {
        String t = super.getSkinType();
        return registered ? PREFIX + t : t;
    }

    /**
     * Puts the replay's body renderers in the game, one for each arm model (wide and slim), under
     * the skin type that {@link #getSkinType} returns. Once.
     */
    static void register(Minecraft mc) {
        if (registered) {
            return;
        }
        RenderManager manager = mc.getRenderManager();
        manager.skinMap.put(PREFIX + "default", new Body(manager, false));
        manager.skinMap.put(PREFIX + "slim", new Body(manager, true));
        registered = true;
    }

    /**
     * The body of a replay player. The game tips a dead body over by a whole tick counter plus the
     * frame's fraction; with the replay in slow motion the counter moves half a tick per tick and
     * the fall came out in jerks. Here the fall comes from the replay's continuous death time,
     * handed to the game's own corpse rotation as a whole part and a fraction. The rest is the
     * game's drawing.
     */
    static final class Body extends RenderPlayer {

        Body(RenderManager manager, boolean slim) {
            super(manager, slim);
        }

        @Override
        protected void rotateCorpse(
                AbstractClientPlayer p, float age, float yaw, float partialTicks) {
            if (!(p instanceof ReplayPlayer r) || r.deathNow <= 0) {
                super.rotateCorpse(p, age, yaw, partialTicks);
                return;
            }
            // The game turns by sqrt((deathTime + partialTicks - 1) / 20 * 1.6) of the maximum:
            // the continuous death time d goes in as deathTime = floor(d) and partialTicks = the
            // rest. Before the first tick (d < 1) the angle is 0, which deathTime 1 gives too.
            float d = r.deathPrev + (r.deathNow - r.deathPrev) * partialTicks;
            int whole = Math.max(1, (int) Math.floor(d));
            float rest = d >= 1 ? d - whole : 0;
            int deathTime = p.deathTime;
            p.deathTime = whole;
            try {
                super.rotateCorpse(p, age, yaw, rest);
            } finally {
                p.deathTime = deathTime;
            }
        }
    }

    static ResourceLocation skin(String nick, ResourceLocation fallback) {
        Object r = nick == null ? null : Render.playerSkin(nick);
        return r instanceof ResourceLocation l ? l : fallback;
    }

    /**
     * The client player in the killer's place while their hand is drawn: the arm with their skin.
     */
    static final class Hand extends EntityPlayerSP {

        String nick;

        Hand(Minecraft mc, World world) {
            super(mc, world, mc.getNetHandler(), mc.thePlayer.getStatFileWriter());
        }

        /** The arm model (slim or wide) comes from the player list, by this UUID. */
        void setUuid(UUID id) {
            entityUniqueID = id;
        }

        @Override
        public ResourceLocation getLocationSkin() {
            return skin(nick, super.getLocationSkin());
        }
    }
}
