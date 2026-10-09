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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;

/**
 * Um replay tocando: os quadros gravados, o tempo (em ticks, com fracao), a velocidade, a pausa e a camera.
 *
 * <p>Os jogadores, itens, XP e flechas do replay sao copias postas no mundo do cliente com ids negativos ({@link #sync}):
 * o jogo (e o Stein GFX) os desenha como qualquer entidade. Os de verdade ficam escondidos enquanto o replay toca. A
 * camera e uma entidade a parte, posta como a entidade de visao do jogo; o jogo interpola a posicao dela entre um tick
 * e outro, entao cada tick ela recebe o lugar de agora (prev) e o do proximo passo (pos), e o quadro sai liso em
 * qualquer velocidade.
 *
 * <p><b>Camera</b>: um drone, parado num ponto e sempre olhando na mesma direcao (atras de voce e de cima), que so
 * desliza para acompanhar quando voce sai da folga do centro do quadro, sem nunca deixar voce sair dele, e ve atraves
 * dos blocos como o espectador ({@link #drone}). Na camera lenta da morte (meia velocidade do golpe final ao corpo caindo), se quem te matou
 * esta na cena, a camera vai para os olhos dele, com a mao, o item e o golpe dele ({@link #beginHand}). As particulas
 * gravadas saem de novo na hora delas ({@link #particles}). O replay da morte repete, escurecendo entre as voltas, ate
 * o jogador renascer.
 *
 * <p>A camera atravessa blocos como a de <b>espectador</b>: enquanto o replay toca o seu jogador fica como espectador
 * so no seu cliente (o jogo entao desenha vendo atraves do bloco em que a camera estiver). Nada disso vai ao servidor.
 */
final class Session {

    static final float[] SPEEDS = {0.25F, 0.5F, 1F, 2F};
    /** {@code -Dsteinreplay.debug=true}: tempo, morte, voce e a camera, a cada segundo de replay. */
    private static final boolean DEBUG = Boolean.getBoolean("steinreplay.debug");
    /**
     * A camera lenta da morte: a fracao da velocidade, os ticks antes da morte em que ela comeca a frear e em que chega
     * nela, e os ticks depois da morte em que volta a acelerar e em que chega no ritmo normal (o corpo cai em 20 ticks).
     */
    private static final float SLOW = 0.5F;
    private static final int SLOW_IN_FROM = 30;
    private static final int SLOW_IN_TO = 10;
    private static final int SLOW_OUT_FROM = 22;
    private static final int SLOW_OUT_TO = 40;
    /**
     * O drone: distancia, inclinacao (graus para baixo), os lados que ele tenta (graus a partir das suas costas), a folga
     * em volta do centro antes de ele seguir (de lado e de altura), quanto ele anda por tick do que falta, e o maximo que
     * voce sai do centro (dali ele vai junto na hora: voce nunca sai do quadro).
     */
    private static final double DRONE_DIST = 6.5;
    private static final float DRONE_PITCH = 28;
    private static final float[] DRONE_SIDES = {25, -25, 65, -65, 110, -110, 160, -160};
    private static final double DEAD_H = 1.0;
    private static final double DEAD_V = 0.6;
    private static final double FOLLOW = 0.3;
    private static final double MAX_H = 2.0;
    private static final double MAX_V = 1.2;
    /** Ticks do escurecimento entre uma volta e outra, e parado no fim antes de escurecer. */
    private static final int FADE_TICKS = 12;
    private static final int HOLD_END = 30;
    /** Altura do olho da camera (ela e um jogador sem corpo). */
    private static final double EYE = 1.62;

    final Source rec;
    /** Replay salvo (assistido fora da partida): no fim ele para, em vez de ir para a tela de morte. */
    final boolean saved;
    /** Quadros que ha agora (no replay da morte ainda chegam quadros: o corpo caindo, os itens). */
    int frames;
    /** O quadro da morte: a camera lenta e em volta dele. */
    int deathAt;
    /** Ainda chegam quadros (replay da morte, nos segundos depois de morrer). */
    boolean growing;
    /** Quem te matou (id da entidade), ou -1, e o nome dele. */
    final int killer;
    final String killerName;

    /** Tempo de agora no replay, em ticks (0 a frames - 1). */
    float time;
    int speed = 2;
    boolean paused;

    private final Minecraft mc;
    private final EntityOtherPlayerMP cam;
    private final Entity oldView;
    private final int oldPerspective;
    private final boolean oldHideGui;
    /** O passo do ultimo tick, para o desenho interpolar entre ele e o de agora. */
    private float lastStep;
    /** Ticks parado no fim, antes de escurecer para a volta seguinte. */
    private int hold;
    /** O comeco das voltas do replay da morte, e o escurecimento: ticks da volta escurecendo (0 = nao), clareando. */
    float loopFrom;
    private int looping;
    private boolean fadingIn;
    private float fade;
    private float prevFade;
    /** Quem te matou como estava no golpe final, para o card (ou null). */
    final Recorder.Snap killerCard;

    Session(Minecraft mc, Source rec, boolean saved) {
        this.mc = mc;
        this.rec = rec;
        this.saved = saved;
        this.frames = rec.size();
        this.deathAt = deathFrame();
        int[] k = findKiller();
        this.killer = k[0];
        Recorder.Snap ks = killer >= 0 ? rec.frame(k[1]).find(killer) : null;
        this.killerName = ks != null && ks.profile != null ? ks.profile.getName() : null;
        Recorder.Snap card = null;
        for (int i = Math.min(deathAt, frames - 1); i >= 0 && card == null && killer >= 0; i--) {
            Recorder.Snap s = rec.frame(i).find(killer);
            if (s != null) {
                card = new Recorder.Snap();
                card.copy(s);
            }
        }
        this.killerCard = card;
        oldView = mc.getRenderViewEntity();
        oldPerspective = mc.gameSettings.thirdPersonView;
        oldHideGui = mc.gameSettings.hideGUI;
        cam = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
        ReplayPlayer.register(mc);
        mc.setRenderViewEntity(cam);
        mc.gameSettings.hideGUI = true;   // sem a mao (a do jogador morto) na camera dos olhos
        dev.xavier.stein.loader.api.Hud.setHidden(true);   // o HUD inteiro: o jogo desenha o HUD ate com tela aberta
        spectator(true);
        seek(0);
    }

    /**
     * Quem te matou: o jogador que estava mais perto de voce e golpeando (o braco no meio do golpe) no ultimo segundo
     * antes de voce tomar o ultimo dano. Sem ninguem golpeando a 6 blocos, ninguem.
     *
     * @return {id, quadro em que foi visto}
     */
    private int[] findKiller() {
        int last = frames - 1;
        for (int i = frames - 1; i >= 0; i--) {   // o ultimo golpe que voce levou
            Recorder.Snap me = rec.frame(i).self();
            if (me != null && me.hurtTime > 0) {
                last = i;
                if (me.hurtTime >= 9) {
                    break;
                }
            }
        }
        int best = -1;
        int seen = -1;
        double bestD = 36;
        for (int i = Math.max(0, last - 20); i <= last; i++) {
            Recorder.Frame f = rec.frame(i);
            Recorder.Snap me = f.self();
            if (me == null) {
                continue;
            }
            for (int j = 0; j < f.count; j++) {
                Recorder.Snap s = f.snaps[j];
                if (s.self || s.kind != Recorder.PLAYER || s.swing <= 0) {
                    continue;
                }
                double d = sq(s.x - me.x) + sq(s.y - me.y) + sq(s.z - me.z);
                if (d < bestD) {
                    bestD = d;
                    best = s.id;
                    seen = i;
                }
            }
        }
        return new int[] {best, seen};
    }

    /**
     * O quadro da morte: o primeiro em que voce aparece morrendo (o corpo caindo); sem nenhum (o replay da morte
     * comeca antes deles chegarem), o ultimo quadro de agora — o tick em que voce morreu.
     */
    private int deathFrame() {
        for (int i = 0; i < rec.size(); i++) {
            Recorder.Snap me = rec.frame(i).self();
            if (me != null && me.deathTime > 0) {
                return i;
            }
        }
        return Math.max(0, rec.size() - 1);
    }

    /**
     * A velocidade no tempo t: a escolhida, vezes a camera lenta em volta da morte — freia no ultimo segundo e meio,
     * fica lenta no golpe e no corpo caindo, e volta ao ritmo normal para os itens se espalhando.
     */
    private float rate(float t) {
        float base = SPEEDS[speed];
        float d = t - deathAt;   // negativo antes da morte
        if (d <= -SLOW_IN_FROM || d >= SLOW_OUT_TO) {
            return base;
        }
        if (d < -SLOW_IN_TO) {
            return base * (SLOW + (1 - SLOW) * (-d - SLOW_IN_TO) / (SLOW_IN_FROM - SLOW_IN_TO));
        }
        if (d > SLOW_OUT_FROM) {
            return base * (SLOW + (1 - SLOW) * (d - SLOW_OUT_FROM) / (SLOW_OUT_TO - SLOW_OUT_FROM));
        }
        return base * SLOW;
    }

    /** Um tick de jogo: anda o replay, a camera e o escurecimento das voltas. */
    void tick() {
        frames = rec.size();
        prevFade = fade;
        if (looping > 0) {   // fim de uma volta: escurece, volta ao comeco e clareia
            looping++;
            fade = Math.min(1, looping / (float) FADE_TICKS);
            if (looping > FADE_TICKS) {
                looping = 0;
                time = loopFrom;
                lastStep = 0;
                lastEvents = (int) Math.floor(time);
                sync(time, time);
                drone(true);
                view();
                aimPlayer();
                prevFade = fade = 1;
                fadingIn = true;
            }
            return;
        }
        if (fadingIn) {
            fade = Math.max(0, fade - 1F / FADE_TICKS);
            fadingIn = fade > 0;
        }
        float step = paused ? 0 : rate(time);
        playRate = paused ? 1 : step;
        float now = time;
        time = Math.min(frames - 1, time + step);
        lastStep = time - now;
        sync(now, time);
        drone(false);
        view();
        aimPlayer();
        particles(time);
        weather();
        arm();
        if (!saved && time >= frames - 1 && !growing) {
            if (++hold > HOLD_END) {
                hold = 0;
                looping = 1;   // replay da morte: repete ate o jogador renascer
            }
        } else {
            hold = 0;
        }
        if (DEBUG && (int) now / 20 != (int) time / 20) {
            Recorder.Snap me = at(time, -1, true);
            System.out.printf("[steinreplay] t=%.1f/%d morte=%d olhos=%s eu=%s camera em %.1f %.1f %.1f sons=%d%n", time,
                    frames, deathAt, killerView ? "assassino" : "drone",
                    me == null ? "-" : String.format("%.1f %.1f %.1f", me.x, me.y, me.z), cam.posX, cam.posY, cam.posZ,
                    soundsPlayed);
        }
    }

    /** Quem te matou no tempo de agora (o registro do quadro), so nos olhos dele; senao null. */
    Recorder.Snap killerNow() {
        return killerView ? rec.frame(clamp((int) Math.floor(time))).find(killer) : null;
    }

    /** Replay salvo: para no fim (o jogador volta, reve ou sai). */
    void finished() {
        if (saved && time >= frames - 1) {
            paused = true;
        }
    }

    /** Comeca escuro e clareia (o replay da morte entra assim, depois do escurecimento da morte). */
    void fadeIn() {
        prevFade = fade = 1;
        fadingIn = true;
    }

    /** O escurecimento no quadro (0 nada, 1 preto). */
    float fade(float partialTicks) {
        return prevFade + (fade - prevFade) * partialTicks;
    }

    void seek(float ticks) {
        time = Math.max(0, Math.min(frames - 1, time + ticks));
        lastStep = 0;
        sync(time, time);
        drone(true);
        view();
        aimPlayer();
        lastEvents = (int) Math.floor(time);   // o trecho pulado nao solta particulas
    }

    // ---- a camera: o drone e, na camera lenta, os olhos de quem te matou ----

    /** O ponto que o drone segue (voce, quando sai da folga do centro), a direcao fixa e a distancia de agora. */
    private double ax;
    private double ay;
    private double az;
    private float droneYaw;
    private float dronePitch;
    private boolean droneChosen;
    /** A distancia de agora (menor que a do drone so quando ele entra numa parede para ver atraves). */
    private double camDist = DRONE_DIST;
    /** Os olhos de quem te matou estao no lugar do drone. */
    boolean killerView;

    /**
     * O drone: parado num ponto fixo, sempre olhando na mesma direcao, enquanto voce fica perto do centro do quadro;
     * quando voce sai da folga, ele desliza atras de voce, sem girar nem trocar de lado, e nunca deixa voce sair do quadro.
     * Bloco no caminho nao o tira de la: ele ve atraves, como o espectador. {@code jump}: posicao nova sem deslizar
     * (comeco, volta, pulo no tempo).
     */
    private void drone(boolean jump) {
        Recorder.Snap me = at(time, -1, true);
        if (me == null) {
            return;
        }
        double px = me.x;
        double py = me.y + 1.0;
        double pz = me.z;
        if (!droneChosen) {
            chooseDrone(px, py, pz, me.yaw);
        }
        if (jump) {
            ax = px;
            ay = py;
            az = pz;
        } else {
            double dx = px - ax;
            double dz = pz - az;
            double h = Math.sqrt(dx * dx + dz * dz);
            double tx = h > DEAD_H ? ax + dx * (1 - DEAD_H / h) : ax;
            double tz = h > DEAD_H ? az + dz * (1 - DEAD_H / h) : az;
            double dy = py - ay;
            double ty = Math.abs(dy) > DEAD_V ? ay + dy - Math.signum(dy) * DEAD_V : ay;
            ax += (tx - ax) * FOLLOW;
            ay += (ty - ay) * FOLLOW;
            az += (tz - az) * FOLLOW;
            dx = px - ax;
            dz = pz - az;
            h = Math.sqrt(dx * dx + dz * dz);
            if (h > MAX_H) {
                ax = px - dx * MAX_H / h;
                az = pz - dz * MAX_H / h;
            }
            if (Math.abs(py - ay) > MAX_V) {
                ay = py - Math.signum(py - ay) * MAX_V;
            }
        }
        // Como o espectador: dentro de um bloco o jogo desenha vendo atraves dele (num buraco, a camera fica no chao e ve
        // voce caindo la dentro). No ar, com um bloco entre ela e voce (uma parede), ela entra no primeiro bloco do
        // caminho, e a parede some do mesmo jeito.
        double[] look = look(droneYaw, dronePitch);
        double want = DRONE_DIST;
        double[] eye = {ax - look[0] * DRONE_DIST, ay - look[1] * DRONE_DIST, az - look[2] * DRONE_DIST};
        if (!solid(eye[0], eye[1], eye[2])) {
            MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(new Vec3(eye[0], eye[1], eye[2]), new Vec3(px, py, pz),
                    false, true, false);
            if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && hit.hitVec != null) {
                want = Math.max(0.6, Math.sqrt(sq(hit.hitVec.xCoord - ax) + sq(hit.hitVec.yCoord - ay)
                        + sq(hit.hitVec.zCoord - az)) - 0.4);
            }
        }
        camDist = jump ? want : camDist + (want - camDist) * (want < camDist ? 0.5 : 0.15);
        double cx = ax - look[0] * camDist;
        double cy = ay - look[1] * camDist - EYE;
        double cz = az - look[2] * camDist;
        if (jump) {
            cam.prevPosX = cam.lastTickPosX = cx;
            cam.prevPosY = cam.lastTickPosY = cy;
            cam.prevPosZ = cam.lastTickPosZ = cz;
        } else {
            cam.prevPosX = cam.lastTickPosX = cam.posX;
            cam.prevPosY = cam.lastTickPosY = cam.posY;
            cam.prevPosZ = cam.lastTickPosZ = cam.posZ;
        }
        cam.posX = cx;
        cam.posY = cy;
        cam.posZ = cz;
        cam.prevRotationYaw = cam.rotationYaw = droneYaw;
        cam.prevRotationPitch = cam.rotationPitch = dronePitch;
    }

    /**
     * A direcao do drone, escolhida uma vez: atras de voce e um pouco de lado, olhando de cima; o primeiro dos lados com o
     * caminho livre ate voce. Cercado (buraco, fosso), mais de cima, vendo atraves do chao.
     */
    private void chooseDrone(double px, double py, double pz, float yaw) {
        droneChosen = true;
        for (float off : DRONE_SIDES) {
            double[] look = look(yaw + off, DRONE_PITCH);
            double[] p = {px - look[0] * DRONE_DIST, py - look[1] * DRONE_DIST, pz - look[2] * DRONE_DIST};
            if (clear(px, py, pz, p)) {
                droneYaw = yaw + off;
                dronePitch = DRONE_PITCH;
                return;
            }
        }
        droneYaw = yaw;
        dronePitch = 55;
    }

    /** Um bloco que tapa a vista no ponto (a camera dentro dele ve atraves). */
    private boolean solid(double x, double y, double z) {
        return mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock().isOpaqueCube();
    }

    private static java.lang.reflect.Field reportedYaw;
    private static java.lang.reflect.Field reportedPitch;

    /**
     * O olhar do jogador do cliente igual ao da camera de agora: o jogo vira as particulas (e outros desenhos de frente
     * para a tela) para o olhar do jogador, e nao para a camera que esta desenhando. O ultimo olhar mandado ao servidor
     * vai junto, para o jogo nao mandar o olhar novo: nada sai daqui.
     */
    private void aimPlayer() {
        EntityPlayerSP p = mc.thePlayer;
        Entity v = mc.getRenderViewEntity();
        if (p == null || v == null || v == p) {
            return;
        }
        p.prevRotationYaw = v.prevRotationYaw;
        p.rotationYaw = v.rotationYaw;
        p.prevRotationPitch = v.prevRotationPitch;
        p.rotationPitch = v.rotationPitch;
        try {
            if (reportedYaw == null) {
                reportedYaw = field(EntityPlayerSP.class, "field_175164_bL");
                reportedPitch = field(EntityPlayerSP.class, "field_175165_bM");
            }
            reportedYaw.setFloat(p, p.rotationYaw);
            reportedPitch.setFloat(p, p.rotationPitch);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // sem o campo, o servidor so recebe o olhar de um jogador morto
        }
    }

    /** A direcao de um olhar (yaw e pitch do jogo), em x, y, z. */
    private static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[] {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /**
     * De quem e o olhar agora: na camera lenta da morte, se quem te matou esta na cena, os olhos dele (o corpo dele nao e
     * desenhado, e a mao com o item entra em {@link #beginHand}); senao, o drone.
     */
    private void view() {
        Entity k = killer >= 0 ? inWorld.get(killer) : null;
        killerView = k != null && time >= deathAt - SLOW_IN_FROM;
        Entity want = killerView ? k : cam;
        if (mc.getRenderViewEntity() != want) {
            mc.setRenderViewEntity(want);
        }
    }

    /** Nenhum bloco entre o alvo e o ponto. */
    private boolean clear(double tx, double ty, double tz, double[] p) {
        return free(tx, ty, tz, p) >= Math.sqrt(sq(p[0] - tx) + sq(p[1] - ty) + sq(p[2] - tz)) - 1e-3;
    }

    /** Quanto se anda do alvo ate o ponto antes de bater num bloco (a distancia toda, sem bloco). */
    private double free(double tx, double ty, double tz, double[] p) {
        double full = Math.sqrt(sq(p[0] - tx) + sq(p[1] - ty) + sq(p[2] - tz));
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(new Vec3(tx, ty, tz), new Vec3(p[0], p[1], p[2]), false,
                true, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.hitVec == null) {
            return full;
        }
        return Math.sqrt(sq(hit.hitVec.xCoord - tx) + sq(hit.hitVec.yCoord - ty) + sq(hit.hitVec.zCoord - tz));
    }

    /**
     * Um jogador (o id, ou voce com self) no tempo t, interpolado entre os dois quadros vizinhos; null se ele nao esta
     * gravado ali.
     */
    private Recorder.Snap at(float t, int id, boolean self) {
        int i = (int) Math.floor(t);
        float f = t - i;
        Recorder.Frame f0 = rec.frame(clamp(i));
        Recorder.Frame f1 = rec.frame(clamp(i + 1));
        Recorder.Snap a = self ? f0.self() : f0.find(id);
        if (a == null) {
            return null;
        }
        Recorder.Snap b = self ? f1.self() : f1.find(id);
        if (b == null) {
            b = a;
        }
        // Voce e o alvo sao lidos ao mesmo tempo: cada um no seu rascunho, para um nao escrever por cima do outro.
        Recorder.Snap s = self ? scratch : scratch2;
        s.x = lerp(a.x, b.x, f);
        s.y = lerp(a.y, b.y, f);
        s.z = lerp(a.z, b.z, f);
        s.yaw = lerpAngle(a.yaw, b.yaw, f);
        s.pitch = lerp(a.pitch, b.pitch, f);
        return s;
    }

    private final Recorder.Snap scratch = new Recorder.Snap();
    private final Recorder.Snap scratch2 = new Recorder.Snap();

    private int clamp(int i) {
        return Math.max(0, Math.min(frames - 1, i));
    }

    // ---- a mao de quem te matou ----

    /** A velocidade do replay neste tick (o tom dos sons). */
    private float playRate = 1;
    /** Sons tocados pelo replay (o log de depuracao mostra). */
    private int soundsPlayed;
    /** A hora e o tempo do mundo de antes do replay (voltam no fim). */
    private float oldRain = -1;
    private float oldThunder;

    /** A hora do mundo, a chuva e a tempestade gravadas no tick de agora (sem elas, as do mundo de verdade vazam). */
    private void weather() {
        Recorder.Frame f = rec.frame(clamp((int) Math.floor(time)));
        if (f.worldTime < 0 || mc.theWorld == null) {
            return;
        }
        if (oldRain < 0) {
            oldRain = mc.theWorld.getRainStrength(1);
            oldThunder = mc.theWorld.getThunderStrength(1);
        }
        mc.theWorld.setWorldTime(f.worldTime);
        mc.theWorld.setRainStrength(f.rain);
        mc.theWorld.setThunderStrength(f.thunder);
    }

    /** O braco de quem te matou atrasa um pouco ao virar, como o do jogo (metade do caminho por tick). */
    private float armYaw;
    private float armPitch;
    private float prevArmYaw;
    private float prevArmPitch;
    private boolean armSet;

    private void arm() {
        Entity k = killerView ? inWorld.get(killer) : null;
        if (k == null) {
            armSet = false;
            return;
        }
        if (!armSet) {
            armYaw = k.rotationYaw;
            armPitch = k.rotationPitch;
            armSet = true;
        }
        prevArmYaw = armYaw;
        prevArmPitch = armPitch;
        armYaw += (k.rotationYaw - armYaw) * 0.5F;
        armPitch += (k.rotationPitch - armPitch) * 0.5F;
        if (DEBUG && k instanceof EntityOtherPlayerMP p && (p.hurtTime > 0 || p.cameraYaw > 0.01F)) {
            System.out.printf("[steinreplay] olhos dele: dano %d, balanco %.3f, andou %.2f%n", p.hurtTime, p.cameraYaw,
                    p.distanceWalkedModified - p.prevDistanceWalkedModified);
        }
    }

    /** O jogador do cliente no lugar de quem te matou, so enquanto a mao e desenhada. */
    private ReplayPlayer.Hand puppet;
    private EntityPlayerSP realPlayer;
    private boolean handSwapped;
    private Object savedItem;
    private float savedEquip;
    private float savedPrevEquip;
    private Object savedMode;
    private static java.lang.reflect.Field itemToRender;
    private static java.lang.reflect.Field equipped;
    private static java.lang.reflect.Field prevEquipped;
    private static java.lang.reflect.Field controllerMode;
    private static java.lang.reflect.Field uuidField;

    /**
     * Nos olhos de quem te matou, a mao dele: o jogo desenha a mao em primeira pessoa a partir do jogador do cliente, e
     * aqui, so entre o fim do mundo desenhado e a tela por cima (onde {@link #endHand} volta tudo), o jogador do cliente e
     * um boneco com o item, o golpe, o bloqueio e a skin dele. O resto do jogo (o tick, os pacotes) nunca ve o boneco.
     */
    void beginHand() {
        if (!killerView || handSwapped || mc.thePlayer == null) {
            return;
        }
        Entity e = inWorld.get(killer);
        if (!(e instanceof EntityOtherPlayerMP k)) {
            return;
        }
        try {
            if (itemToRender == null) {
                itemToRender = field(ItemRenderer.class, "field_78453_b");
                equipped = field(ItemRenderer.class, "field_78454_c");
                prevEquipped = field(ItemRenderer.class, "field_78451_d");
                controllerMode = field(PlayerControllerMP.class, "field_78779_k");
                uuidField = field(Entity.class, "field_96093_i");
            }
            if (puppet == null || puppet.worldObj != mc.theWorld) {
                puppet = new ReplayPlayer.Hand(mc, mc.theWorld);
                uuidField.set(puppet, k.getUniqueID());   // o modelo do braco (fino ou largo) vem da lista, por este UUID
            }
            puppet.nick = k.getName();
            puppet.prevPosX = puppet.lastTickPosX = k.prevPosX;
            puppet.prevPosY = puppet.lastTickPosY = k.prevPosY;
            puppet.prevPosZ = puppet.lastTickPosZ = k.prevPosZ;
            puppet.setPosition(k.posX, k.posY, k.posZ);
            puppet.prevRotationYaw = k.prevRotationYaw;
            puppet.rotationYaw = k.rotationYaw;
            puppet.prevRotationPitch = k.prevRotationPitch;
            puppet.rotationPitch = k.rotationPitch;
            puppet.prevRenderArmYaw = prevArmYaw;
            puppet.renderArmYaw = armYaw;
            puppet.prevRenderArmPitch = prevArmPitch;
            puppet.renderArmPitch = armPitch;
            puppet.prevSwingProgress = k.prevSwingProgress;
            puppet.swingProgress = k.swingProgress;
            ItemStack held = k.getHeldItem();
            puppet.inventory.currentItem = 0;
            puppet.inventory.mainInventory[0] = held;
            if (k.isUsingItem() && held != null) {
                if (!puppet.isUsingItem()) {
                    puppet.setItemInUse(held, 72000);
                }
            } else if (puppet.isUsingItem()) {
                puppet.clearItemInUse();
            }
            ItemRenderer ir = mc.getItemRenderer();
            savedItem = itemToRender.get(ir);
            savedEquip = equipped.getFloat(ir);
            savedPrevEquip = prevEquipped.getFloat(ir);
            itemToRender.set(ir, held);
            equipped.setFloat(ir, 1);
            prevEquipped.setFloat(ir, 1);
            savedMode = controllerMode.get(mc.playerController);
            controllerMode.set(mc.playerController, WorldSettings.GameType.SURVIVAL);   // o espectador nao tem mao
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return;
        }
        realPlayer = mc.thePlayer;
        mc.thePlayer = puppet;
        mc.gameSettings.hideGUI = false;
        handSwapped = true;
    }

    /** O jogador do cliente de volta (chamado pela tela, logo depois da mao, e no tick, por garantia). */
    void endHand() {
        if (!handSwapped) {
            return;
        }
        handSwapped = false;
        mc.thePlayer = realPlayer;
        realPlayer = null;
        mc.gameSettings.hideGUI = true;
        try {
            ItemRenderer ir = mc.getItemRenderer();
            itemToRender.set(ir, savedItem);
            equipped.setFloat(ir, savedEquip);
            prevEquipped.setFloat(ir, savedPrevEquip);
            controllerMode.set(mc.playerController, savedMode);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // os mesmos campos que acabaram de ser lidos
        }
    }

    private static java.lang.reflect.Field field(Class<?> c, String name) throws NoSuchFieldException {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** O quadro: primeira pessoa (o drone e os olhos de quem te matou sao o ponto exato da camera), e a mao dele. */
    void render(Minecraft mc, float partialTicks) {
        mc.gameSettings.thirdPersonView = 0;
        beginHand();
    }

    // ---- as copias do replay, no mundo do cliente ----

    /** As copias que estao no mundo agora, pelo id gravado, e o id (negativo) com que cada uma entrou. */
    private final Map<Integer, Entity> inWorld = new HashMap<>();
    private final Map<Integer, Integer> fakeIds = new HashMap<>();
    private int nextFakeId = -100000;
    private final Recorder.Snap stA = new Recorder.Snap();
    private final Recorder.Snap stB = new Recorder.Snap();

    /**
     * As copias do replay no estado do tempo a (o de antes) e b (o de agora): entram no mundo do cliente, com ids
     * negativos que o servidor nunca usa, e o jogo as desenha como desenha qualquer entidade — inclusive pelo Stein GFX
     * —, interpolando entre os dois estados a cada quadro. Quem sai da cena sai do mundo. Chamado a cada tick e ao
     * pular no tempo; o estado e escrito depois do tick do mundo, entao vale o gravado e nao a fisica do cliente.
     */
    private void sync(float a, float b) {
        if (mc.theWorld == null) {
            return;
        }
        int ib = (int) Math.floor(b);
        float fb = b - ib;
        Recorder.Frame b0 = rec.frame(clamp(ib));
        Recorder.Frame b1 = rec.frame(clamp(ib + 1));
        int ia = (int) Math.floor(a);
        float fa = a - ia;
        Recorder.Frame a0 = rec.frame(clamp(ia));
        Recorder.Frame a1 = rec.frame(clamp(ia + 1));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int j = 0; j < b0.count; j++) {
            Recorder.Snap s = b0.snaps[j];
            if (s.kind == Recorder.PLAYER && (s.invisible || s.profile == null)) {
                continue;   // invisivel ou sem perfil (nos olhos de quem te matou, o jogo nao desenha a entidade de visao)
            }
            Recorder.Snap s1 = b1.find(s.id);
            mix(stB, s, s1 != null ? s1 : s, fb);
            Recorder.Snap p0 = a0.find(s.id);
            Recorder.Snap p1 = a1.find(s.id);
            if (p0 != null) {
                mix(stA, p0, p1 != null ? p1 : p0, fa);
            } else {
                stA.copy(stB);
            }
            if (s.kind == Recorder.PLAYER || s.kind == Recorder.MOB) {
                swing(s.id, a, b);
            }
            Entity e = ensure(s);
            if (e == null) {
                continue;
            }
            setState(e, stA, stB);
            seen.add(s.id);
        }
        java.util.Iterator<Map.Entry<Integer, Entity>> it = inWorld.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entity> en = it.next();
            if (!seen.contains(en.getKey())) {
                mc.theWorld.removeEntityFromWorld(fakeIds.get(en.getKey()));
                it.remove();
            }
        }
    }

    /** Ticks de um golpe (o do jogo, sem pressa nem cansaco). */
    private static final int SWING_TICKS = 6;

    /**
     * O braco no tempo a e no b, a partir do inicio gravado de cada golpe, no tempo continuo do replay: cada golpe que o
     * jogo mostrou aparece, inteiro, em qualquer velocidade (misturar o braco de dois quadros perdia os golpes que
     * recomecam no meio do anterior, os cliques rapidos). Um golpe novo entre a e b comeca do zero.
     */
    private void swing(int id, float a, float b) {
        int kb = swingStart(id, b);
        int ka = swingStart(id, a);
        stB.swing = kb >= 0 ? (b - kb) / SWING_TICKS : 0;
        stA.swing = ka >= 0 && ka == kb ? (a - ka) / SWING_TICKS : 0;
        if (stB.swing >= 1) {
            stB.swing = 0;
        }
        if (stA.swing >= 1) {
            stA.swing = 0;
        }
    }

    /** O quadro em que comecou o golpe que esta no ar no tempo t, ou -1. */
    private int swingStart(int id, float t) {
        int i = (int) Math.floor(t);
        for (int k = i; k >= 0 && k > i - SWING_TICKS; k--) {
            if (k >= frames) {
                continue;
            }
            Recorder.Snap s = rec.frame(k).find(id);
            if (s != null && s.swingStart) {
                return k;
            }
        }
        return -1;
    }

    /** A copia de um registro (criada e posta no mundo na primeira vez). */
    private Entity ensure(Recorder.Snap s) {
        Entity e = inWorld.get(s.id);
        if (e != null && e.worldObj == mc.theWorld) {
            return e;
        }
        if (s.kind == Recorder.PLAYER) {
            e = new ReplayPlayer(mc.theWorld, s.self ? selfCopy(s.profile) : s.profile, s.profile.getName());
        } else if (s.kind == Recorder.ITEM) {
            if (s.item == null) {
                return null;
            }
            e = new EntityItem(mc.theWorld, s.x, s.y, s.z, s.item);
        } else if (s.kind == Recorder.XP) {
            e = new EntityXPOrb(mc.theWorld, s.x, s.y, s.z, Math.max(1, s.xp));
        } else if (s.kind == Recorder.ARROW) {
            e = new EntityArrow(mc.theWorld, s.x, s.y, s.z);
        } else if (s.kind == Recorder.MOB || s.kind == Recorder.THROWN) {
            e = s.etype > 0 ? EntityList.createEntityByID(s.etype, mc.theWorld) : null;
            if (e instanceof EntityPotion potion) {
                potion.setPotionDamage(s.xp);
            }
        } else if (s.kind == Recorder.HOOK) {
            // A linha vai ate a mao de quem pesca: so com a copia dele ja na cena.
            Entity angler = inWorld.get(s.xp);
            e = angler instanceof EntityPlayer p ? new EntityFishHook(mc.theWorld, s.x, s.y, s.z, p) : null;
        }
        if (e == null) {
            return null;
        }
        int id = fakeIds.computeIfAbsent(s.id, k -> nextFakeId--);
        e.setPosition(s.x, s.y, s.z);
        mc.theWorld.addEntityToWorld(id, e);
        inWorld.put(s.id, e);
        return e;
    }

    boolean isActor(Object entity) {
        return entity == cam || entity instanceof Entity && inWorld.containsValue(entity);
    }

    /** Um id de entidade que e copia do replay (os negativos que ele pos no mundo). */
    static boolean isFake(Entity e) {
        return e.getEntityId() <= -100000;
    }

    /** O estado entre dois registros (o mesmo jogador em dois quadros), fracao f. */
    private static void mix(Recorder.Snap out, Recorder.Snap a, Recorder.Snap b, float f) {
        out.copy(f < 0.5F ? a : b);
        out.x = lerp(a.x, b.x, f);
        out.y = lerp(a.y, b.y, f);
        out.z = lerp(a.z, b.z, f);
        out.yaw = lerpAngle(a.yaw, b.yaw, f);
        out.pitch = lerp(a.pitch, b.pitch, f);
        out.headYaw = lerpAngle(a.headYaw, b.headYaw, f);
        out.bodyYaw = lerpAngle(a.bodyYaw, b.bodyYaw, f);
        out.limbSwing = lerp(a.limbSwing, b.limbSwing, f);
        out.limbAmount = lerp(a.limbAmount, b.limbAmount, f);
        out.deathF = lerp(a.deathTime, b.deathTime, f);
        out.swing = Math.min(1, b.swing < a.swing ? a.swing + (1 - a.swing) * f : lerp(a.swing, b.swing, f));
    }

    // ---- particulas ----

    /** O ultimo quadro cujas particulas ja sairam. */
    private int lastEvents = -1;
    /** As particulas que estao na tela agora e sao do replay (as outras sao do mundo de verdade, de agora). */
    private final java.util.Set<EntityFX> known = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static java.lang.reflect.Field fxLayers;

    /**
     * As particulas dos quadros que o replay acabou de passar, pedidas de novo ao jogo como o RenderGlobal pediria (a
     * mesma distancia maxima e a mesma opcao de particulas). Antes, as particulas novas que nao sao do replay saem: o
     * mundo de verdade segue rodando por tras (no replay da morte), e a poeira da sua morte apareceria duas vezes. Pular
     * no tempo nao solta as particulas do trecho pulado.
     */
    private void particles(float b) {
        int ib = (int) Math.floor(b);
        purgeForeign();
        if (ib < lastEvents) {
            lastEvents = ib;
            return;
        }
        ParticleTap.replaying = true;
        try {
            for (int i = Math.max(lastEvents + 1, ib - 3); i <= ib; i++) {
                play(rec.frame(clamp(i)).events);
            }
        } finally {
            ParticleTap.replaying = false;
        }
        lastEvents = ib;
        remember();
    }

    private void play(Recorder.Events e) {
        Entity view = mc.getRenderViewEntity();
        int setting = mc.gameSettings.particleSetting;
        for (int i = 0; i < e.count; i++) {
            double x = e.pos[i * 3];
            double y = e.pos[i * 3 + 1];
            double z = e.pos[i * 3 + 2];
            if (!e.ignoreRange[i]) {
                if (view != null && view.getDistanceSq(x, y, z) > 1024) {
                    continue;
                }
                int k = setting == 1 && mc.theWorld.rand.nextInt(3) == 0 ? 2 : setting;
                if (k > 1) {
                    continue;
                }
            }
            mc.effectRenderer.spawnEffectParticle(e.type[i], x, y, z, e.vel[i * 3], e.vel[i * 3 + 1], e.vel[i * 3 + 2],
                    e.params(i));
        }
        for (int i = 0; i < e.auxCount * 5; i += 5) {
            mc.renderGlobal.playAuxSFX(null, e.aux[i], new BlockPos(e.aux[i + 1], e.aux[i + 2], e.aux[i + 3]),
                    e.aux[i + 4]);
        }
        // Os sons, com o tom mais grave na camera lenta (como um filme em camera lenta).
        float tone = Math.max(0.6F, Math.min(1, playRate));
        soundsPlayed += e.soundCount;
        for (int i = 0; i < e.soundCount; i++) {
            float[] s = e.sound;
            mc.getSoundHandler().playSound(new PositionedSoundRecord(new ResourceLocation(e.soundName[i]), s[i * 5 + 3],
                    s[i * 5 + 4] * tone, s[i * 5], s[i * 5 + 1], s[i * 5 + 2]));
        }
    }

    @SuppressWarnings("unchecked")
    private List<EntityFX>[][] layers() {
        try {
            if (fxLayers == null) {
                java.lang.reflect.Field f = EffectRenderer.class.getDeclaredField("field_78876_b");
                f.setAccessible(true);
                fxLayers = f;
            }
            return (List<EntityFX>[][]) fxLayers.get(mc.effectRenderer);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Tira da tela as particulas que entraram desde o ultimo tick e nao sao do replay. */
    private void purgeForeign() {
        List<EntityFX>[][] layers = layers();
        if (layers == null) {
            return;
        }
        for (List<EntityFX>[] layer : layers) {
            for (List<EntityFX> list : layer) {
                if (list != null) {
                    list.removeIf(fx -> !known.contains(fx));
                }
            }
        }
    }

    /** As particulas na tela agora sao todas do replay. */
    private void remember() {
        known.clear();
        List<EntityFX>[][] layers = layers();
        if (layers == null) {
            return;
        }
        for (List<EntityFX>[] layer : layers) {
            for (List<EntityFX> list : layer) {
                if (list != null) {
                    known.addAll(list);
                }
            }
        }
    }

    // ---- espectador (so no cliente) ----

    /** O modo de jogo que o seu jogador tinha na lista antes do replay (para voltar), ou null. */
    private WorldSettings.GameType oldMode;

    /**
     * Poe o seu jogador como espectador na lista de jogadores do seu cliente (ou volta o de antes): e por ela que o jogo
     * sabe se desenha como espectador. Pelo mesmo pacote que o servidor mandaria, e so aqui: nada vai ao servidor.
     */
    private void spectator(boolean on) {
        net.minecraft.client.network.NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null || mc.thePlayer == null) {
            return;
        }
        java.util.UUID id = mc.thePlayer.getGameProfile().getId();
        net.minecraft.client.network.NetworkPlayerInfo info = handler.getPlayerInfo(id);
        if (info == null) {
            return;
        }
        WorldSettings.GameType mode;
        if (on) {
            oldMode = info.getGameType();
            mode = WorldSettings.GameType.SPECTATOR;
        } else {
            if (oldMode == null) {
                return;
            }
            mode = oldMode;
            oldMode = null;
        }
        try {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            buf.writeEnumValue(S38PacketPlayerListItem.Action.UPDATE_GAME_MODE);
            buf.writeVarIntToBuffer(1);
            buf.writeUuid(id);
            buf.writeVarIntToBuffer(mode.getID());
            S38PacketPlayerListItem packet = new S38PacketPlayerListItem();
            packet.readPacketData(buf);
            handler.handlePlayerListItem(packet);
        } catch (java.io.IOException e) {
            // pacote montado aqui mesmo: nao falha
        }
    }

    /**
     * O "voce" gravado com um UUID proprio (e a mesma skin): o seu UUID esta como espectador durante o replay, e o corpo
     * gravado com ele seria desenhado como espectador (so a cabeca, transparente). A copia entra na lista de jogadores
     * do cliente enquanto o replay toca, para a skin carregar, e sai no fim.
     */
    private GameProfile selfCopy(GameProfile real) {
        if (selfCopy != null) {
            return selfCopy;
        }
        String key = "stein-replay:" + (real.getId() != null ? real.getId() : real.getName());
        selfCopy = new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), real.getName());
        selfCopy.getProperties().putAll(real.getProperties());
        list(true, selfCopy);
        return selfCopy;
    }

    private GameProfile selfCopy;

    /** Poe (ou tira) um perfil da lista de jogadores do cliente, pelo pacote que o servidor mandaria. */
    private void list(boolean add, GameProfile p) {
        net.minecraft.client.network.NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null) {
            return;
        }
        try {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
            buf.writeEnumValue(add ? S38PacketPlayerListItem.Action.ADD_PLAYER
                    : S38PacketPlayerListItem.Action.REMOVE_PLAYER);
            buf.writeVarIntToBuffer(1);
            buf.writeUuid(p.getId());
            if (add) {
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
                buf.writeVarIntToBuffer(WorldSettings.GameType.SURVIVAL.getID());
                buf.writeVarIntToBuffer(0);
                buf.writeBoolean(false);
            }
            S38PacketPlayerListItem packet = new S38PacketPlayerListItem();
            packet.readPacketData(buf);
            handler.handlePlayerListItem(packet);
        } catch (java.io.IOException e) {
            // pacote montado aqui mesmo: nao falha
        }
    }

    /**
     * Uma copia com o estado de antes (a) e o de agora (b): o jogo interpola entre os dois a cada quadro, como faz com
     * qualquer entidade.
     */
    private static void setState(Entity e, Recorder.Snap a, Recorder.Snap b) {
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
        if (!(e instanceof EntityOtherPlayerMP p)) {   // um mob: o resto do estado que ele tem
            l.prevSwingProgress = a.swing;
            l.swingProgress = b.swing;
            l.maxHurtTime = 10;
            l.hurtTime = b.hurtTime;
            l.deathTime = b.deathTime;
            for (int i = 0; i < 5; i++) {
                l.setCurrentItemOrArmor(i, b.equipment[i]);
            }
            return;
        }
        // O balanco da camera ao andar, nos olhos dele: o tick da entidade ja passou o valor anterior para os prev.
        double moved = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        boolean ground = Math.abs(b.y - a.y) < 0.05;
        p.distanceWalkedModified = p.prevDistanceWalkedModified + (float) moved * 0.6F;
        p.cameraYaw = p.prevCameraYaw + ((ground ? (float) Math.min(0.1, moved) : 0) - p.prevCameraYaw) * 0.4F;
        p.cameraPitch = p.prevCameraPitch + ((float) Math.atan(-(b.y - a.y) * 0.2) * 15 - p.prevCameraPitch) * 0.8F;
        p.prevSwingProgress = a.swing;
        p.swingProgress = b.swing;
        p.maxHurtTime = 10;   // o tremor da camera nos olhos dele divide por este (zero daria NaN)
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

    /** Volta a camera, a perspectiva e o HUD de antes. */
    void close(Minecraft mc) {
        endHand();
        puppet = null;
        if (oldRain >= 0 && mc.theWorld != null && !saved) {
            mc.theWorld.setRainStrength(oldRain);
            mc.theWorld.setThunderStrength(oldThunder);
        }
        mc.setRenderViewEntity(oldView != null && oldView.worldObj == mc.theWorld ? oldView : mc.thePlayer);
        mc.gameSettings.thirdPersonView = oldPerspective;
        mc.gameSettings.hideGUI = oldHideGui;
        dev.xavier.stein.loader.api.Hud.setHidden(false);
        spectator(false);
        if (selfCopy != null) {
            list(false, selfCopy);
            selfCopy = null;
        }
        if (mc.theWorld != null) {
            for (Integer id : inWorld.keySet()) {
                mc.theWorld.removeEntityFromWorld(fakeIds.get(id));
            }
        }
        inWorld.clear();
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double lerp(double a, double b, float f) {
        return a + (b - a) * f;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static float lerpAngle(float a, float b, float f) {
        float d = ((b - a) % 360 + 540) % 360 - 180;
        return a + d * f;
    }

    /** O angulo a escrito perto de b (sem dar a volta inteira na interpolacao do jogo). */
    private static float unwrap(float a, float b) {
        return b - (((b - a) % 360 + 540) % 360 - 180);
    }
}
