package dev.lyricraft.hud;

import dev.lyricraft.Config;
import dev.lyricraft.online.OnlineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Other Lyricraft players' Echoes panels, drawn in the same spot in the world where they placed
 * them, with a small "♫ name" tag and their own colour.
 *
 * Limits: only players whose character is loaded near you (same world, same dimension, within
 * 48 blocks), at most 12 panels drawn, and they never sit on top of your own panels, each other,
 * the Now Playing card or the hotbar: a panel that would overlap slides up or down until it fits,
 * and is hidden if there's no room.
 */
public final class RemoteEchoes {

	private static final double MAX_DIST = 48;
	private static final int MAX_DRAWN = 12;
	private static final float TEXT_BLOCKS = 0.16f, PAD = 10f;

	/** Smoothed vertical nudge per panel (screen pixels), so panels glide out of each other's way. */
	private final Map<String, float[]> lift = new HashMap<>();
	/** Where panels ended up last frame (for tests). */
	private static volatile List<float[]> lastPlaced = List.of();

	public static List<float[]> placedLastFrame() {
		return lastPlaced;
	}

	public void render(GuiGraphicsExtractor g, Font font, Minecraft mc, Projector proj, Config cfg, OnlineClient online,
	                   float dt, float alpha) {
		if (!cfg.showOthersEchoes || mc.level == null || !proj.valid()) return;
		List<OnlineClient.RemoteEcho> all = online.echoes();
		if (all.isEmpty()) {
			lift.clear();
			lastPlaced = List.of();
			return;
		}
		String dim = mc.level.dimension().identifier().toString();
		Vec3 cam = proj.cameraPos();
		Map<UUID, AbstractClientPlayer> here = new HashMap<>();
		for (AbstractClientPlayer p : mc.level.players()) here.put(p.getUUID(), p);
		boolean demo = online.demoActive();

		List<OnlineClient.RemoteEcho> visible = new ArrayList<>();
		for (OnlineClient.RemoteEcho e : all) {
			if (!e.dim().equals(dim)) continue;
			AbstractClientPlayer owner = here.get(e.owner());
			if (owner == null && !demo) continue;                  // only players actually loaded near you
			if (owner == mc.player && !demo) continue;             // your own are drawn by the Echoes style
			if (cam.distanceTo(new Vec3(e.x(), e.y(), e.z())) > MAX_DIST) continue;
			visible.add(e);
		}
		visible.sort(Comparator.comparingDouble(e -> cam.distanceToSqr(e.x(), e.y(), e.z())));
		if (visible.size() > MAX_DRAWN) visible = visible.subList(0, MAX_DRAWN);

		List<float[]> placed = new ArrayList<>();
		Map<String, float[]> seen = new HashMap<>();
		for (OnlineClient.RemoteEcho e : visible) {
			String key = e.owner() + "@" + e.receivedNanos();
			float[] state = lift.getOrDefault(key, new float[]{0f, 1f}); // {lift px, overlap fade}
			seen.put(key, state);
			draw(g, font, mc, proj, e, state, dt, alpha, placed);
		}
		lastPlaced = List.copyOf(placed);
		lift.keySet().retainAll(seen.keySet());
		lift.putAll(seen);
	}

	private void draw(GuiGraphicsExtractor g, Font font, Minecraft mc, Projector proj, OnlineClient.RemoteEcho e,
	                  float[] state, float dt, float alpha, List<float[]> placed) {
		Projector.Point pt = proj.project(new Vec3(e.x(), e.y(), e.z()));
		if (pt == null || pt.pxPerBlock() <= 0 || !proj.onScreen(pt, 200)) return;
		float scale = Math.min(3.5f, pt.pxPerBlock() * TEXT_BLOCKS / font.lineHeight);
		if (scale < 0.15f) return;

		String text = e.line();
		int textW = Math.max(font.width(text), 40);
		scale = Draw.fitScale(textW, scale, g.guiWidth() * 0.8f);
		float lh = font.lineHeight * scale;
		float w = (textW + PAD * 2) * scale, h = lh * 2.3f;

		// find a free spot: stay put, or slide up/down by one panel height until nothing's in the way
		float target = 0f;
		boolean free = false;
		float[] tries = {0f, -1f, 1f, -2f, 2f, -3f};
		for (float t : tries) {
			float dy = t * (h + 4);
			float y0 = pt.y() - h / 2f + dy;
			if (!HudSpace.overlaps(pt.x() - w / 2f, y0, pt.x() + w / 2f, y0 + h)) {
				target = dy;
				free = true;
				break;
			}
		}
		state[0] = Draw.approach(state[0], target, dt * 400f);
		state[1] = Draw.approach(state[1], free ? 1f : 0f, dt * 4f);

		float age = e.ageSeconds();
		float fadeIn = Math.clamp(age / 0.3f, 0f, 1f);
		float fadeOut = Math.clamp((OnlineClient.ECHO_LIFETIME_S - age) / 4f, 0f, 1f);
		long elapsed = e.elapsedNow();
		boolean current = elapsed >= 0 && elapsed < e.durMs() + 600;
		float vis = alpha * fadeIn * fadeOut * state[1] * (current ? 0.95f : 0.6f);
		if (vis < 0.02f) return;

		float x0 = pt.x() - w / 2f, y0 = pt.y() - h / 2f + state[0];
		int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x0 + w), iy1 = Math.round(y0 + h);
		if (free) {
			HudSpace.reserve(ix0 - 2, iy0 - 2, ix1 + 2, iy1 + 2);
			if (Math.abs(state[0] - target) < 1f) placed.add(new float[]{ix0, iy0, ix1, iy1});
		}

		// each friend gets their own colour
		long mix = (e.owner().getMostSignificantBits() ^ e.owner().getLeastSignificantBits()) * 0x9E3779B97F4A7C15L;
		int col = Draw.hsv((int) ((mix >>> 40) % 360), 0.55f, 1f);
		g.fillGradient(ix0, iy0, ix1, iy1, Draw.argb(Draw.mix(col, 0x05070A, 0.82f), 0.6f * vis), Draw.argb(0x05070A, 0.38f * vis));
		g.fill(ix0, iy0, ix1, iy0 + 1, Draw.argb(col, 0.8f * vis));
		g.fill(ix0, iy1 - 1, ix1, iy1, Draw.argb(col, 0.5f * vis));
		int c = Math.max(3, Math.round(5 * scale));
		g.fill(ix0, iy0, ix0 + c, iy0 + 2, Draw.argb(0xFFFFFF, 0.7f * vis));
		g.fill(ix0, iy0, ix0 + 2, iy0 + c, Draw.argb(0xFFFFFF, 0.7f * vis));
		g.fill(ix1 - c, iy1 - 2, ix1, iy1, Draw.argb(0xFFFFFF, 0.7f * vis));
		g.fill(ix1 - 2, iy1 - c, ix1, iy1, Draw.argb(0xFFFFFF, 0.7f * vis));

		// "♫ name" tag on the top edge
		String name = nameOf(mc, e.owner());
		float ts = Math.max(0.4f, scale * 0.5f);
		Draw.text(g, font, "♫ " + name, ix0 + 3 * scale, iy0 + 2 * scale, ts, Draw.argb(col, 0.9f * vis), false);

		float tw = font.width(text) * scale;
		float tx = pt.x() - tw / 2f, ty = y0 + (h - lh) / 2f + scale * 2f;
		float progress = e.durMs() <= 0 ? 1f : Math.clamp(elapsed / (float) e.durMs(), 0f, 1f);
		Draw.karaoke(g, font, text, tx, ty, scale, progress, vis, col, 0xE8E8F0, 0.55f, false, current ? 0.6f : 0f);
	}

	private static String nameOf(Minecraft mc, UUID id) {
		var conn = mc.getConnection();
		PlayerInfo info = conn == null ? null : conn.getPlayerInfo(id);
		String n = info == null ? null : info.getProfile().name();
		return n == null || n.isBlank() ? "friend" : n;
	}
}
