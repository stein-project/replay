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
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The world of a saved replay, on the client only: no server and no connection (the connection is a
 * fake that never opens, and what the game sends waits in its queue). It has the blocks kept around
 * the death, the time of day of then and every player's skin (the signed profile the server sent
 * goes into the player list, as the server would do). The player is a spectator; the scene is shown
 * by the {@link Session}'s camera.
 */
final class ReplayWorld {

    private ReplayWorld() {}

    /**
     * Opens the replay's world (only outside a match).
     *
     * @return false if a world is already open
     */
    static boolean open(Minecraft mc, Recording r, GuiScreen back) {
        if (mc.theWorld != null) {
            return false;
        }
        NetworkManager net = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
        NetHandlerPlayClient handler =
                new NetHandlerPlayClient(mc, back, net, mc.getSession().getProfile());
        mc.playerController = new PlayerControllerMP(mc, handler);
        WorldClient world =
                new WorldClient(
                        handler,
                        new WorldSettings(
                                0L,
                                WorldSettings.GameType.SPECTATOR,
                                false,
                                false,
                                WorldType.DEFAULT),
                        r.dimension,
                        EnumDifficulty.PEACEFUL,
                        mc.mcProfiler);
        players(handler, r, mc.getSession().getProfile());
        mc.loadWorld(world);
        mc.playerController.setGameType(WorldSettings.GameType.SPECTATOR);
        world.setWorldTime(r.worldTime);
        for (Recording.ChunkData d : r.chunks) {
            chunk(world, d);
        }
        Snap me = r.size() > 0 ? r.frame(0).self() : null;
        if (me != null) {
            mc.thePlayer.setPositionAndRotation(me.x, me.y, me.z, me.yaw, me.pitch);
        }
        return true;
    }

    /** Closes the world and goes back to the given screen. */
    static void close(Minecraft mc, GuiScreen to) {
        if (mc.theWorld != null) {
            mc.loadWorld(null);
        }
        mc.displayGuiScreen(to);
    }

    /** The players in the list (for the skins), and you as a spectator. */
    private static void players(NetHandlerPlayClient handler, Recording r, GameProfile self) {
        Map<GameProfile, Boolean> seen = new IdentityHashMap<>();
        List<GameProfile> list = new ArrayList<>();
        for (Frame f : r.frames) {
            for (int i = 0; i < f.count; i++) {
                GameProfile p = f.snaps[i].profile;
                if (p != null && p.getId() != null && seen.put(p, Boolean.TRUE) == null) {
                    list.add(p);
                }
            }
        }
        // You, as a spectator: the game draws as a spectator (seeing through blocks) by the list.
        if (self != null && self.getId() != null) {
            list.add(self);
        }
        PlayerListPackets.add(handler, list, self);
    }

    /** A kept chunk in the replay's world: the sections with the blocks and light of then. */
    private static void chunk(WorldClient world, Recording.ChunkData d) {
        world.doPreChunk(d.x, d.z, true);
        Chunk c = world.getChunkFromChunkCoords(d.x, d.z);
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        boolean sky = !world.provider.getHasNoSky();
        for (int i = 0; i < d.data.size(); i++) {
            int y = d.ys.get(i)[0];
            ExtendedBlockStorage s = new ExtendedBlockStorage(y, sky);
            s.setData(d.data.get(i));
            s.setBlocklightArray(new NibbleArray(d.blockLight.get(i)));
            if (d.skyLight.get(i) != null) {
                s.setSkylightArray(new NibbleArray(d.skyLight.get(i)));
            }
            s.removeInvalidBlocks();
            sections[(y >> 4) & 15] = s;
        }
        c.setStorageArrays(sections);
        c.setBiomeArray(d.biomes);
        c.setTerrainPopulated(true);
        c.setLightPopulated(true);
        c.generateSkylightMap();
        world.markBlockRangeForRenderUpdate(
                d.x << 4, 0, d.z << 4, (d.x << 4) + 15, 255, (d.z << 4) + 15);
    }
}
