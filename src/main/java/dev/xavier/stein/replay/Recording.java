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
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Um replay guardado: os quadros (copiados do anel, para o anel seguir gravando), os blocos em volta da morte e o que
 * aparece na lista (quando, onde, quem matou). E o que o replay da morte toca e o que vai para o disco
 * ({@code steinloader/replays/*.steinreplay}, gzip), para assistir de novo fora da partida num mundo montado so com
 * esses blocos ({@link ReplayWorld}).
 *
 * <p>No arquivo, os perfis (nome, UUID e a skin assinada que o servidor mandou) e os itens (NBT) vao numa tabela, e
 * cada jogador de cada quadro aponta para ela: os mesmos itens se repetem em todos os quadros.
 */
final class Recording implements Source {

    private static final int MAGIC = 0x53545250;   // "STRP"
    private static final int VERSION = 6;
    /**
     * As versoes antigas que ainda abrem: 2 sem as particulas, 3 sem a vida, 4 sem o inicio de cada golpe, 5 sem os
     * sons, a hora do mundo e os mobs.
     */
    private static final int OLDEST = 2;
    private static final int WITH_PARTICLES = 3;
    private static final int WITH_HEALTH = 4;
    private static final int WITH_SWING_START = 5;
    private static final int WITH_SOUNDS = 6;
    /** Raio dos blocos guardados, em chunks em volta da morte (7x7 chunks: 112 blocos de lado). */
    static final int CHUNK_RADIUS = 3;

    /** Os blocos de um chunk: as secoes nao vazias (16x16x16), com luz, e os biomas. */
    static final class ChunkData {
        int x;
        int z;
        byte[] biomes;
        final List<int[]> ys = new ArrayList<>();   // {y} de cada secao, na ordem de data/light
        final List<char[]> data = new ArrayList<>();
        final List<byte[]> blockLight = new ArrayList<>();
        final List<byte[]> skyLight = new ArrayList<>();
    }

    final List<Recorder.Frame> frames = new ArrayList<>();
    final List<ChunkData> chunks = new ArrayList<>();
    long when;
    String server = "";
    String killer = "";
    int dimension;
    long worldTime;
    /** Quadros (ticks) do replay, do cabecalho: a lista mostra a duracao sem ler o resto. */
    int length;
    /** O arquivo de onde veio (ou para onde foi), ou null. */
    File file;
    /** A versao do arquivo lido. */
    int version = VERSION;

    @Override
    public int size() {
        return frames.size();
    }

    @Override
    public Recorder.Frame frame(int i) {
        return frames.get(i);
    }

    /** Copia o anel de agora e os blocos em volta da ultima posicao gravada (thread do jogo). */
    static Recording capture(Minecraft mc, Recorder rec, String server) {
        Recording r = new Recording();
        r.when = System.currentTimeMillis();
        r.server = server == null ? "" : server;
        r.dimension = mc.thePlayer.dimension;
        r.worldTime = mc.theWorld.getWorldTime();
        for (int i = 0; i < rec.size(); i++) {
            Recorder.Frame src = rec.frame(i);
            Recorder.Frame f = new Recorder.Frame(src.count);
            f.count = src.count;
            for (int j = 0; j < src.count; j++) {
                f.snaps[j].copy(src.snaps[j]);
            }
            f.events.copy(src.events);
            f.copyWeather(src);
            r.frames.add(f);
        }
        r.length = r.frames.size();
        Recorder.Snap me = r.size() > 0 ? r.frame(r.size() - 1).self() : null;
        int cx = (int) Math.floor((me != null ? me.x : mc.thePlayer.posX) / 16);
        int cz = (int) Math.floor((me != null ? me.z : mc.thePlayer.posZ) / 16);
        World w = mc.theWorld;
        for (int x = cx - CHUNK_RADIUS; x <= cx + CHUNK_RADIUS; x++) {
            for (int z = cz - CHUNK_RADIUS; z <= cz + CHUNK_RADIUS; z++) {
                Chunk c = w.getChunkFromChunkCoords(x, z);
                if (c == null || c.isEmpty()) {
                    continue;
                }
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
                    d.skyLight.add(s.getSkylightArray() != null ? s.getSkylightArray().getData().clone() : null);
                }
                r.chunks.add(d);
            }
        }
        return r;
    }

    private final double[] dist = new double[Recorder.MAX_PLAYERS];

    /**
     * Mais um tick gravado depois da morte (thread do jogo): o corpo caindo e os itens se espalhando entram no replay
     * enquanto ele ja toca.
     */
    void extend(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Recorder.Frame f = new Recorder.Frame();
        Recorder.fill(f, mc, dist);
        frames.add(f);
        length = frames.size();
    }

    /** Tira os quadros do comeco: o replay toca (e salva) so os segundos escolhidos. */
    void trimStart(int n) {
        frames.subList(0, Math.min(n, frames.size())).clear();
        length = frames.size();
    }

    /** Quem te matou, para a lista (o mesmo que o replay acha). */
    void setKiller(String name) {
        killer = name == null ? "" : name;
    }

    // ---- disco ----

    void save(File f) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Map<GameProfile, Integer> profiles = new IdentityHashMap<>();
        Map<ItemStack, Integer> items = new IdentityHashMap<>();
        List<GameProfile> profileList = new ArrayList<>();
        List<ItemStack> itemList = new ArrayList<>();
        for (Recorder.Frame fr : frames) {
            for (int i = 0; i < fr.count; i++) {
                Recorder.Snap s = fr.snaps[i];
                if (s.profile != null && !profiles.containsKey(s.profile)) {
                    profiles.put(s.profile, profileList.size());
                    profileList.add(s.profile);
                }
                for (ItemStack it : s.equipment) {
                    if (it != null && !items.containsKey(it)) {
                        items.put(it, itemList.size());
                        itemList.add(it);
                    }
                }
                if (s.item != null && !items.containsKey(s.item)) {
                    items.put(s.item, itemList.size());
                    itemList.add(s.item);
                }
            }
        }
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(when);
            out.writeUTF(server);
            out.writeUTF(killer);
            out.writeInt(dimension);
            out.writeLong(worldTime);
            out.writeInt(frames.size());
            out.writeInt(profileList.size());
            for (GameProfile p : profileList) {
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
            out.writeInt(itemList.size());
            for (ItemStack it : itemList) {
                CompressedStreamTools.write(it.writeToNBT(new NBTTagCompound()), out);
            }
            out.writeInt(frames.size());
            for (Recorder.Frame fr : frames) {
                out.writeShort(fr.count);
                for (int i = 0; i < fr.count; i++) {
                    Recorder.Snap s = fr.snaps[i];
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
                    out.writeByte((s.self ? 1 : 0) | (s.sneaking ? 2 : 0) | (s.using ? 4 : 0) | (s.invisible ? 8 : 0)
                            | (s.swingStart ? 16 : 0));
                    for (ItemStack it : s.equipment) {
                        out.writeInt(it != null ? items.get(it) : -1);
                    }
                    out.writeFloat(s.health);
                }
                writeEvents(out, fr.events);
                out.writeLong(fr.worldTime);
                out.writeFloat(fr.rain);
                out.writeFloat(fr.thunder);
            }
            out.writeInt(chunks.size());
            for (ChunkData d : chunks) {
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
        }
        if (f.exists() && !f.delete()) {
            throw new IOException("nao apagou " + f);
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("nao gravou " + f);
        }
        file = f;
    }

    /** So o cabecalho (para a lista): quando, onde, quem matou e quantos segundos. */
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
                UUID id = in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
                GameProfile p = new GameProfile(id, in.readUTF());
                int props = in.readInt();
                for (int k = 0; k < props; k++) {
                    String name = in.readUTF();
                    String value = in.readUTF();
                    String sig = in.readBoolean() ? in.readUTF() : null;
                    p.getProperties().put(name, new Property(name, value, sig));
                }
                profiles.add(p);
            }
            List<ItemStack> items = new ArrayList<>();
            int ni = in.readInt();
            for (int i = 0; i < ni; i++) {
                items.add(ItemStack.loadItemStackFromNBT(CompressedStreamTools.read(in)));
            }
            int nf = in.readInt();
            for (int i = 0; i < nf; i++) {
                int count = in.readShort();
                Recorder.Frame fr = new Recorder.Frame(count);
                fr.count = count;
                for (int j = 0; j < count; j++) {
                    Recorder.Snap s = fr.snaps[j];
                    s.id = in.readInt();
                    s.kind = in.readByte();
                    s.etype = r.version >= WITH_SOUNDS ? in.readInt() : 0;
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
                    s.health = r.version >= WITH_HEALTH ? in.readFloat() : -1;
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
                ChunkData d = new ChunkData();
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
                r.chunks.add(d);
            }
            return r;
        }
    }

    /**
     * Replay de antes do inicio de cada golpe ir ao arquivo: o golpe comeca no quadro em que o braco esta no inicio e,
     * no seguinte, no primeiro passo (1/6 do golpe).
     */
    private static void guessSwingStarts(Recording r) {
        for (int i = 0; i + 1 < r.frames.size(); i++) {
            Recorder.Frame f = r.frames.get(i);
            Recorder.Frame next = r.frames.get(i + 1);
            for (int j = 0; j < f.count; j++) {
                Recorder.Snap s = f.snaps[j];
                Recorder.Snap n = s.kind == Recorder.PLAYER ? next.find(s.id) : null;
                s.swingStart = n != null && s.swing == 0 && Math.abs(n.swing - 1F / 6) < 0.01F;
            }
        }
    }

    private static void writeEvents(DataOutputStream out, Recorder.Events e) throws IOException {
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

    private static void readEvents(DataInputStream in, Recorder.Events e, boolean sounds) throws IOException {
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
            e.addSound(name, in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
        }
    }

    private static DataInputStream open(File f) throws IOException {
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))));
    }

    private static void readHeader(DataInputStream in, Recording r) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("nao e um replay do Stein");
        }
        int v = in.readInt();
        r.version = v;
        if (v < OLDEST || v > VERSION) {
            throw new IOException("replay de outra versao (" + v + ")");
        }
        r.when = in.readLong();
        r.server = in.readUTF();
        r.killer = in.readUTF();
        r.dimension = in.readInt();
        r.worldTime = in.readLong();
        r.length = in.readInt();
    }
}
