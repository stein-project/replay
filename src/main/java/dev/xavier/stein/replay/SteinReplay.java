/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.ModConfig;
import dev.xavier.stein.loader.api.ModContext;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Server;
import dev.xavier.stein.loader.api.SteinMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;

import java.io.File;
import java.util.List;

/**
 * Death replay: the game always keeps the last seconds of the players around ({@link Recorder}).
 * When you die, the game goes on in slow motion for a few seconds, as it was ({@link
 * DeathSlowMotion}, {@link DyingScreen}), fades to black, and the replay takes the death screen's
 * place ({@link Session}, {@link ReplayScreen}): it loops until you respawn, with the respawn and
 * leave-server buttons.
 *
 * <p>Every death replay is saved ({@link SavedReplays}, {@link ReplayFile}: the frames and the
 * blocks around) in {@code steinloader/replays}. From the Stein Loader menu (Configure, on Replay)
 * they show in a list ({@link ReplayList}) to watch again — in a world built only from those blocks
 * ({@link ReplayWorld}) — or delete; the Panel page lists and deletes them too ({@link
 * ReplayPage}).
 *
 * <p>It is visual only: it reads what the client already received from the server and sends
 * nothing. The real players are hidden while the replay plays.
 */
public final class SteinReplay implements SteinMod {

    /** The most recorded, in seconds (the ring's size; the option picks how much to play). */
    static final int MAX_SECONDS = 30;

    /** The least recorded for a replay (just joined or changed worlds: the game's death screen). */
    private static final int MIN_RECORDED = 100;

    /** Ticks recorded after the death: the body falling (one second) and the items scattering. */
    private static final int AFTER_DEATH = 60;

    /**
     * {@code -Dsteinreplay.watchAtSec=N}: N seconds after the game opens, on the menu, watches the
     * newest saved replay — to check the replay world without clicking the list.
     */
    private static final long WATCH_AT = Long.getLong("steinreplay.watchAtSec", -1L);

    private final ModContext ctx = ModContext.of(this);
    private final Log log = ctx.log();
    private final ModConfig<ReplayConfig> config = ctx.config(ReplayConfig.class);
    private final Recorder recorder = new Recorder(MAX_SECONDS * 20);
    private final SavedReplays files = new SavedReplays(log);
    private final ReplayPage page = new ReplayPage(config, files);
    private final DeathSlowMotion dying = new DeathSlowMotion();
    private final long started = System.nanoTime();

    private Session session;

    /** Where the saved replay playing came from (the list), to go back to it. */
    private GuiScreen savedBack;

    /** This death already had its replay (or skipped it): a death screen coming back opens none. */
    private boolean shownThisDeath;

    /** The world of the last tick: when it changes, everything starts over. */
    private Object lastWorld;

    /** The current death replay (saved at the end, with the seconds after the death). */
    private Recording live;

    /** Ticks of {@link #live} recorded after the death. */
    private int afterDeath;

    /** Where you died: your player stays there while the replay plays (a spectator would sink). */
    private double deadX;

    private double deadY;
    private double deadZ;
    private boolean watchTested;

    Session session() {
        return session;
    }

    SavedReplays files() {
        return files;
    }

    @Override
    public void afterStartGame() {
        log.info(
                "started ("
                        + (config.get().enabled ? "on" : "off")
                        + ", "
                        + files.saved().size()
                        + " saved replay(s))");
    }

    @Override
    public void onJoinGame() {
        recorder.clear();
        if (dying.active()) {
            dying.stop(Minecraft.getMinecraft());
        }
        shownThisDeath = false;
    }

    @Override
    public void onTickEnd() {
        Minecraft mc = Minecraft.getMinecraft();
        if (session != null) {
            session.endHand(); // just in case: the screen gives the player back right after the
            // hand
        }
        watchTest(mc);
        if (mc.theWorld != lastWorld && (session == null || !session.saved)) {
            lastWorld = mc.theWorld;
            worldChanged(mc);
        }
        if (session != null && session.saved) {
            if (mc.theWorld == null) {
                session = null; // the replay's world was closed from outside
                return;
            }
            session.tick();
            session.finished();
            return;
        }
        if (dying.active()) {
            tickDying(mc);
            return;
        }
        if (session != null) {
            tickDeathReplay(mc);
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
        // Died: the death in slow motion (the game as it is, nothing touched), the fade and the
        // replay in place of the death screen. Without a usable recording (just joined) or in
        // hardcore, the game's death screen stays.
        if (!shownThisDeath
                && config.get().enabled
                && recorder.size() >= MIN_RECORDED
                && recorder.hasSelf()
                && !mc.theWorld.getWorldInfo().isHardcoreModeEnabled()) {
            shownThisDeath = true;
            deadX = mc.thePlayer.posX;
            deadY = mc.thePlayer.posY;
            deadZ = mc.thePlayer.posZ;
            dying.start(mc);
            mc.displayGuiScreen(new DyingScreen(dying));
            recorder.record(mc);
        }
    }

    /** {@link #WATCH_AT}: once, on the menu. */
    private void watchTest(Minecraft mc) {
        if (WATCH_AT < 0
                || watchTested
                || mc.theWorld != null
                || System.nanoTime() - started <= WATCH_AT * 1_000_000_000L) {
            return;
        }
        watchTested = true;
        List<File> fs = files.list();
        String why =
                fs.isEmpty() ? "no saved replay" : watch(fs.get(0), new ReplayList(this, null));
        log.info("watch test: " + (why.isEmpty() ? fs.get(0).getName() : why));
    }

    /**
     * A tick of the death replay: it ends when the player respawns, leaves or another screen opens.
     */
    private void tickDeathReplay(Minecraft mc) {
        if (mc.thePlayer == null
                || mc.theWorld == null
                || mc.thePlayer.getHealth() > 0
                || mc.currentScreen != null && !(mc.currentScreen instanceof ReplayScreen)) {
            finish();
            return;
        }
        mc.thePlayer.setPosition(deadX, deadY, deadZ);
        mc.thePlayer.motionX = 0;
        mc.thePlayer.motionY = 0;
        mc.thePlayer.motionZ = 0;
        // The seconds after the death (the body falling, the items scattering) come in while the
        // replay already plays.
        if (session.growing) {
            if (afterDeath < AFTER_DEATH) {
                live.extend(mc);
                afterDeath++;
            } else {
                session.growing = false;
            }
        }
        session.tick();
    }

    /**
     * A new world: the previous one's recording no longer applies, and the slow-motion death or the
     * replay in progress end (with the player dead in the new world, the game's death screen opens
     * by itself).
     */
    private void worldChanged(Minecraft mc) {
        recorder.clear();
        shownThisDeath = false;
        if (dying.active()) {
            dying.stop(mc);
            if (mc.currentScreen instanceof DyingScreen) {
                mc.displayGuiScreen(null);
            }
        }
        if (session != null) {
            finish();
        }
    }

    /**
     * A tick of the slow-motion death: the game keeps recording (the body falling, the items), the
     * screen fades at the end and the replay comes in. If the player respawns before (a server that
     * respawns by itself), everything goes back.
     */
    private void tickDying(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.getHealth() > 0) {
            dying.stop(mc);
            if (mc.currentScreen instanceof DyingScreen) {
                mc.displayGuiScreen(null);
            }
            return;
        }
        recorder.record(mc);
        if (dying.tick(mc)) {
            start(mc);
        }
    }

    /**
     * Builds the death replay and opens it. If anything goes wrong, the game's death screen (it
     * opens by itself with the player dead and no screen on top).
     */
    private void start(Minecraft mc) {
        try {
            startReplay(mc);
        } catch (RuntimeException e) {
            log.error("death replay unavailable, the game's death screen stays", e);
            Session s = session;
            session = null;
            live = null;
            if (s != null) {
                s.close();
            }
            mc.displayGuiScreen(null);
        }
    }

    private void startReplay(Minecraft mc) {
        int want = Math.max(5, Math.min(MAX_SECONDS, config.get().seconds)) * 20;
        Recording r = Recording.capture(mc, recorder, Server.address());
        // Only the stretch that plays (and goes to disk).
        r.trimStart(Math.max(0, r.size() - DeathSlowMotion.TICKS - want));
        session = new Session(mc, r, false, log);
        r.setKiller(session.killerName);
        session.growing = true;
        session.fadeIn();
        live = r;
        afterDeath = DeathSlowMotion.TICKS; // the slow-motion ticks are already recorded
        mc.displayGuiScreen(new ReplayScreen(this));
        log.info(
                "death replay: "
                        + r.size() / 20
                        + " s recorded"
                        + (session.killerName != null ? ", by " + session.killerName : ""));
    }

    /**
     * Respawn (the replay's button): the request goes to the server, the replay closes when alive.
     */
    void respawn() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.respawnPlayer();
        }
    }

    /** Leave the server (the replay's button): to the server list, or the menu in a local world. */
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

    /**
     * Watches a saved replay (outside a match): builds its world and plays it, and at the end goes
     * back to {@code back}.
     *
     * @return "" or why it did not open, for the list to show
     */
    String watch(File f, GuiScreen back) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            return Lang.tr("steinreplay.leaveToWatch");
        }
        try {
            Recording r = ReplayFile.load(f);
            if (r.size() < 2 || !ReplayWorld.open(mc, r, back)) {
                return Lang.tr("steinreplay.cantOpen");
            }
            savedBack = back;
            session = new Session(mc, r, true, log);
            mc.displayGuiScreen(new ReplayScreen(this));
            log.info("watching " + f.getName());
            return "";
        } catch (Exception e) {
            log.error("could not open the replay " + f, e);
            ReplayWorld.close(mc, back);
            return Lang.tr("steinreplay.cantOpen");
        }
    }

    /**
     * End of the replay. A death replay (the player respawned, left, or another screen opened):
     * camera and HUD back, and the replay is saved. A saved one: closes the replay's world and goes
     * back to the list.
     */
    void finish() {
        Minecraft mc = Minecraft.getMinecraft();
        Session s = session;
        session = null;
        if (s != null) {
            s.close();
        }
        if (s != null && s.saved) {
            GuiScreen back = savedBack;
            savedBack = null;
            ReplayWorld.close(
                    mc,
                    back instanceof ReplayList list ? new ReplayList(this, list.parent()) : back);
            return;
        }
        recorder.clear();
        if (live != null && config.get().save) {
            files.saveLater(live); // with the seconds after the death there was time to record
        }
        live = null;
        if (mc.currentScreen instanceof ReplayScreen) {
            mc.displayGuiScreen(null);
        }
    }

    /**
     * While the replay plays the whole real world goes away (players, mobs, items, what stayed in
     * place after the death) and only the replay's copies remain.
     */
    @Override
    public boolean onHideEntity(Object entity) {
        return session != null && entity instanceof Entity && !session.isActor(entity);
    }

    /**
     * Every world sound (with a position, not repeating) goes into the recording; while the replay
     * plays, the real world's are muted (the music and screen clicks go on) and only the recorded
     * ones play.
     */
    @Override
    public Object onPlaySound(Object sound) {
        if (!(sound instanceof ISound s) || ParticleTap.replaying) {
            return sound;
        }
        boolean world = s.getAttenuationType() == ISound.AttenuationType.LINEAR;
        if (world && !s.canRepeat() && s.getSoundLocation() != null) {
            ParticleTap.sound(
                    s.getSoundLocation().toString(),
                    s.getXPosF(),
                    s.getYPosF(),
                    s.getZPosF(),
                    s.getVolume(),
                    s.getPitch());
        }
        return world && session != null ? null : sound;
    }

    @Override
    public void onRenderWorld(float partialTicks) {
        if (session != null) {
            session.render();
        }
    }

    @Override
    public void onShutdown() {
        config.save();
    }

    /** Configure, on the Stein Loader menu: the list of saved replays. */
    @Override
    public Object configScreen(Object parent) {
        return new ReplayList(this, (GuiScreen) parent);
    }

    @Override
    public void onPage(Page page) {
        this.page.build(page);
    }
}
