package dev.lyricraft.hud;

import dev.lyricraft.Config;
import dev.lyricraft.online.OnlineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The line other Lyricraft players are hearing right now, floating above their head, with the
 * song underneath. Karaoke-filled in time with them. Your own shows too in third person.
 */
public final class OverheadLyrics {

	private static final double MAX_DIST = 32, FADE_FROM = 20;
	/** How high above the head (blocks), clear of the name tag. */
	private static final double ABOVE_HEAD = 0.85;
	private static final float TEXT_BLOCKS = 0.14f;

	public void render(GuiGraphicsExtractor g, Font font, Minecraft mc, Projector proj, Config cfg, OnlineClient online,
	                   float partialTick, int accent) {
		if (mc.level == null || mc.player == null || !proj.valid()) return;
		if (!cfg.showOthersLyrics && mc.options.getCameraType().isFirstPerson()) return;
		Vec3 cam = proj.cameraPos();
		for (AbstractClientPlayer p : mc.level.players()) {
			boolean self = p == mc.player;
			if (self && mc.options.getCameraType().isFirstPerson()) continue;
			if (!self && !cfg.showOthersLyrics) continue;
			if (p.isInvisible() || p.isDiscrete()) continue; // like name tags: crouching hides it
			OnlineClient.Remote r = online.remote(p.getUUID());
			if (r == null || (r.song() == null && r.line() == null)) continue;

			Vec3 head = p.getPosition(partialTick).add(0, p.getBbHeight() + ABOVE_HEAD, 0);
			double dist = head.distanceTo(cam);
			if (dist > MAX_DIST) continue;
			float alpha = (float) Math.clamp(1 - (dist - FADE_FROM) / (MAX_DIST - FADE_FROM), 0, 1);
			Projector.Point pt = proj.project(head);
			if (pt == null || pt.pxPerBlock() <= 0 || !proj.onScreen(pt, 120)) continue;
			float scale = Math.min(2.6f, pt.pxPerBlock() * TEXT_BLOCKS / font.lineHeight);
			if (scale < 0.2f) continue;

			float y = pt.y();
			// current line (only while it's being sung). Players on Echoes already show their lines as
			// panels in the world, so it'd just be doubled up here; then only the song name shows.
			boolean asPanels = self ? cfg.style == Config.Style.ECHOES : r.echoes() && cfg.showOthersEchoes;
			long elapsed = r.elapsedNow();
			if (!asPanels && r.line() != null && r.durMs() > 0 && elapsed < r.durMs() + 1500) {
				String line = r.line();
				float s = Draw.fitScale(font.width(line), scale, g.guiWidth() * 0.6f);
				float w = font.width(line) * s;
				float progress = Math.clamp(elapsed / (float) r.durMs(), 0f, 1f);
				float fade = alpha * Math.clamp(1 - (elapsed - r.durMs()) / 1500f, 0f, 1f);
				Draw.karaoke(g, font, line, pt.x() - w / 2f, y - font.lineHeight * s, s, progress, fade,
						accent, 0xFFFFFF, 0.6f, true, 0.4f);
			}
			// song name underneath
			if (r.song() != null) {
				String song = "♫ " + r.song() + (r.artist() == null || r.artist().isBlank() ? "" : " – " + r.artist());
				float s = scale * 0.7f;
				song = Draw.ellipsize(font, song, Math.round(g.guiWidth() * 0.5f / s));
				float w = font.width(song) * s;
				Draw.text(g, font, song, pt.x() - w / 2f, y + 3 * scale, s, Draw.argb(0xD0D0D8, 0.85f * alpha), true);
			}
		}
	}
}
