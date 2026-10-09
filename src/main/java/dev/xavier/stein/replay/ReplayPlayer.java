/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.replay;

import com.mojang.authlib.GameProfile;
import dev.xavier.stein.loader.api.Render;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/**
 * Um jogador do replay, com a skin pelo nick como no Tab ({@link Render#playerSkin}): a que o servidor mandou e, num
 * servidor sem autenticacao, a da conta original com aquele nick. O jogo sozinho so acharia a skin pelo UUID na lista
 * de jogadores — e no replay o "voce" gravado tem outro UUID, e num servidor offline ninguem tem skin.
 */
final class ReplayPlayer extends EntityOtherPlayerMP {

    private final String nick;
    /** O tempo de morte (ticks, com fracao) no estado de antes e no de agora: o tombo sai liso em camera lenta. */
    float deathPrev;
    float deathNow;

    ReplayPlayer(World world, GameProfile profile, String nick) {
        super(world, profile);
        this.nick = nick;
    }

    @Override
    public ResourceLocation getLocationSkin() {
        return skin(nick, super.getLocationSkin());
    }

    /** O desenho do corpo do replay (o {@link Body}), quando registrado; senao o do jogo. */
    @Override
    public String getSkinType() {
        String t = super.getSkinType();
        return registered ? PREFIX + t : t;
    }

    private static final String PREFIX = "stein_replay_";
    private static boolean registered;

    /**
     * Poe no jogo os desenhos de corpo do replay, um para cada modelo de braco (largo e fino), pelo tipo de skin que o
     * {@link #getSkinType} devolve. Uma vez so.
     */
    @SuppressWarnings("unchecked")
    static void register(Minecraft mc) {
        if (registered) {
            return;
        }
        try {
            java.lang.reflect.Field f = RenderManager.class.getDeclaredField("field_178636_l");
            f.setAccessible(true);
            java.util.Map<String, RenderPlayer> skins =
                    (java.util.Map<String, RenderPlayer>) f.get(mc.getRenderManager());
            skins.put(PREFIX + "default", new Body(mc.getRenderManager(), false));
            skins.put(PREFIX + "slim", new Body(mc.getRenderManager(), true));
            registered = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.out.println("[steinreplay] corpo do replay indisponivel: " + e);
        }
    }

    /**
     * O corpo de um jogador do replay. O jogo tomba o corpo morto por um contador inteiro de ticks mais a fracao do
     * quadro; com o replay em camera lenta o contador anda meio tick por tick e o tombo saia aos trancos. Aqui o tombo
     * vem do tempo de morte continuo do replay. O resto e o desenho do jogo.
     */
    static final class Body extends RenderPlayer {

        Body(RenderManager manager, boolean slim) {
            super(manager, slim);
        }

        @Override
        protected void rotateCorpse(AbstractClientPlayer p, float age, float yaw, float partialTicks) {
            if (!(p instanceof ReplayPlayer r) || r.deathNow <= 0) {
                super.rotateCorpse(p, age, yaw, partialTicks);
                return;
            }
            GlStateManager.rotate(180.0F - yaw, 0.0F, 1.0F, 0.0F);
            float d = r.deathPrev + (r.deathNow - r.deathPrev) * partialTicks;
            float f = (float) Math.sqrt(Math.max(0, (d - 1) / 20 * 1.6F));
            GlStateManager.rotate(Math.min(1, f) * getDeathMaxRotation(p), 0.0F, 0.0F, 1.0F);
        }
    }

    static ResourceLocation skin(String nick, ResourceLocation fallback) {
        Object r = nick == null ? null : Render.playerSkin(nick);
        return r instanceof ResourceLocation l ? l : fallback;
    }

    /** O jogador do cliente no lugar de quem te matou, enquanto a mao dele e desenhada: o braco com a skin dele. */
    static final class Hand extends EntityPlayerSP {

        String nick;

        Hand(Minecraft mc, World world) {
            super(mc, world, mc.getNetHandler(), mc.thePlayer.getStatFileWriter());
        }

        @Override
        public ResourceLocation getLocationSkin() {
            return skin(nick, super.getLocationSkin());
        }
    }
}
