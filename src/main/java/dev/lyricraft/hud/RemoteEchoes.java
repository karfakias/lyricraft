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
 * Kept tidy on purpose, since a few friends singing at once gets busy fast:
 * - per friend only the line they're on now, plus the one before it (smaller and dimmer, and
 *   gone a few seconds after the new one shows up)
 * - only friends whose character is loaded near you (same dimension, within 48 blocks), fading
 *   out with distance, at most 8 panels in total with current lines first
 * - panels never sit on your own panels, each other, the Now Playing card or the hotbar: they
 *   slide up, down or sideways to a free spot and stay there, or hide if there's no room
 */
public final class RemoteEchoes {

	private static final double MAX_DIST = 48, FADE_FROM = 32, TOO_CLOSE = 1.6;
	private static final int MAX_DRAWN = 8;
	private static final float TEXT_BLOCKS = 0.15f, PAD = 10f;
	/** How long the previous line stays after a new one arrives, and how long a line stays at all. */
	private static final float PREVIOUS_S = 6f, LINGER_S = 20f;
	/** Spots to try, in panel widths (x) and heights (y): stay, up, down, sideways, further up/down. */
	private static final float[][] SPOTS = {
			{0, 0}, {0, -1}, {0, 1}, {-0.65f, 0}, {0.65f, 0}, {-0.65f, -1}, {0.65f, -1}, {0, -2}, {0, 2},
	};

	/** Per panel: {x offset px, y offset px, fade, chosen spot}. Smoothed so panels glide, not jump. */
	private final Map<String, float[]> slots = new HashMap<>();
	/** Where panels ended up last frame (for tests). */
	private static volatile List<float[]> lastPlaced = List.of();

	public static List<float[]> placedLastFrame() {
		return lastPlaced;
	}

	private record Item(OnlineClient.RemoteEcho echo, boolean current, float since, double dist) {}

	public void render(GuiGraphicsExtractor g, Font font, Minecraft mc, Projector proj, Config cfg, OnlineClient online,
	                   float dt, float alpha) {
		if (!cfg.showOthersEchoes || mc.level == null || !proj.valid()) return;
		List<OnlineClient.RemoteEcho> all = online.echoes();
		if (all.isEmpty()) {
			slots.clear();
			lastPlaced = List.of();
			return;
		}
		String dim = mc.level.dimension().identifier().toString();
		Vec3 cam = proj.cameraPos();
		Map<UUID, AbstractClientPlayer> here = new HashMap<>();
		for (AbstractClientPlayer p : mc.level.players()) here.put(p.getUUID(), p);
		boolean demo = online.demoActive();

		// newest first per friend
		Map<UUID, List<OnlineClient.RemoteEcho>> byOwner = new HashMap<>();
		for (OnlineClient.RemoteEcho e : all) {
			if (!e.dim().equals(dim)) continue;
			AbstractClientPlayer owner = here.get(e.owner());
			if (owner == null && !demo) continue;                  // only players actually loaded near you
			if (owner == mc.player && !demo) continue;             // your own are drawn by the Echoes style
			byOwner.computeIfAbsent(e.owner(), k -> new ArrayList<>()).add(e);
		}
		List<Item> items = new ArrayList<>();
		for (List<OnlineClient.RemoteEcho> list : byOwner.values()) {
			list.sort(Comparator.comparingLong(OnlineClient.RemoteEcho::receivedNanos).reversed());
			for (int i = 0; i < Math.min(2, list.size()); i++) {
				OnlineClient.RemoteEcho e = list.get(i);
				double d = cam.distanceTo(new Vec3(e.x(), e.y(), e.z()));
				if (d > MAX_DIST) continue;
				// the previous line counts from the moment the newer one arrived
				float since = i == 0 ? e.ageSeconds() : list.getFirst().ageSeconds();
				items.add(new Item(e, i == 0, since, d));
			}
		}
		// current lines first, nearest first; that's also the order they get to pick spots in
		items.sort(Comparator.comparing((Item it) -> !it.current()).thenComparingDouble(Item::dist));
		if (items.size() > MAX_DRAWN) items = items.subList(0, MAX_DRAWN);

		List<float[]> placed = new ArrayList<>();
		Map<String, float[]> seen = new HashMap<>();
		for (Item it : items) {
			String key = it.echo().owner() + "@" + it.echo().receivedNanos();
			float[] state = slots.getOrDefault(key, new float[]{0f, 0f, 0f, -1f});
			seen.put(key, state);
			draw(g, font, mc, proj, it, state, dt, alpha, placed);
		}
		lastPlaced = List.copyOf(placed);
		slots.keySet().retainAll(seen.keySet());
		slots.putAll(seen);
	}

	private void draw(GuiGraphicsExtractor g, Font font, Minecraft mc, Projector proj, Item it,
	                  float[] state, float dt, float alpha, List<float[]> placed) {
		OnlineClient.RemoteEcho e = it.echo();
		Projector.Point pt = proj.project(new Vec3(e.x(), e.y(), e.z()));
		if (pt == null || pt.pxPerBlock() <= 0 || !proj.onScreen(pt, 200)) return;
		float scale = Math.min(2.6f, pt.pxPerBlock() * TEXT_BLOCKS / font.lineHeight) * (it.current() ? 1f : 0.82f);
		if (scale < 0.15f) return;

		String text = e.line();
		int textW = Math.max(font.width(text), 40);
		scale = Draw.fitScale(textW, scale, g.guiWidth() * 0.7f);
		float lh = font.lineHeight * scale;
		float w = (textW + PAD * 2) * scale, h = lh * 2.3f;

		// find a free spot; keep the one we had if it's still free, so panels don't hop around
		int chosen = -1;
		int prev = Math.round(state[3]);
		if (prev >= 0 && fits(pt, w, h, prev)) chosen = prev;
		for (int i = 0; chosen < 0 && i < SPOTS.length; i++) if (fits(pt, w, h, i)) chosen = i;
		boolean free = chosen >= 0;
		if (free) state[3] = chosen;
		float tx = free ? SPOTS[chosen][0] * w : state[0], ty = free ? SPOTS[chosen][1] * (h + 4) : state[1];
		boolean firstFrame = state[2] <= 0f && prev < 0;
		state[0] = firstFrame ? tx : Draw.approach(state[0], tx, dt * 420f);
		state[1] = firstFrame ? ty : Draw.approach(state[1], ty, dt * 420f);
		state[2] = Draw.approach(state[2], free ? 1f : 0f, dt * 4f);

		// fades: in, out with age, with distance, when you're standing right in it, and the previous line
		float age = e.ageSeconds();
		long elapsed = e.elapsedNow();
		float life = it.current() ? (e.durMs() / 1000f + LINGER_S - age) / 3f : (PREVIOUS_S - it.since()) / 1.5f;
		float fade = Math.clamp(age / 0.3f, 0f, 1f) * Math.clamp(life, 0f, 1f)
				* (float) Math.clamp(1 - (it.dist() - FADE_FROM) / (MAX_DIST - FADE_FROM), 0, 1)
				* (float) Math.clamp((it.dist() - TOO_CLOSE) / 1.0, 0, 1);
		boolean singing = it.current() && elapsed >= 0 && elapsed < e.durMs() + 600;
		float vis = alpha * fade * state[2] * (singing ? 0.95f : it.current() ? 0.7f : 0.45f);
		if (vis < 0.02f) return;

		float x0 = pt.x() - w / 2f + state[0], y0 = pt.y() - h / 2f + state[1];
		int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x0 + w), iy1 = Math.round(y0 + h);
		if (free) {
			HudSpace.reserve(ix0 - 3, iy0 - 3, ix1 + 3, iy1 + 3);
			if (Math.abs(state[0] - tx) < 1f && Math.abs(state[1] - ty) < 1f) placed.add(new float[]{ix0, iy0, ix1, iy1});
		}

		// each friend gets their own colour
		long mix = (e.owner().getMostSignificantBits() ^ e.owner().getLeastSignificantBits()) * 0x9E3779B97F4A7C15L;
		int col = Draw.hsv((int) ((mix >>> 40) % 360), 0.55f, 1f);
		float seed = (float) ((mix >>> 20) & 0xFFFF) / 0xFFFF;
		GlassPanel.draw(g, ix0, iy0, ix1, iy1, col, col, vis, singing ? 0.6f : 0.15f, seed, (System.nanoTime() % 1_000_000_000_000L) / 1e9f);

		// "♫ name" tag on the top edge, only on their current line
		if (it.current()) {
			float ts = Math.max(0.4f, scale * 0.5f);
			Draw.text(g, font, "♫ " + nameOf(mc, e.owner()), ix0 + 3 * scale, iy0 + 2 * scale, ts, Draw.argb(col, 0.9f * vis), false);
		}

		float tw = font.width(text) * scale;
		float tx0 = x0 + (w - tw) / 2f, ty0 = y0 + (h - lh) / 2f + scale * 2f;
		float progress = e.durMs() <= 0 ? 1f : Math.clamp(elapsed / (float) e.durMs(), 0f, 1f);
		if (it.current()) Draw.karaoke(g, font, text, tx0, ty0, scale, progress, vis, col, 0xE8E8F0, 0.55f, false, singing ? 0.6f : 0f);
		else Draw.text(g, font, text, tx0, ty0, scale, Draw.argb(Draw.mix(0xE8E8F0, col, 0.3f), vis), false);
	}

	private static boolean fits(Projector.Point pt, float w, float h, int spot) {
		float x0 = pt.x() - w / 2f + SPOTS[spot][0] * w, y0 = pt.y() - h / 2f + SPOTS[spot][1] * (h + 4);
		return !HudSpace.overlaps(x0, y0, x0 + w, y0 + h);
	}

	private static String nameOf(Minecraft mc, UUID id) {
		var conn = mc.getConnection();
		PlayerInfo info = conn == null ? null : conn.getPlayerInfo(id);
		String n = info == null ? null : info.getProfile().name();
		return n == null || n.isBlank() ? "friend" : n;
	}
}
