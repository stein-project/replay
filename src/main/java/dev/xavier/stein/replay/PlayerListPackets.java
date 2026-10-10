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

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.world.WorldSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Changes to the client's own player list, through the same packet the server would send and only
 * on this client: nothing goes to the server. The game finds skins and the spectator mode in this
 * list.
 */
final class PlayerListPackets {

    private PlayerListPackets() {}

    /**
     * Adds profiles to the list (with their signed skins), in survival, except {@code spectator}
     * (may be null), which goes in as a spectator.
     */
    static void add(NetHandlerPlayClient handler, List<GameProfile> list, GameProfile spectator) {
        if (handler == null || list.isEmpty()) {
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
            buf.writeVarIntToBuffer(
                    p == spectator
                            ? WorldSettings.GameType.SPECTATOR.getID()
                            : WorldSettings.GameType.SURVIVAL.getID());
            buf.writeVarIntToBuffer(0);
            buf.writeBoolean(false);
        }
        apply(handler, buf);
    }

    /** Removes a profile from the list. */
    static void remove(NetHandlerPlayClient handler, UUID id) {
        if (handler == null) {
            return;
        }
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeEnumValue(S38PacketPlayerListItem.Action.REMOVE_PLAYER);
        buf.writeVarIntToBuffer(1);
        buf.writeUuid(id);
        apply(handler, buf);
    }

    /** Sets the game mode of a profile in the list. */
    static void gameMode(NetHandlerPlayClient handler, UUID id, WorldSettings.GameType mode) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeEnumValue(S38PacketPlayerListItem.Action.UPDATE_GAME_MODE);
        buf.writeVarIntToBuffer(1);
        buf.writeUuid(id);
        buf.writeVarIntToBuffer(mode.getID());
        apply(handler, buf);
    }

    private static void apply(NetHandlerPlayClient handler, PacketBuffer buf) {
        S38PacketPlayerListItem packet = new S38PacketPlayerListItem();
        try {
            packet.readPacketData(buf);
        } catch (IOException e) {
            return; // built right here: it does not fail
        }
        handler.handlePlayerListItem(packet);
    }
}
