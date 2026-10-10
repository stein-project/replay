/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A kept replay: the frames (copied from the ring, so the ring keeps recording), the blocks around
 * the death and what the list shows (when, where, who killed you). It is what the death replay
 * plays and what goes to disk ({@link ReplayFile}), to watch again outside a match in a world built
 * only from those blocks ({@link ReplayWorld}).
 */
final class Recording implements Source {

    /** Radius of the kept blocks, in chunks around the death (7x7 chunks: 112 blocks across). */
    static final int CHUNK_RADIUS = 3;

    /** The blocks of a chunk: the non-empty sections (16x16x16), with light, and the biomes. */
    static final class ChunkData {
        int x;
        int z;
        byte[] biomes;

        /** {y} of each section, in the order of data and light. */
        final List<int[]> ys = new ArrayList<>();

        final List<char[]> data = new ArrayList<>();
        final List<byte[]> blockLight = new ArrayList<>();
        final List<byte[]> skyLight = new ArrayList<>();
    }

    final List<Frame> frames = new ArrayList<>();
    final List<ChunkData> chunks = new ArrayList<>();
    long when;
    String server = "";
    String killer = "";
    int dimension;
    long worldTime;

    /**
     * Frames (ticks) of the replay, from the header: the list shows the length without the rest.
     */
    int length;

    /** The file it came from (or went to), or null. */
    File file;

    /** The version of the file read. */
    int version = ReplayFile.VERSION;

    private final double[] dist = new double[Recorder.MAX_PLAYERS];

    @Override
    public int size() {
        return frames.size();
    }

    @Override
    public Frame frame(int i) {
        return frames.get(i);
    }

    /** Copies the current ring and the blocks around the last recorded position (game thread). */
    static Recording capture(Minecraft mc, Recorder rec, String server) {
        Recording r = new Recording();
        r.when = System.currentTimeMillis();
        r.server = server == null ? "" : server;
        r.dimension = mc.thePlayer.dimension;
        r.worldTime = mc.theWorld.getWorldTime();
        for (int i = 0; i < rec.size(); i++) {
            Frame src = rec.frame(i);
            Frame f = new Frame(src.count);
            f.count = src.count;
            for (int j = 0; j < src.count; j++) {
                f.snaps[j].copy(src.snaps[j]);
            }
            f.events.copy(src.events);
            f.copyWeather(src);
            r.frames.add(f);
        }
        r.length = r.frames.size();
        Snap me = r.size() > 0 ? r.frame(r.size() - 1).self() : null;
        int cx = (int) Math.floor((me != null ? me.x : mc.thePlayer.posX) / 16);
        int cz = (int) Math.floor((me != null ? me.z : mc.thePlayer.posZ) / 16);
        World w = mc.theWorld;
        for (int x = cx - CHUNK_RADIUS; x <= cx + CHUNK_RADIUS; x++) {
            for (int z = cz - CHUNK_RADIUS; z <= cz + CHUNK_RADIUS; z++) {
                Chunk c = w.getChunkFromChunkCoords(x, z);
                if (c != null && !c.isEmpty()) {
                    r.chunks.add(chunk(c, x, z));
                }
            }
        }
        return r;
    }

    private static ChunkData chunk(Chunk c, int x, int z) {
        ChunkData d = new ChunkData();
        d.x = x;
        d.z = z;
        d.biomes = c.getBiomeArray().clone();
        for (ExtendedBlockStorage s : c.getBlockStorageArray()) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            d.ys.add(new int[] {s.getYLocation()});
            d.data.add(s.getData().clone());
            d.blockLight.add(s.getBlocklightArray().getData().clone());
            d.skyLight.add(
                    s.getSkylightArray() != null ? s.getSkylightArray().getData().clone() : null);
        }
        return d;
    }

    /**
     * One more tick recorded after the death (game thread): the body falling and the items
     * scattering come into the replay while it already plays.
     */
    void extend(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Frame f = new Frame();
        Recorder.fill(f, mc, dist);
        frames.add(f);
        length = frames.size();
    }

    /** Drops frames from the start: the replay plays (and saves) only the chosen seconds. */
    void trimStart(int n) {
        frames.subList(0, Math.min(n, frames.size())).clear();
        length = frames.size();
    }

    /** Who killed you, for the list (the same one the replay finds). */
    void setKiller(String name) {
        killer = name == null ? "" : name;
    }
}
