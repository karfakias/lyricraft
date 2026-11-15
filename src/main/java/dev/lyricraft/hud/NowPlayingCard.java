package dev.lyricraft.hud;

import dev.lyricraft.Config;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Now Playing card. Five looks (Card, Compact, Pill, Cover, Minimal), any size, and it can
 * be dragged anywhere on screen from the Lyricraft menu.
 */
public final class NowPlayingCard {

	public static final int MARGIN = 6;

	/** Where the card was drawn last frame, in screen (GUI) pixels: x, y, w, h. */
	public static final float[] lastRect = new float[4];
	private static volatile long lastDrawnNanos;

	/** Was the card drawn in the last moment (so it can be grabbed)? */
	public static boolean drawnRecently() {
		return System.nanoTime() - lastDrawnNanos < 250_000_000L;
	}

	private String shownKey = "";
	private long changedAt;
	private float vinylOut, vinylAngle, pauseShade;
	private final float[] bars = new float[4];

	/** Unscaled size of a card look. */
	public static int width(Config.CardStyle s) {
		return switch (s) {
			case CARD -> 150;
			case COMPACT -> 118;
			case PILL -> 150;
			case COVER -> 48;
			case MINIMAL -> 140;
		};
	}

	public static int height(Config.CardStyle s) {
		return switch (s) {
			case CARD -> 40;
			case COMPACT -> 24;
			case PILL -> 16;
			case COVER -> 67;
			case MINIMAL -> 22;
		};
	}

	/** Top-left corner on screen for the saved position. */
	public static float[] place(Config cfg, int guiW, int guiH) {
		float w = width(cfg.cardStyle) * cfg.cardScale, h = height(cfg.cardStyle) * cfg.cardScale;
		float x = MARGIN + cfg.cardX * Math.max(0, guiW - w - MARGIN * 2);
		float y = MARGIN + cfg.cardY * Math.max(0, guiH - h - MARGIN * 2);
		return new float[]{x, y, w, h};
	}

	public void render(GuiGraphicsExtractor g, Font font, Config cfg, MediaSnapshot track, Lyrics lyrics, long posMs,
	                   boolean playing, int accent, int vibeA, float alpha, float dt) {
		if (alpha < 0.01f) return;
		long now = System.nanoTime();
		if (!track.trackKey().equals(shownKey)) {
			shownKey = track.trackKey();
			changedAt = now;
		}
		float since = (now - changedAt) / 1e9f;
		vinylOut = Draw.approach(vinylOut, playing && cfg.cardVinyl ? 1f : 0f, dt * 2.2f);
		vinylAngle += dt * (float) Math.PI * 2f * 0.55f * Math.max(vinylOut, playing ? 1f : 0f);
		pauseShade = Draw.approach(pauseShade, playing ? 0f : 1f, dt * 4f);
		float t = now / 1e9f;
		for (int i = 0; i < bars.length; i++) {
			float target = playing ? 0.35f + 0.65f * (float) Math.abs(Math.sin(t * (4.1 + i * 1.3) + i * 1.9)) : 0.18f;
			bars[i] = Draw.approach(bars[i], target, dt * 6f);
		}

		float[] r = place(cfg, g.guiWidth(), g.guiHeight());
		System.arraycopy(r, 0, lastRect, 0, 4);
		lastDrawnNanos = now;
		HudSpace.reserve(r[0] - 2, r[1] - 2, r[0] + r[2] + 3, r[1] + r[3] + 3);

		CoverArt.Cover cover = cfg.cardCover ? CoverArt.get(track.trackKey()) : null;
		int tint = cover != null ? cover.colorA() : vibeA;
		int tintB = cover != null ? cover.colorB() : Draw.mix(tint, 0x000000, 0.5f);
		Ctx c = new Ctx(g, font, cfg, track, lyrics, posMs, playing, accent, tint, cover, alpha, since, now,
				Theme.of(cfg.cardTheme, accent, tint, tintB));

		g.pose().pushMatrix();
		g.pose().translate(Math.round(r[0]), Math.round(r[1]));
		g.pose().scale(cfg.cardScale, cfg.cardScale);
		switch (cfg.cardStyle) {
			case CARD -> card(c);
			case COMPACT -> compact(c);
			case PILL -> pill(c);
			case COVER -> coverOnly(c);
			case MINIMAL -> minimal(c);
		}
		g.pose().popMatrix();
	}

	private record Ctx(GuiGraphicsExtractor g, Font font, Config cfg, MediaSnapshot track, Lyrics lyrics, long posMs,
	                   boolean playing, int accent, int tint, CoverArt.Cover cover, float a, float since, long now, Theme th) {
		float bg() {
			return cfg.cardOpacity;
		}

		/** Fade/slide-in for text after a song change. */
		float textIn() {
			return Math.clamp(Draw.easeOutCubic((since - 0.12f) / 0.45f), 0f, 1f);
		}

		float flip() {
			return Draw.easeOutBack(Math.clamp((since - 0.05f) / 0.45f, 0f, 1f));
		}
	}

	/** Colours for one card theme. */
	record Theme(Config.CardTheme kind, int top, int bottom, int border, int title, int artist, int detail, int bar,
	             boolean shadow, boolean light) {
		static Theme of(Config.CardTheme t, int accent, int tint, int tintB) {
			return switch (t) {
				case GLASS -> new Theme(t, Draw.mix(0x0C0C10, tint, 0.30f), 0x0C0C10, -1, 0xFFFFFF, 0xB8B8C0, 0x9A9AA6, accent, true, false);
				case DARK -> new Theme(t, 0x181818, 0x121212, 0x2A2A2A, 0xFFFFFF, 0xB3B3B3, 0x8A8A8A, accent, false, false);
				case LIGHT -> new Theme(t, 0xFAFAFC, 0xE4E4EA, 0xC8C8D0, 0x14141A, 0x4A4A55, 0x6E6E78, Draw.mix(accent, 0x000000, 0.25f), false, true);
				case SPOTIFY -> new Theme(t, 0x1ED760, 0x0F7A37, -1, 0xFFFFFF, 0xEAFFF1, 0xC4F2D4, 0xFFFFFF, true, false);
				case SONG -> new Theme(t, Draw.mix(tint, 0x000000, 0.25f), Draw.mix(tintB, 0x000000, 0.55f), -1, 0xFFFFFF, 0xF0F0F4, 0xD8D8E0, 0xFFFFFF, true, false);
				case NEON -> new Theme(t, 0x07070C, 0x040408, accent, 0xFFFFFF, Draw.mix(accent, 0xFFFFFF, 0.45f), Draw.mix(accent, 0x9A9AA6, 0.6f), accent, true, false);
				case TOOLTIP -> new Theme(t, 0x100010, 0x100010, 0x5000FF, 0xFFFFFF, 0xAAAAAA, 0x8A8A9A, 0xAA66FF, true, false);
			};
		}
	}

	// ------------------------------------------------------------------ looks

	private void card(Ctx c) {
		Config.CardStyle s = Config.CardStyle.CARD;
		int w = width(s), h = height(s), cs = 30;
		body(c, w, h);
		int tx = 6;
		if (c.cfg.cardCover) {
			if (vinylOut > 0.02f) vinyl(c, 5 + cs / 2f + vinylOut * 9f, 5 + cs / 2f, cs / 2f - 1);
			coverArt(c, 5, 5, cs, false);
			tx = 5 + cs + (c.cfg.cardVinyl ? 14 : 6);
		}
		int slide = Math.round((1f - c.textIn()) * 6f);
		float ta = c.a * c.textIn();
		int tw = w - tx - 18;
		marquee(c, c.track.title(), tx + slide, 5, tw, Draw.argb(c.th.title(), ta), c.th.shadow());
		marquee(c, c.track.artist(), tx + slide, 15, tw + 12, Draw.argb(c.th.artist(), ta), false);
		playingBars(c, w - 14, 5, 7);
		if (c.cfg.cardDetails) {
			String info = status(c); // the dot colour shows the source: green = Spotify app, blue = browser
			float sm = 0.62f;
			String times = time(c.posMs) + " / " + (c.track.durationMs() > 0 ? time(c.track.durationMs()) : "--:--");
			int timesW = Math.round(c.font.width(times) * sm);
			Draw.text(c.g, c.font, "●", tx, 25.5f, sm, Draw.argb(sourceColor(c.track.app()), ta), false);
			Draw.text(c.g, c.font, Draw.ellipsize(c.font, info, Math.round((w - tx - 12 - timesW) / sm) - 8),
					tx + 5, 25.5f, sm, Draw.argb(c.th.detail(), ta), false);
			Draw.text(c.g, c.font, times, w - 6 - timesW, 25.5f, sm, Draw.argb(c.th.artist(), c.a), false);
		}
		if (c.cfg.cardProgress) progress(c, tx, h - 6, w - 6, 2, true);
	}

	private void compact(Ctx c) {
		Config.CardStyle s = Config.CardStyle.COMPACT;
		int w = width(s), h = height(s), cs = 16;
		body(c, w, h);
		int tx = 6;
		if (c.cfg.cardCover) {
			coverArt(c, 4, 4, cs, false);
			tx = 4 + cs + 5;
		}
		float ta = c.a * c.textIn();
		marquee(c, c.track.title(), tx, 4, w - tx - 14, Draw.argb(c.th.title(), ta), c.th.shadow());
		String second = c.cfg.cardDetails ? c.track.artist() + "  ·  " + time(c.posMs) : c.track.artist();
		Draw.text(c.g, c.font, Draw.ellipsize(c.font, second, Math.round((w - tx - 4) / 0.8f)), tx, 13.5f, 0.8f,
				Draw.argb(c.th.artist(), ta), false);
		playingBars(c, w - 11, 4, 6);
		if (c.cfg.cardProgress) progress(c, 2, h - 2, w - 2, 1, false);
	}

	private void pill(Ctx c) {
		Config.CardStyle s = Config.CardStyle.PILL;
		int w = width(s), h = height(s);
		float pa = c.bg() * c.a;
		if (pa > 0.02f && c.th.border() >= 0 && c.th.kind() != Config.CardTheme.TOOLTIP) {
			int edge = Draw.argb(c.th.border(), pa);
			Draw.circle(c.g, h / 2f, h / 2f, h / 2f + 1, edge);
			Draw.circle(c.g, w - h / 2f, h / 2f, h / 2f + 1, edge);
			c.g.fill(h / 2, -1, w - h / 2, h + 1, edge);
		}
		int bg = Draw.argb(c.th.bottom(), 0.92f * pa);
		Draw.circle(c.g, h / 2f, h / 2f, h / 2f, bg);
		Draw.circle(c.g, w - h / 2f, h / 2f, h / 2f, bg);
		c.g.fill(h / 2, 0, w - h / 2, h, bg);
		c.g.fillGradient(h / 2, 0, w - h / 2, h, Draw.argb(c.th.top(), 0.9f * pa), Draw.argb(c.th.bottom(), 0.4f * pa));
		// round spinning record with the cover on it
		float cx = h / 2f, cy = h / 2f, rr = h / 2f - 2;
		Draw.circle(c.g, cx, cy, rr + 1, Draw.argb(0x000000, c.a));
		if (c.cover != null) coverArt(c, Math.round(cx - rr), Math.round(cy - rr), Math.round(rr * 2), true);
		else Draw.circle(c.g, cx, cy, rr, Draw.argb(c.tint, c.a));
		float ex = cx + (float) Math.cos(vinylAngle) * rr, ey = cy + (float) Math.sin(vinylAngle) * rr;
		Draw.line(c.g, cx, cy, ex, ey, 1f, Draw.argb(0xFFFFFF, 0.35f * c.a));
		Draw.circle(c.g, cx, cy, 1.2f, Draw.argb(0x0C0C10, c.a));
		String text = c.track.title() + "  ·  " + c.track.artist();
		marquee(c, text, h + 2, 4, w - h - 14, Draw.argb(c.th.title(), c.a * c.textIn()), false);
		if (!c.playing) c.g.text(c.font, "❚❚", w - 12, 4, Draw.argb(c.th.title(), 0.8f * c.a), false);
		else playingBars(c, w - 13, 4, 7);
		if (c.cfg.cardProgress) progress(c, h / 2 + 2, h - 2, w - h / 2 - 2, 1, false);
	}

	private void coverOnly(Ctx c) {
		Config.CardStyle s = Config.CardStyle.COVER;
		int w = width(s), cs = 44;
		body(c, w, height(s));
		coverArt(c, 2, 2, cs, false);
		playingBars(c, cs - 12, cs - 9, 6);
		if (c.cfg.cardProgress) progress(c, 2, cs + 4, cs + 2, 2, false);
		float sm = 0.66f;
		Draw.text(c.g, c.font, Draw.ellipsize(c.font, c.track.title(), Math.round(cs / sm)), 2, cs + 8, sm,
				Draw.argb(c.th.title(), c.a * c.textIn()), c.th.shadow());
		if (c.cfg.cardDetails) Draw.text(c.g, c.font, Draw.ellipsize(c.font, c.track.artist(), Math.round(cs / sm)), 2, cs + 8 + 6.5f, sm * 0.9f,
				Draw.argb(c.th.artist(), c.a * c.textIn()), false);
	}

	private void minimal(Ctx c) {
		Config.CardStyle s = Config.CardStyle.MINIMAL;
		int w = width(s);
		if (c.bg() > 0.05f) c.g.fillGradient(0, 0, w, height(s), Draw.argb(0x000000, 0.35f * c.bg() * c.a), Draw.argb(0x000000, 0f));
		float bob = c.playing ? (float) Math.sin(c.now / 2.5e8) * 0.8f : 0f;
		Draw.text(c.g, c.font, c.playing ? "♫" : "❚❚", 1, 2 + bob, 1f, Draw.argb(c.accent, c.a), true);
		marquee(c, c.track.title(), 12, 2, w - 14, Draw.argb(0xFFFFFF, c.a * c.textIn()), true);
		Draw.text(c.g, c.font, Draw.ellipsize(c.font, c.track.artist(), Math.round((w - 14) / 0.85f)), 12, 12, 0.85f,
				Draw.argb(0xC0C0C8, c.a * c.textIn()), true);
		if (c.cfg.cardProgress) progress(c, 12, 21, w - 2, 1, false);
	}

	// ------------------------------------------------------------------ parts

	private static void body(Ctx c, int w, int h) {
		float bg = c.bg();
		if (bg <= 0.02f) return;
		GuiGraphicsExtractor g = c.g;
		Theme th = c.th;
		float a = bg * c.a;
		roundRect(g, 1, 2, w + 1, h + 2, Draw.argb(0x000000, 0.3f * a)); // drop shadow
		switch (th.kind()) {
			case NEON -> {
				// glow halo around a black panel
				roundRect(g, -2, -2, w + 2, h + 2, Draw.argb(th.border(), 0.18f * a));
				roundRect(g, -1, -1, w + 1, h + 1, Draw.argb(th.border(), 0.35f * a));
				roundRect(g, 0, 0, w, h, Draw.argb(th.border(), 0.95f * a));
				roundRect(g, 1, 1, w - 1, h - 1, Draw.argb(th.bottom(), 0.96f * a));
			}
			case TOOLTIP -> {
				// the classic Minecraft tooltip: dark purple with a purple gradient frame
				g.fill(1, 0, w - 1, h, Draw.argb(th.top(), 0.94f * a));
				g.fill(0, 1, w, h - 1, Draw.argb(th.top(), 0.94f * a));
				g.fillGradient(1, 1, 2, h - 1, Draw.argb(0x5000FF, 0.5f * a), Draw.argb(0x28007F, 0.5f * a));
				g.fillGradient(w - 2, 1, w - 1, h - 1, Draw.argb(0x5000FF, 0.5f * a), Draw.argb(0x28007F, 0.5f * a));
				g.fill(1, 1, w - 1, 2, Draw.argb(0x5000FF, 0.5f * a));
				g.fill(1, h - 2, w - 1, h - 1, Draw.argb(0x28007F, 0.5f * a));
			}
			default -> {
				roundRect(g, 0, 0, w, h, Draw.argb(th.bottom(), 0.92f * a));
				g.fillGradient(1, 1, w - 1, h - 1, Draw.argb(th.top(), 0.92f * a), Draw.argb(th.bottom(), 0.6f * a));
				if (th.border() >= 0) {
					g.fill(2, 0, w - 2, 1, Draw.argb(th.border(), a));
					g.fill(2, h - 1, w - 2, h, Draw.argb(th.border(), a));
					g.fill(0, 2, 1, h - 2, Draw.argb(th.border(), a));
					g.fill(w - 1, 2, w, h - 2, Draw.argb(th.border(), a));
				} else {
					g.fill(2, 0, w - 2, 1, Draw.argb(0xFFFFFF, (th.light() ? 0.6f : 0.12f) * a));
				}
			}
		}
	}

	private void coverArt(Ctx c, int x, int y, int size, boolean round) {
		GuiGraphicsExtractor g = c.g;
		if (!round) g.fill(x + 1, y + 1, x + size + 1, y + size + 1, Draw.argb(0x000000, 0.5f * c.a));
		g.pose().pushMatrix();
		g.pose().translate(x + size / 2f, y + size / 2f);
		g.pose().scale(Math.max(0.02f, round ? 1f : c.flip()), 1f);
		g.pose().translate(-size / 2f, -size / 2f);
		if (c.cover != null) drawPixels(g, c.cover, size, c.a, round);
		else {
			g.fillGradient(0, 0, size, size, Draw.argb(c.tint, c.a), Draw.argb(Draw.mix(c.tint, 0x000000, 0.7f), c.a));
			float sc = size / 20f;
			Draw.text(g, c.font, "♫", size / 2f - 3.5f * sc, size / 2f - 4f * sc, sc, Draw.argb(0xFFFFFF, 0.9f * c.a), true);
		}
		if (!round) {
			g.fillGradient(0, 0, size, size / 2, Draw.argb(0xFFFFFF, 0.12f * c.a), Draw.argb(0xFFFFFF, 0f));
			g.outline(0, 0, size, size, Draw.argb(0xFFFFFF, 0.12f * c.a));
			if (pauseShade > 0.01f && size >= 14) {
				g.fill(0, 0, size, size, Draw.argb(0x000000, 0.45f * pauseShade * c.a));
				int pa = Draw.argb(0xFFFFFF, 0.9f * pauseShade * c.a), m = size / 2, bh = Math.max(3, size / 6);
				g.fill(m - bh / 2 - 2, m - bh, m - bh / 2, m + bh, pa);
				g.fill(m + bh / 2, m - bh, m + bh / 2 + 2, m + bh, pa);
			}
		}
		g.pose().popMatrix();
	}

	private static void drawPixels(GuiGraphicsExtractor g, CoverArt.Cover cover, int size, float a, boolean round) {
		int n = CoverArt.SIZE;
		g.pose().pushMatrix();
		g.pose().scale(size / (float) n, size / (float) n);
		int[] px = cover.pixels();
		int alpha = Math.clamp(Math.round(a * 255f), 0, 255) << 24;
		float c = n / 2f;
		for (int y = 0; y < n; y++) {
			int row = y * n, from = 0, to = n;
			if (round) {
				float dy = y + 0.5f - c, half = (float) Math.sqrt(Math.max(0, c * c - dy * dy));
				from = Math.round(c - half);
				to = Math.round(c + half);
				if (to <= from) continue;
			}
			int start = from;
			for (int x = from + 1; x <= to; x++) {
				if (x == to || px[row + x] != px[row + start]) {
					g.fill(start, y, x, y + 1, alpha | px[row + start]);
					start = x;
				}
			}
		}
		g.pose().popMatrix();
	}

	private void vinyl(Ctx c, float cx, float cy, float r) {
		GuiGraphicsExtractor g = c.g;
		Draw.circle(g, cx, cy, r, Draw.argb(0x0A0A0A, c.a));
		for (float rr = r - 2; rr > r * 0.45f; rr -= 2.5f) Draw.ring(g, cx, cy, rr, rr - 0.6f, Draw.argb(0x2A2A2A, c.a));
		float ex = cx + (float) Math.cos(vinylAngle) * r * 0.7f, ey = cy + (float) Math.sin(vinylAngle) * r * 0.7f;
		Draw.line(g, cx, cy, ex, ey, 1f, Draw.argb(0xFFFFFF, 0.18f * c.a));
		Draw.circle(g, cx, cy, r * 0.36f, Draw.argb(c.accent, c.a));
		Draw.circle(g, cx, cy, 1.2f, Draw.argb(0x0A0A0A, c.a));
	}

	private void playingBars(Ctx c, int x, int y, int maxH) {
		for (int i = 0; i < bars.length; i++) {
			int h = Math.max(1, Math.round(bars[i] * maxH));
			c.g.fill(x + i * 3, y + maxH - h, x + i * 3 + 2, y + maxH, Draw.argb(c.playing ? c.th.bar() : 0x8A8A96, c.a));
		}
	}

	private static void progress(Ctx c, int x0, int y, int x1, int thick, boolean knob) {
		GuiGraphicsExtractor g = c.g;
		g.fill(x0, y, x1, y + thick, Draw.argb(c.th.light() ? 0x000000 : 0xFFFFFF, 0.16f * c.a));
		long dur = c.track.durationMs();
		if (dur <= 0) return;
		float p = Math.clamp(c.posMs / (float) dur, 0f, 1f);
		int fx = x0 + Math.round((x1 - x0) * p);
		g.fill(x0, y, fx, y + thick, Draw.argb(c.th.bar(), c.a));
		if (knob) g.fill(fx - 1, y - 1, fx + 2, y + thick + 1, Draw.argb(c.th.light() ? 0x14141A : 0xFFFFFF, c.a));
	}

	/** Text that scrolls back and forth when it's too long. */
	private static void marquee(Ctx c, String text, int x, int y, int maxW, int color, boolean shadow) {
		if (text == null) return;
		Font font = c.font;
		int w = font.width(text);
		if (w <= maxW) {
			c.g.text(font, text, x, y, color, shadow);
			return;
		}
		float overflow = w - maxW + 6;
		float cycle = 3f + overflow / 18f * 2f;
		float u = (c.since % cycle) / cycle;
		float off;
		if (u < 0.25f) off = 0;
		else if (u < 0.5f) off = Draw.easeOutCubic((u - 0.25f) / 0.25f) * overflow;
		else if (u < 0.75f) off = overflow;
		else off = (1f - Draw.easeOutCubic((u - 0.75f) / 0.25f)) * overflow;
		// the scissor follows the card's position/scale transform, so local coordinates are right
		c.g.enableScissor(x, y - 1, x + maxW, y + font.lineHeight + 1);
		c.g.text(font, text, Math.round(x - off), y, color, shadow);
		c.g.disableScissor();
	}

	private static void roundRect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 2, y0, x1 - 2, y0 + 1, color);
		g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, color);
		g.fill(x0, y0 + 2, x1, y1 - 2, color);
		g.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, color);
		g.fill(x0 + 2, y1 - 1, x1 - 2, y1, color);
	}

	private static String status(Ctx c) {
		if (!c.playing) return "Paused";
		Lyrics l = c.lyrics;
		if (l == null) return "Finding lyrics" + ".".repeat((int) (c.now / 400_000_000L % 4));
		return switch (l.kind) {
			case SYNCED -> "Lyrics synced";
			case PLAIN_ONLY -> "Lyrics not synced";
			case INSTRUMENTAL -> "Instrumental";
			case NOT_FOUND -> "No lyrics found";
		};
	}

	static String source(String app) {
		String s = app == null ? "" : app.toLowerCase();
		if (s.contains("spotify")) return "Spotify";
		if (s.contains("chrome")) return "Web · Chrome";
		if (s.contains("msedge") || s.contains("edge")) return "Web · Edge";
		if (s.contains("firefox")) return "Web · Firefox";
		if (s.contains("opera")) return "Web · Opera";
		if (s.contains("brave")) return "Web · Brave";
		return app == null || app.isBlank() ? "Media" : app.replace(".exe", "");
	}

	private static int sourceColor(String app) {
		return app != null && app.toLowerCase().contains("spotify") ? 0x1ED760 : 0x7FB2FF;
	}

	static String time(long ms) {
		long s = Math.max(0, ms) / 1000;
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}
}
