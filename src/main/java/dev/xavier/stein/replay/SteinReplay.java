/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Chat;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.ModConfig;
import dev.xavier.stein.loader.api.ModContext;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Server;
import dev.xavier.stein.loader.api.SteinMod;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;

/**
 * Replay da morte: o jogo guarda sempre os ultimos segundos dos jogadores em volta ({@link Recorder}). Quando voce
 * morre, o jogo segue em camera lenta por uns segundos, do jeito que estava ({@link DyingScreen}), escurece, e o replay
 * entra no lugar da tela de morte ({@link Session}, {@link ReplayScreen}): repete ate voce renascer, com os botoes de
 * renascer e de sair do servidor.
 *
 * <p>Cada replay da morte e salvo ({@link Recording}: os quadros e os blocos em volta) em
 * {@code steinloader/replays}. Pelo menu do Stein Loader (Configurar, no Replay) eles aparecem numa lista
 * ({@link ReplayList}) para assistir de novo — num mundo montado so com aqueles blocos ({@link ReplayWorld}) — ou
 * apagar; a pagina do Painel tambem lista e apaga.
 *
 * <p>E so visual: le o que o cliente ja recebeu do servidor e nao manda nada. Os jogadores de verdade ficam escondidos
 * enquanto o replay toca.
 */
public final class SteinReplay implements SteinMod {

    /** O maximo gravado, em segundos (o anel tem esse tamanho; a opcao escolhe quanto tocar). */
    static final int MAX_SECONDS = 30;
    /** Replays salvos guardados; passando disso, o mais velho sai. */
    static final int MAX_SAVED = 50;
    static final String EXT = ".steinreplay";

    static final class Config {
        boolean enabled = true;
        /** Quantos segundos tocar. */
        int seconds = 15;
        /** Salvar cada replay da morte. */
        boolean save = true;
    }

    private final ModContext ctx = ModContext.of(this);
    private final Log log = ctx.log();
    private final ModConfig<Config> config = ModConfig.of("steinreplay", Config.class);
    private final Recorder recorder = new Recorder(MAX_SECONDS * 20);

    private Session session;
    /** De onde veio o replay salvo que esta tocando (a lista), para voltar a ela. */
    private GuiScreen savedBack;
    /** Esta morte ja teve replay (ou foi pulada): a tela de morte que voltar nao abre outro. */
    private boolean shownThisDeath;
    /** A lista da pagina do Painel, lida quando ele abre. */
    private List<Recording> pageList = new ArrayList<>();

    Session session() {
        return session;
    }

    File replaysDir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "steinloader/replays");
    }

    @Override
    public void afterStartGame() {
        log.info("iniciado (" + (config.get().enabled ? "ligado" : "desligado") + ", " + saved().size()
                + " replay(s) salvo(s))");
    }

    @Override
    public void onJoinGame() {
        recorder.clear();
        if (dying >= 0) {
            dying = -1;
            timerSpeed(1);
        }
        shownThisDeath = false;
    }

    /**
     * {@code -Dsteinreplay.watchAtSec=N}: N segundos depois de abrir o jogo, no menu, assiste o replay salvo mais novo —
     * para conferir o mundo do replay sem clicar na lista.
     */
    private static final long WATCH_AT = Long.getLong("steinreplay.watchAtSec", -1L);
    private final long started = System.nanoTime();
    private boolean watchTested;

    @Override
    public void onTickEnd() {
        Minecraft mc = Minecraft.getMinecraft();
        if (session != null) {
            session.endHand();   // por garantia: a tela devolve o jogador logo depois da mao
        }
        if (WATCH_AT >= 0 && !watchTested && mc.theWorld == null
                && System.nanoTime() - started > WATCH_AT * 1_000_000_000L) {
            watchTested = true;
            List<File> fs = files();
            String why = fs.isEmpty() ? "nenhum replay salvo" : watch(fs.get(0), new ReplayList(this, null));
            log.info("teste de assistir: " + (why.isEmpty() ? fs.get(0).getName() : why));
        }
        if (mc.theWorld != lastWorld && (session == null || !session.saved)) {
            lastWorld = mc.theWorld;
            worldChanged(mc);
        }
        if (session != null && session.saved) {
            if (mc.theWorld == null) {
                session = null;   // o mundo do replay fechou por fora
                return;
            }
            session.tick();
            session.finished();
            return;
        }
        if (dying >= 0) {
            tickDying(mc);
            return;
        }
        if (session != null) {
            if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.getHealth() > 0
                    || mc.currentScreen != null && !(mc.currentScreen instanceof ReplayScreen)) {
                finish();   // renasceu, saiu do mundo ou outra tela entrou
                return;
            }
            mc.thePlayer.setPosition(deadX, deadY, deadZ);
            mc.thePlayer.motionX = 0;
            mc.thePlayer.motionY = 0;
            mc.thePlayer.motionZ = 0;
            // Os segundos depois da morte (o corpo caindo, os itens se espalhando) entram enquanto o replay ja toca.
            if (session.growing) {
                if (afterDeath < AFTER_DEATH) {
                    live.extend(mc);
                    afterDeath++;
                } else {
                    session.growing = false;
                }
            }
            session.tick();
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.getHealth() > 0) {
            shownThisDeath = false;
            recorder.record(mc);
            return;
        }
        // Morreu: a morte em camera lenta (o jogo como esta, sem mexer em nada), o escurecimento e o replay no lugar da
        // tela de morte. Sem gravacao que preste (acabou de entrar) ou no hardcore, fica a tela de morte do jogo.
        if (!shownThisDeath && config.get().enabled && recorder.size() >= MIN_RECORDED && recorder.hasSelf()
                && !mc.theWorld.getWorldInfo().isHardcoreModeEnabled()) {
            shownThisDeath = true;
            dying = 0;
            deadX = mc.thePlayer.posX;
            deadY = mc.thePlayer.posY;
            deadZ = mc.thePlayer.posZ;
            timerSpeed(DYING_SPEED);
            mc.displayGuiScreen(new DyingScreen(this));
            recorder.record(mc);
        }
    }

    /** O mundo do ultimo tick: trocou (saiu e voltou, outro servidor no mesmo proxy, outra dimensao), tudo recomeca. */
    private Object lastWorld;

    /**
     * Mundo novo: a gravacao do anterior nao vale mais, e a morte em camera lenta ou o replay que estavam no meio acabam
     * (com o jogador morto no mundo novo, a tela de morte do jogo abre sozinha).
     */
    private void worldChanged(Minecraft mc) {
        recorder.clear();
        shownThisDeath = false;
        if (dying >= 0) {
            dying = -1;
            timerSpeed(1);
            if (mc.currentScreen instanceof DyingScreen) {
                mc.displayGuiScreen(null);
            }
        }
        if (session != null) {
            finish();
        }
    }

    /**
     * Um tick da morte em camera lenta: o jogo segue gravando (o corpo caindo, os itens), a tela escurece no fim e o
     * replay entra. Se o jogador renascer antes (servidor que renasce sozinho), volta tudo.
     */
    private void tickDying(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.getHealth() > 0) {
            dying = -1;
            timerSpeed(1);
            if (mc.currentScreen instanceof DyingScreen) {
                mc.displayGuiScreen(null);
            }
            return;
        }
        recorder.record(mc);
        if (++dying >= DYING_TICKS) {
            dying = -1;
            timerSpeed(1);
            start(mc);
        }
    }

    /** O vermelho da morte agora (0 a 1): entra em dois ticks, para ver na hora que morreu (e nao parecer travamento). */
    float dyingRed(float partialTicks) {
        return dying < 0 ? 0 : Math.min(1, (dying + partialTicks) / 2F);
    }

    /** A fracao do escurecimento da morte agora (0 a 1), no quadro. */
    float dyingFade(float partialTicks) {
        return dying < 0 ? 1 : Math.max(0, Math.min(1, (dying + partialTicks - FADE_FROM) / (DYING_TICKS - FADE_FROM)));
    }

    /**
     * Monta o replay da morte e abre. Se algo der errado, a tela de morte do jogo (ela abre sozinha com o jogador morto e
     * nenhuma tela por cima).
     */
    private void start(Minecraft mc) {
        try {
            startReplay(mc);
        } catch (RuntimeException e) {
            log.error("replay da morte indisponivel, fica a tela de morte do jogo", e);
            Session s = session;
            session = null;
            live = null;
            if (s != null) {
                s.close(mc);
            }
            mc.displayGuiScreen(null);
        }
    }

    private void startReplay(Minecraft mc) {
        int want = Math.max(5, Math.min(MAX_SECONDS, config.get().seconds)) * 20;
        Recording r = Recording.capture(mc, recorder, Server.address());
        r.trimStart(Math.max(0, r.size() - DYING_TICKS - want));   // so o trecho que toca (e que vai para o disco)
        session = new Session(mc, r, false);
        r.setKiller(session.killerName);
        session.growing = true;
        session.fadeIn();
        live = r;
        afterDeath = DYING_TICKS;   // os ticks da morte em camera lenta ja estao gravados
        mc.displayGuiScreen(new ReplayScreen(this));
        log.info("replay da morte: " + r.size() / 20 + " s gravados"
                + (session.killerName != null ? ", por " + session.killerName : ""));
    }

    /** O minimo gravado para haver replay (acabou de entrar ou de trocar de mundo: fica a tela de morte do jogo). */
    private static final int MIN_RECORDED = 100;
    /** Ticks gravados depois da morte: o corpo caindo (um segundo) e os itens se espalhando. */
    private static final int AFTER_DEATH = 60;
    /** A morte em camera lenta: ticks de jogo, a velocidade do jogo nela e o tick em que comeca a escurecer. */
    private static final int DYING_TICKS = 30;
    private static final float DYING_SPEED = 0.4F;
    private static final int FADE_FROM = 21;
    /** Ticks da morte em camera lenta (-1: fora dela). */
    private int dying = -1;
    /** O replay da morte de agora (salvo no fim, com os segundos depois da morte) e quantos ticks ja entraram. */
    private Recording live;
    private int afterDeath;
    /** Onde voce morreu: o seu jogador fica ali enquanto o replay toca (como espectador ele nao colide e afundaria). */
    private double deadX;
    private double deadY;
    private double deadZ;
    private static java.lang.reflect.Field timerField;

    /** A velocidade do relogio do jogo (1 = normal): so o cliente anda devagar, o servidor segue igual. */
    private void timerSpeed(float speed) {
        try {
            if (timerField == null) {
                timerField = Minecraft.class.getDeclaredField("field_71428_T");
                timerField.setAccessible(true);
            }
            ((net.minecraft.util.Timer) timerField.get(Minecraft.getMinecraft())).timerSpeed = speed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("camera lenta indisponivel: " + e);
        }
    }

    /** Renascer (o botao do replay): o pedido vai ao servidor e o replay fecha quando o jogador volta vivo. */
    void respawn() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.respawnPlayer();
        }
    }

    /** Sair do servidor (o botao do replay): para a lista de servidores, ou o menu, num mundo local. */
    void leaveServer() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean local = mc.isIntegratedServerRunning();
        finish();
        if (mc.theWorld != null) {
            mc.theWorld.sendQuittingDisconnectingPacket();
        }
        mc.loadWorld(null);
        mc.displayGuiScreen(local ? new GuiMainMenu() : new GuiMultiplayer(new GuiMainMenu()));
    }

    /** Grava noutra thread (o jogo nao espera o disco) e tira os mais velhos que passam do limite. */
    private void saveLater(Recording r) {
        File f = new File(replaysDir(), new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(r.when)) + EXT);
        Thread t = new Thread(() -> {
            try {
                r.save(f);
                List<File> files = files();
                for (int i = MAX_SAVED; i < files.size(); i++) {
                    files.get(i).delete();
                }
                log.info("replay salvo: " + f.getName() + " (" + f.length() / 1024 + " KB)");
            } catch (Exception e) {
                log.error("nao salvou o replay " + f, e);
            }
        }, "Stein Replay");
        t.setDaemon(true);
        t.start();
    }

    /** Os arquivos de replay, do mais novo ao mais velho. */
    private List<File> files() {
        File[] fs = replaysDir().listFiles((d, n) -> n.endsWith(EXT));
        List<File> out = new ArrayList<>(fs == null ? List.of() : Arrays.asList(fs));
        out.sort(Comparator.comparing(File::getName).reversed());
        return out;
    }

    /** Os replays salvos (so o cabecalho de cada um), do mais novo ao mais velho. */
    List<Recording> saved() {
        List<Recording> out = new ArrayList<>();
        for (File f : files()) {
            try {
                out.add(Recording.header(f));
            } catch (Exception e) {
                log.warn("replay ilegivel: " + f.getName() + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * Assiste um replay salvo (fora de uma partida): monta o mundo dele e toca, e no fim volta para {@code back}.
     *
     * @return "" ou o motivo de nao abrir, para a lista mostrar
     */
    String watch(File f, GuiScreen back) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            return Lang.tr("steinreplay.leaveToWatch");
        }
        try {
            Recording r = Recording.load(f);
            if (r.size() < 2 || !ReplayWorld.open(mc, r, back)) {
                return Lang.tr("steinreplay.cantOpen");
            }
            savedBack = back;
            session = new Session(mc, r, true);
            mc.displayGuiScreen(new ReplayScreen(this));
            log.info("assistindo " + f.getName());
            return "";
        } catch (Exception e) {
            log.error("nao abriu o replay " + f, e);
            ReplayWorld.close(mc, back);
            return Lang.tr("steinreplay.cantOpen");
        }
    }

    /**
     * Fim do replay. Da morte (o jogador renasceu, saiu, ou outra tela entrou): camera e HUD de volta, e o replay e salvo.
     * Salvo: fecha o mundo do replay e volta para a lista.
     */
    void finish() {
        Minecraft mc = Minecraft.getMinecraft();
        Session s = session;
        session = null;
        if (s != null) {
            s.close(mc);
        }
        if (s != null && s.saved) {
            GuiScreen back = savedBack;
            savedBack = null;
            ReplayWorld.close(mc, back instanceof ReplayList ? new ReplayList(this, ((ReplayList) back).parent())
                    : back);
            return;
        }
        recorder.clear();
        if (live != null && config.get().save) {
            saveLater(live);   // com os segundos depois da morte que deu tempo de gravar
        }
        live = null;
        if (mc.currentScreen instanceof ReplayScreen) {
            mc.displayGuiScreen(null);
        }
    }

    /**
     * Enquanto o replay toca some todo o mundo de verdade (jogadores, mobs, itens, o que ficou no lugar depois da morte)
     * e ficam so as copias do replay.
     */
    @Override
    public boolean onHideEntity(Object entity) {
        return session != null && entity instanceof net.minecraft.entity.Entity && !session.isActor(entity);
    }

    /**
     * Cada som do mundo (com posicao, sem repetir) entra na gravacao; enquanto o replay toca, os do mundo de verdade
     * ficam mudos (a musica e os cliques de tela seguem) e so tocam os gravados.
     */
    @Override
    public Object onPlaySound(Object sound) {
        if (!(sound instanceof ISound s) || ParticleTap.replaying) {
            return sound;
        }
        boolean world = s.getAttenuationType() == ISound.AttenuationType.LINEAR;
        if (world && !s.canRepeat() && s.getSoundLocation() != null) {
            ParticleTap.sound(s.getSoundLocation().toString(), s.getXPosF(), s.getYPosF(), s.getZPosF(), s.getVolume(),
                    s.getPitch());
        }
        return world && session != null ? null : sound;
    }

    @Override
    public void onRenderWorld(float partialTicks) {
        if (session != null) {
            session.render(Minecraft.getMinecraft(), partialTicks);
        }
    }

    @Override
    public void onShutdown() {
        config.save();
    }

    /** O Configurar do menu do Stein Loader: a lista dos replays salvos. */
    @Override
    public Object configScreen(Object parent) {
        return new ReplayList(this, (GuiScreen) parent);
    }

    @Override
    public void onPage(Page page) {
        Config c = config.get();
        page.icon("loop");
        page.section(tr("steinreplay.title"));
        page.option(Option.toggle(tr("steinreplay.enabled"), () -> c.enabled, v -> {
            c.enabled = v;
            config.saveSoon();
        }));
        page.option(Option.slider(tr("steinreplay.seconds"), 5, MAX_SECONDS, 5, () -> c.seconds, v -> {
            c.seconds = (int) Math.round(v);
            config.saveSoon();
        }, v -> Math.round(v) + " s"));
        page.option(Option.toggle(tr("steinreplay.save"), () -> c.save, v -> {
            c.save = v;
            config.saveSoon();
        }));
        page.restore(() -> {
            Config d = new Config();
            c.enabled = d.enabled;
            c.seconds = d.seconds;
            c.save = d.save;
            config.save();
        });

        // Os salvos: clicar diz onde assistir (fora da partida), a lixeira apaga.
        page.section(tr("steinreplay.saved"));
        pageList = saved();
        page.rows(() -> {
            List<Page.Item> rows = new ArrayList<>();
            for (Recording r : pageList) {
                String when = new SimpleDateFormat("dd/MM HH:mm").format(new Date(r.when));
                String title = when + (r.server.isEmpty() ? "" : "  §7" + r.server);
                String detail = (r.killer.isEmpty() ? "" : r.killer + " · ") + r.length / 20 + " s";
                rows.add(new Page.Item(title, detail, false, () -> Chat.print("§7" + tr("steinreplay.watchHint")),
                        () -> {
                            if (r.file.delete()) {
                                pageList.remove(r);
                            }
                        }));
            }
            return rows;
        }, tr("steinreplay.none"));
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}
