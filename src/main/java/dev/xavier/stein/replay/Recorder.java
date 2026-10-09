/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import com.mojang.authlib.GameProfile;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.item.ItemStack;

/**
 * Os ultimos segundos dos jogadores em volta, num anel de quadros (um por tick): posicao, rotacoes, braco, pernas,
 * dano, morte, agachar, item em uso e equipamento de cada jogador ate {@link #RADIUS} blocos, voce incluido, e as
 * particulas do tick ({@link ParticleTap}).
 *
 * <p>So le o que o cliente ja tem (o que o servidor mandou), nunca pergunta nada ao servidor. Os quadros e os registros
 * sao criados uma vez e reaproveitados: gravar um tick e copiar uns poucos numeros por jogador, sem alocar nada. Os
 * itens sao guardados por referencia — o jogo troca o objeto quando o servidor manda equipamento novo.
 */
final class Recorder implements Source {

    /** Ate onde gravar, em blocos. */
    static final double RADIUS = 48;
    /** Jogadores por quadro, no maximo (os mais perto ficam). */
    static final int MAX_PLAYERS = 48;
    /**
     * O resto por quadro, no maximo, e ate onde: itens no chao, XP, flechas, mobs e o que se arremessa (pocao, perola,
     * bola de neve, a linha da vara).
     */
    static final int MAX_OTHERS = 48;
    static final double OTHER_RADIUS = 32;
    static final int MAX_SNAPS = MAX_PLAYERS + MAX_OTHERS;
    /** O que cada registro e. */
    static final int PLAYER = 0;
    static final int ITEM = 1;
    static final int XP = 2;
    static final int ARROW = 3;
    static final int MOB = 4;
    static final int THROWN = 5;
    static final int HOOK = 6;

    /** Um jogador (ou um item no chao, um XP, uma flecha, um mob, um arremesso) num tick. */
    static final class Snap {
        int id;
        int kind;
        /** O tipo do jogo (EntityList) de um mob ou arremesso, para criar a copia. */
        int etype;
        /** O item no chao, ou null. */
        ItemStack item;
        /** O valor do XP (o tamanho da bolinha); a pocao arremessada; o id de quem segura a vara. */
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
        /** Um golpe comecou neste tick (o braco voltou ao inicio): o replay refaz cada golpe a partir daqui. */
        boolean swingStart;
        int hurtTime;
        int deathTime;
        /** O tempo de morte com fracao, entre dois quadros (so no replay tocando; nao vai ao disco). */
        float deathF;
        boolean sneaking;
        boolean using;
        boolean invisible;
        /** A vida (a que o servidor mandou; -1 = nao se sabe, replay antigo). */
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

        /** Um item no chao, um XP, uma flecha, um mob ou um arremesso. */
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
            xp = what == XP ? ((EntityXPOrb) e).getXpValue()
                    : e instanceof EntityPotion potion ? potion.getPotionDamage()
                    : e instanceof EntityFishHook hook && hook.angler != null ? hook.angler.getEntityId() : 0;
            swing = 0;
            swingStart = false;
            hurtTime = 0;
            deathTime = 0;
            sneaking = false;
            using = false;
            invisible = e.isInvisible();
            health = -1;
            java.util.Arrays.fill(equipment, null);
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
    }

    /** As particulas e os efeitos de bloco de um tick ({@link ParticleTap}), em vetores fixos. */
    static final class Events {
        int count;
        final int[] type = new int[ParticleTap.MAX_PARTICLES];
        final boolean[] ignoreRange = new boolean[ParticleTap.MAX_PARTICLES];
        /** x, y, z de cada uma. */
        final double[] pos = new double[ParticleTap.MAX_PARTICLES * 3];
        /** A velocidade (ou a cor, conforme o tipo) de cada uma. */
        final float[] vel = new float[ParticleTap.MAX_PARTICLES * 3];
        /** Ate dois parametros (o bloco ou o item das lascas). */
        final byte[] argc = new byte[ParticleTap.MAX_PARTICLES];
        final int[] args = new int[ParticleTap.MAX_PARTICLES * 2];
        int auxCount;
        /** tipo, x, y, z e dado de cada efeito de bloco. */
        final int[] aux = new int[ParticleTap.MAX_AUX * 5];
        /** Os sons do tick: o nome ("dominio:caminho") e x, y, z, volume e tom de cada um. */
        int soundCount;
        final String[] soundName = new String[ParticleTap.MAX_SOUNDS];
        final float[] sound = new float[ParticleTap.MAX_SOUNDS * 5];

        void addSound(String name, float x, float y, float z, float volume, float pitch) {
            if (soundCount >= soundName.length) {
                return;
            }
            int i = soundCount++;
            soundName[i] = name;
            sound[i * 5] = x;
            sound[i * 5 + 1] = y;
            sound[i * 5 + 2] = z;
            sound[i * 5 + 3] = volume;
            sound[i * 5 + 4] = pitch;
        }

        void add(int id, boolean ignore, double x, double y, double z, double dx, double dy, double dz, int[] params) {
            if (count >= type.length) {
                return;
            }
            int i = count++;
            type[i] = id;
            ignoreRange[i] = ignore;
            pos[i * 3] = x;
            pos[i * 3 + 1] = y;
            pos[i * 3 + 2] = z;
            vel[i * 3] = (float) dx;
            vel[i * 3 + 1] = (float) dy;
            vel[i * 3 + 2] = (float) dz;
            int n = params == null ? 0 : Math.min(2, params.length);
            argc[i] = (byte) n;
            for (int k = 0; k < n; k++) {
                args[i * 2 + k] = params[k];
            }
        }

        void addAux(int t, int x, int y, int z, int data) {
            if (auxCount * 5 >= aux.length) {
                return;
            }
            int i = auxCount++ * 5;
            aux[i] = t;
            aux[i + 1] = x;
            aux[i + 2] = y;
            aux[i + 3] = z;
            aux[i + 4] = data;
        }

        /** Os parametros da particula i, como o jogo pede. */
        int[] params(int i) {
            int n = argc[i];
            return n == 0 ? EMPTY : n == 1 ? new int[] {args[i * 2]} : new int[] {args[i * 2], args[i * 2 + 1]};
        }

        private static final int[] EMPTY = new int[0];

        void copy(Events e) {
            count = e.count;
            System.arraycopy(e.type, 0, type, 0, count);
            System.arraycopy(e.ignoreRange, 0, ignoreRange, 0, count);
            System.arraycopy(e.pos, 0, pos, 0, count * 3);
            System.arraycopy(e.vel, 0, vel, 0, count * 3);
            System.arraycopy(e.argc, 0, argc, 0, count);
            System.arraycopy(e.args, 0, args, 0, count * 2);
            auxCount = e.auxCount;
            System.arraycopy(e.aux, 0, aux, 0, auxCount * 5);
            soundCount = e.soundCount;
            System.arraycopy(e.soundName, 0, soundName, 0, soundCount);
            System.arraycopy(e.sound, 0, sound, 0, soundCount * 5);
        }
    }

    /**
     * O seu perfil como o servidor mandou na lista de jogadores (com a skin): o do proprio jogo e o da conta, sem as
     * texturas que o servidor da a cada um.
     */
    private static GameProfile serverProfile(EntityPlayer p) {
        net.minecraft.client.network.NetHandlerPlayClient h = Minecraft.getMinecraft().getNetHandler();
        net.minecraft.client.network.NetworkPlayerInfo info = h != null ? h.getPlayerInfo(p.getUniqueID()) : null;
        return info != null && info.getGameProfile() != null ? info.getGameProfile() : p.getGameProfile();
    }

    /** Um tick: os jogadores gravados nele. */
    static final class Frame {
        final Snap[] snaps;
        int count;
        /** As particulas do tick. */
        final Events events = new Events();
        /** A hora do mundo (-1 = nao gravada, replay antigo), a chuva e a tempestade (0 a 1). */
        long worldTime = -1;
        float rain;
        float thunder;

        /** A hora e o tempo de outro quadro. */
        void copyWeather(Frame f) {
            worldTime = f.worldTime;
            rain = f.rain;
            thunder = f.thunder;
        }

        Frame() {
            this(MAX_SNAPS);
        }

        Frame(int capacity) {
            snaps = new Snap[capacity];
            for (int i = 0; i < snaps.length; i++) {
                snaps[i] = new Snap();
            }
        }

        Snap find(int id) {
            for (int i = 0; i < count; i++) {
                if (snaps[i].id == id) {
                    return snaps[i];
                }
            }
            return null;
        }

        Snap self() {
            for (int i = 0; i < count; i++) {
                if (snaps[i].self) {
                    return snaps[i];
                }
            }
            return null;
        }
    }

    private final Frame[] ring;
    /** Onde vai o proximo quadro, e quantos ha. */
    private int next;
    private int size;
    private int dimension = Integer.MIN_VALUE;
    private final double[] dist = new double[MAX_PLAYERS];

    Recorder(int ticks) {
        ring = new Frame[ticks];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new Frame();
        }
    }

    void clear() {
        next = 0;
        size = 0;
        ParticleTap.clear();
    }

    @Override
    public int size() {
        return size;
    }

    /** O quadro i, do mais velho (0) ao mais novo (size - 1). */
    @Override
    public Frame frame(int i) {
        return ring[(next - size + i + ring.length * 2) % ring.length];
    }

    /** O quadro mais novo tem voce (o replay precisa de voce na cena). */
    boolean hasSelf() {
        return size > 0 && frame(size - 1).self() != null;
    }

    /** Grava o tick de agora. */
    void record(Minecraft mc) {
        EntityPlayer me = mc.thePlayer;
        if (me == null || mc.theWorld == null) {
            clear();
            return;
        }
        if (me.dimension != dimension) {   // outro mundo: o que estava gravado nao vale mais
            dimension = me.dimension;
            clear();
        }
        ParticleTap.attach(mc.theWorld);
        fill(ring[next], mc, dist);
        next = (next + 1) % ring.length;
        size = Math.min(size + 1, ring.length);
    }

    /** O que um registro de entidade e, ou -1 para o que nao se grava (os jogadores vao a parte). */
    private static int kindOf(Entity e) {
        if (e instanceof EntityItem) {
            return ITEM;
        }
        if (e instanceof EntityXPOrb) {
            return XP;
        }
        if (e instanceof EntityArrow) {
            return ARROW;
        }
        if (e instanceof EntityFishHook) {
            return HOOK;
        }
        if (e instanceof EntityThrowable) {
            return THROWN;
        }
        return e instanceof EntityLivingBase && !(e instanceof EntityPlayer) && EntityList.getEntityID(e) > 0 ? MOB : -1;
    }

    /**
     * O tick de agora num quadro: os jogadores ate {@link #RADIUS} (os mais perto, se forem muitos), depois os itens, XP,
     * flechas, mobs e arremessos ate {@link #OTHER_RADIUS}, a hora e o tempo do mundo, as particulas e os sons.
     */
    static void fill(Frame f, Minecraft mc, double[] dist) {
        EntityPlayer me = mc.thePlayer;
        f.count = 0;
        ParticleTap.take(f.events);
        f.worldTime = mc.theWorld.getWorldTime();
        f.rain = mc.theWorld.getRainStrength(1);
        f.thunder = mc.theWorld.getThunderStrength(1);
        List<EntityPlayer> players = mc.theWorld.playerEntities;
        for (EntityPlayer p : players) {
            if (Session.isFake(p)) {
                continue;   // as copias do proprio replay (no mundo enquanto ele toca)
            }
            boolean isSelf = p == me;
            double d = isSelf ? 0 : p.getDistanceSqToEntity(me);
            if (d > RADIUS * RADIUS) {
                continue;
            }
            if (f.count < MAX_PLAYERS) {
                dist[f.count] = d;
                f.snaps[f.count++].copy(p, isSelf);
                continue;
            }
            // Cheio: troca o mais longe, se este estiver mais perto.
            int far = 0;
            for (int i = 1; i < MAX_PLAYERS; i++) {
                if (dist[i] > dist[far]) {
                    far = i;
                }
            }
            if (d < dist[far]) {
                dist[far] = d;
                f.snaps[far].copy(p, isSelf);
            }
        }
        List<Entity> all = mc.theWorld.loadedEntityList;
        for (int i = 0; i < all.size() && f.count < f.snaps.length; i++) {
            Entity e = all.get(i);
            int what = kindOf(e);
            if (what >= 0 && !Session.isFake(e) && e.getDistanceSqToEntity(me) <= OTHER_RADIUS * OTHER_RADIUS) {
                f.snaps[f.count++].copy(e, what);
            }
        }
    }
}
