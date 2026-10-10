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

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The replay file ({@code steinloader/replays/*.steinreplay}, gzip): a header (when, where, who
 * killed you, length), then the profiles (name, UUID and the signed skin the server sent) and the
 * items (NBT) in tables that every frame points into — the same items repeat in every frame —, the
 * frames, and the blocks around the death.
 *
 * <p>Players already have these files: the layout below is the format, and a change needs a new
 * {@link #VERSION} that still reads the old ones.
 */
final class ReplayFile {

    private static final int MAGIC = 0x53545250; // "STRP"
    static final int VERSION = 6;

    /**
     * The old versions that still open: 2 without particles, 3 without health, 4 without the start
     * of each swing, 5 without sounds, world time and mobs.
     */
    private static final int OLDEST = 2;

    private static final int WITH_PARTICLES = 3;
    private static final int WITH_HEALTH = 4;
    private static final int WITH_SWING_START = 5;
    private static final int WITH_SOUNDS = 6;

    private ReplayFile() {}

    static void save(Recording r, File f) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Map<GameProfile, Integer> profiles = new IdentityHashMap<>();
        Map<ItemStack, Integer> items = new IdentityHashMap<>();
        List<GameProfile> profileList = new ArrayList<>();
        List<ItemStack> itemList = new ArrayList<>();
        for (Frame fr : r.frames) {
            for (int i = 0; i < fr.count; i++) {
                Snap s = fr.snaps[i];
                if (s.profile != null && !profiles.containsKey(s.profile)) {
                    profiles.put(s.profile, profileList.size());
                    profileList.add(s.profile);
                }
                for (ItemStack it : s.equipment) {
                    index(it, items, itemList);
                }
                index(s.item, items, itemList);
            }
        }
        try (DataOutputStream out =
                new DataOutputStream(
                        new BufferedOutputStream(
                                new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(r.when);
            out.writeUTF(r.server);
            out.writeUTF(r.killer);
            out.writeInt(r.dimension);
            out.writeLong(r.worldTime);
            out.writeInt(r.frames.size());
            out.writeInt(profileList.size());
            for (GameProfile p : profileList) {
                writeProfile(out, p);
            }
            out.writeInt(itemList.size());
            for (ItemStack it : itemList) {
                CompressedStreamTools.write(it.writeToNBT(new NBTTagCompound()), out);
            }
            out.writeInt(r.frames.size());
            for (Frame fr : r.frames) {
                out.writeShort(fr.count);
                for (int i = 0; i < fr.count; i++) {
                    writeSnap(out, fr.snaps[i], profiles, items);
                }
                writeEvents(out, fr.events);
                out.writeLong(fr.worldTime);
                out.writeFloat(fr.rain);
                out.writeFloat(fr.thunder);
            }
            out.writeInt(r.chunks.size());
            for (Recording.ChunkData d : r.chunks) {
                writeChunk(out, d);
            }
        }
        if (f.exists() && !f.delete()) {
            throw new IOException("could not delete " + f);
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("could not write " + f);
        }
        r.file = f;
    }

    private static void index(ItemStack it, Map<ItemStack, Integer> items, List<ItemStack> list) {
        if (it != null && !items.containsKey(it)) {
            items.put(it, list.size());
            list.add(it);
        }
    }

    private static void writeProfile(DataOutputStream out, GameProfile p) throws IOException {
        UUID id = p.getId();
        out.writeBoolean(id != null);
        if (id != null) {
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
        }
        out.writeUTF(p.getName() == null ? "" : p.getName());
        List<Property> props = new ArrayList<>(p.getProperties().values());
        out.writeInt(props.size());
        for (Property pr : props) {
            out.writeUTF(pr.getName());
            out.writeUTF(pr.getValue());
            out.writeBoolean(pr.hasSignature());
            if (pr.hasSignature()) {
                out.writeUTF(pr.getSignature());
            }
        }
    }

    private static void writeSnap(
            DataOutputStream out,
            Snap s,
            Map<GameProfile, Integer> profiles,
            Map<ItemStack, Integer> items)
            throws IOException {
        out.writeInt(s.id);
        out.writeByte(s.kind);
        out.writeInt(s.etype);
        out.writeInt(s.item != null ? items.get(s.item) : -1);
        out.writeInt(s.xp);
        out.writeInt(s.profile != null ? profiles.get(s.profile) : -1);
        out.writeDouble(s.x);
        out.writeDouble(s.y);
        out.writeDouble(s.z);
        out.writeFloat(s.yaw);
        out.writeFloat(s.pitch);
        out.writeFloat(s.headYaw);
        out.writeFloat(s.bodyYaw);
        out.writeFloat(s.limbSwing);
        out.writeFloat(s.limbAmount);
        out.writeFloat(s.swing);
        out.writeByte(s.hurtTime);
        out.writeShort(s.deathTime);
        out.writeByte(
                (s.self ? 1 : 0)
                        | (s.sneaking ? 2 : 0)
                        | (s.using ? 4 : 0)
                        | (s.invisible ? 8 : 0)
                        | (s.swingStart ? 16 : 0));
        for (ItemStack it : s.equipment) {
            out.writeInt(it != null ? items.get(it) : -1);
        }
        out.writeFloat(s.health);
    }

    private static void writeChunk(DataOutputStream out, Recording.ChunkData d) throws IOException {
        out.writeInt(d.x);
        out.writeInt(d.z);
        out.write(d.biomes, 0, 256);
        out.writeByte(d.data.size());
        for (int i = 0; i < d.data.size(); i++) {
            out.writeShort(d.ys.get(i)[0]);
            for (char c : d.data.get(i)) {
                out.writeChar(c);
            }
            out.write(d.blockLight.get(i));
            byte[] sky = d.skyLight.get(i);
            out.writeBoolean(sky != null);
            if (sky != null) {
                out.write(sky);
            }
        }
    }

    /** Only the header (for the list): when, where, who killed you and how many seconds. */
    static Recording header(File f) throws IOException {
        try (DataInputStream in = open(f)) {
            Recording r = new Recording();
            readHeader(in, r);
            r.file = f;
            return r;
        }
    }

    static Recording load(File f) throws IOException {
        try (DataInputStream in = open(f)) {
            Recording r = new Recording();
            readHeader(in, r);
            r.file = f;
            List<GameProfile> profiles = new ArrayList<>();
            int np = in.readInt();
            for (int i = 0; i < np; i++) {
                profiles.add(readProfile(in));
            }
            List<ItemStack> items = new ArrayList<>();
            int ni = in.readInt();
            for (int i = 0; i < ni; i++) {
                items.add(ItemStack.loadItemStackFromNBT(CompressedStreamTools.read(in)));
            }
            int nf = in.readInt();
            for (int i = 0; i < nf; i++) {
                int count = in.readShort();
                Frame fr = new Frame(count);
                fr.count = count;
                for (int j = 0; j < count; j++) {
                    readSnap(in, fr.snaps[j], r.version, profiles, items);
                }
                if (r.version >= WITH_PARTICLES) {
                    readEvents(in, fr.events, r.version >= WITH_SOUNDS);
                }
                if (r.version >= WITH_SOUNDS) {
                    fr.worldTime = in.readLong();
                    fr.rain = in.readFloat();
                    fr.thunder = in.readFloat();
                }
                r.frames.add(fr);
            }
            if (r.version < WITH_SWING_START) {
                guessSwingStarts(r);
            }
            int nc = in.readInt();
            for (int i = 0; i < nc; i++) {
                r.chunks.add(readChunk(in));
            }
            return r;
        }
    }

    private static GameProfile readProfile(DataInputStream in) throws IOException {
        UUID id = in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
        GameProfile p = new GameProfile(id, in.readUTF());
        int props = in.readInt();
        for (int k = 0; k < props; k++) {
            String name = in.readUTF();
            String value = in.readUTF();
            String sig = in.readBoolean() ? in.readUTF() : null;
            p.getProperties().put(name, new Property(name, value, sig));
        }
        return p;
    }

    private static void readSnap(
            DataInputStream in,
            Snap s,
            int version,
            List<GameProfile> profiles,
            List<ItemStack> items)
            throws IOException {
        s.id = in.readInt();
        s.kind = in.readByte();
        s.etype = version >= WITH_SOUNDS ? in.readInt() : 0;
        int item = in.readInt();
        s.xp = in.readInt();
        s.item = item >= 0 ? items.get(item) : null;
        int p = in.readInt();
        s.profile = p >= 0 ? profiles.get(p) : null;
        s.x = in.readDouble();
        s.y = in.readDouble();
        s.z = in.readDouble();
        s.yaw = in.readFloat();
        s.pitch = in.readFloat();
        s.headYaw = in.readFloat();
        s.bodyYaw = in.readFloat();
        s.limbSwing = in.readFloat();
        s.limbAmount = in.readFloat();
        s.swing = in.readFloat();
        s.hurtTime = in.readByte();
        s.deathTime = in.readShort();
        int flags = in.readByte();
        s.self = (flags & 1) != 0;
        s.sneaking = (flags & 2) != 0;
        s.using = (flags & 4) != 0;
        s.invisible = (flags & 8) != 0;
        s.swingStart = (flags & 16) != 0;
        for (int k = 0; k < 5; k++) {
            int it = in.readInt();
            s.equipment[k] = it >= 0 ? items.get(it) : null;
        }
        s.health = version >= WITH_HEALTH ? in.readFloat() : -1;
    }

    private static Recording.ChunkData readChunk(DataInputStream in) throws IOException {
        Recording.ChunkData d = new Recording.ChunkData();
        d.x = in.readInt();
        d.z = in.readInt();
        d.biomes = new byte[256];
        in.readFully(d.biomes);
        int sections = in.readByte();
        for (int k = 0; k < sections; k++) {
            d.ys.add(new int[] {in.readShort()});
            char[] data = new char[4096];
            for (int c = 0; c < data.length; c++) {
                data[c] = in.readChar();
            }
            d.data.add(data);
            byte[] bl = new byte[2048];
            in.readFully(bl);
            d.blockLight.add(bl);
            byte[] sky = null;
            if (in.readBoolean()) {
                sky = new byte[2048];
                in.readFully(sky);
            }
            d.skyLight.add(sky);
        }
        return d;
    }

    /**
     * A replay from before the start of each swing went to the file: the swing starts in the frame
     * where the arm is at the start and, in the next one, at the first step (1/6 of the swing).
     */
    private static void guessSwingStarts(Recording r) {
        for (int i = 0; i + 1 < r.frames.size(); i++) {
            Frame f = r.frames.get(i);
            Frame next = r.frames.get(i + 1);
            for (int j = 0; j < f.count; j++) {
                Snap s = f.snaps[j];
                Snap n = s.kind == Snap.PLAYER ? next.find(s.id) : null;
                s.swingStart = n != null && s.swing == 0 && Math.abs(n.swing - 1F / 6) < 0.01F;
            }
        }
    }

    private static void writeEvents(DataOutputStream out, Events e) throws IOException {
        out.writeShort(e.count);
        for (int i = 0; i < e.count; i++) {
            out.writeShort(e.type[i]);
            out.writeByte((e.ignoreRange[i] ? 1 : 0) | e.argc[i] << 1);
            for (int k = 0; k < 3; k++) {
                out.writeDouble(e.pos[i * 3 + k]);
            }
            for (int k = 0; k < 3; k++) {
                out.writeFloat(e.vel[i * 3 + k]);
            }
            for (int k = 0; k < e.argc[i]; k++) {
                out.writeInt(e.args[i * 2 + k]);
            }
        }
        out.writeShort(e.auxCount);
        for (int i = 0; i < e.auxCount * 5; i++) {
            out.writeInt(e.aux[i]);
        }
        out.writeShort(e.soundCount);
        for (int i = 0; i < e.soundCount; i++) {
            out.writeUTF(e.soundName[i]);
            for (int k = 0; k < 5; k++) {
                out.writeFloat(e.sound[i * 5 + k]);
            }
        }
    }

    private static void readEvents(DataInputStream in, Events e, boolean sounds)
            throws IOException {
        int n = in.readShort();
        e.count = 0;
        for (int i = 0; i < n; i++) {
            int type = in.readShort();
            int flags = in.readByte();
            double x = in.readDouble();
            double y = in.readDouble();
            double z = in.readDouble();
            float dx = in.readFloat();
            float dy = in.readFloat();
            float dz = in.readFloat();
            int[] params = new int[flags >> 1 & 3];
            for (int k = 0; k < params.length; k++) {
                params[k] = in.readInt();
            }
            e.add(type, (flags & 1) != 0, x, y, z, dx, dy, dz, params);
        }
        int a = in.readShort();
        e.auxCount = 0;
        for (int i = 0; i < a; i++) {
            e.addAux(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }
        e.soundCount = 0;
        int s = sounds ? in.readShort() : 0;
        for (int i = 0; i < s; i++) {
            String name = in.readUTF();
            e.addSound(
                    name,
                    in.readFloat(),
                    in.readFloat(),
                    in.readFloat(),
                    in.readFloat(),
                    in.readFloat());
        }
    }

    private static DataInputStream open(File f) throws IOException {
        return new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))));
    }

    private static void readHeader(DataInputStream in, Recording r) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a Stein replay");
        }
        int v = in.readInt();
        r.version = v;
        if (v < OLDEST || v > VERSION) {
            throw new IOException("replay of another version (" + v + ")");
        }
        r.when = in.readLong();
        r.server = in.readUTF();
        r.killer = in.readUTF();
        r.dimension = in.readInt();
        r.worldTime = in.readLong();
        r.length = in.readInt();
    }
}
