/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * O mundo de um replay salvo, so no cliente: sem servidor e sem conexao (a conexao e de mentira e nunca abre, o que o
 * jogo manda fica parado na fila dela). Tem os blocos guardados em volta da morte, a hora do dia de entao e as skins
 * de cada jogador (o perfil assinado que o servidor mandou vai para a lista de jogadores, como faria o servidor). O
 * jogador fica no modo espectador; quem mostra a cena e a camera do {@link Session}.
 */
final class ReplayWorld {

    private ReplayWorld() {
    }

    /** Abre o mundo do replay (so fora de uma partida). @return false se ja ha um mundo aberto */
    static boolean open(Minecraft mc, Recording r, GuiScreen back) throws java.io.IOException {
        if (mc.theWorld != null) {
            return false;
        }
        NetworkManager net = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
        NetHandlerPlayClient handler = new NetHandlerPlayClient(mc, back, net, mc.getSession().getProfile());
        mc.playerController = new PlayerControllerMP(mc, handler);
        WorldClient world = new WorldClient(handler, new WorldSettings(0L, WorldSettings.GameType.SPECTATOR, false, false,
                WorldType.DEFAULT), r.dimension, EnumDifficulty.PEACEFUL, mc.mcProfiler);
        players(handler, r, mc.getSession().getProfile());
        mc.loadWorld(world);
        mc.playerController.setGameType(WorldSettings.GameType.SPECTATOR);
        world.setWorldTime(r.worldTime);
        for (Recording.ChunkData d : r.chunks) {
            chunk(world, d);
        }
        Recorder.Snap me = r.size() > 0 ? r.frame(0).self() : null;
        if (me != null) {
            mc.thePlayer.setPositionAndRotation(me.x, me.y, me.z, me.yaw, me.pitch);
        }
        return true;
    }

    /** Fecha o mundo e volta para a tela dada. */
    static void close(Minecraft mc, GuiScreen to) {
        if (mc.theWorld != null) {
            mc.loadWorld(null);
        }
        mc.displayGuiScreen(to);
    }

    /** Os jogadores na lista (para as skins), pelo mesmo pacote que o servidor mandaria. */
    private static void players(NetHandlerPlayClient handler, Recording r, GameProfile self)
            throws java.io.IOException {
        Map<GameProfile, Boolean> seen = new IdentityHashMap<>();
        List<GameProfile> list = new ArrayList<>();
        for (Recorder.Frame f : r.frames) {
            for (int i = 0; i < f.count; i++) {
                GameProfile p = f.snaps[i].profile;
                if (p != null && p.getId() != null && seen.put(p, Boolean.TRUE) == null) {
                    list.add(p);
                }
            }
        }
        // Voce, como espectador: e pela lista que o jogo desenha como espectador (vendo atraves dos blocos).
        if (self != null && self.getId() != null) {
            list.add(self);
        }
        if (list.isEmpty()) {
            return;
        }
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeEnumValue(S38PacketPlayerListItem.Action.ADD_PLAYER);
        buf.writeVarIntToBuffer(list.size());
        for (GameProfile p : list) {
            buf.writeUuid(p.getId());
            buf.writeString(p.getName() == null ? "" : p.getName());
            List<Property> props = new ArrayList<>(p.getProperties().values());
            buf.writeVarIntToBuffer(props.size());
            for (Property pr : props) {
                buf.writeString(pr.getName());
                buf.writeString(pr.getValue());
                buf.writeBoolean(pr.hasSignature());
                if (pr.hasSignature()) {
                    buf.writeString(pr.getSignature());
                }
            }
            buf.writeVarIntToBuffer(p == self ? WorldSettings.GameType.SPECTATOR.getID()
                    : WorldSettings.GameType.SURVIVAL.getID());
            buf.writeVarIntToBuffer(0);
            buf.writeBoolean(false);
        }
        S38PacketPlayerListItem packet = new S38PacketPlayerListItem();
        packet.readPacketData(buf);
        handler.handlePlayerListItem(packet);
    }

    /** Um chunk guardado no mundo do replay: as secoes com os blocos e a luz de entao, os biomas e a altura. */
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
        world.markBlockRangeForRenderUpdate(d.x << 4, 0, d.z << 4, (d.x << 4) + 15, 255, (d.z << 4) + 15);
    }
}
