package dev.lyricraft.overlay;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/**
 * Decorative overlays, switched with O:
 * VINYL (record player widget), VHS (retro tape look), SYNTHWAVE (retro sunset + neon grid)
 * and VISUALIZER (spectrum ring around the crosshair).
 *
 * {@link #renderUnder} draws behind the lyrics, {@link #renderOver} on top of them.
 */
public final class Overlays {

	private final LyricraftClient mod;
	private final Random random = new Random();
	private final ExtraOverlays extra;
	private float strength; // eased 0..1
	private float vinylAngle;
	private float armLift = 1f; // 1 = lifted (paused)
	private float marquee;
	private static final String DATE = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH)).toUpperCase(Locale.ENGLISH);

	public Overlays(LyricraftClient mod) {
		this.mod = mod;
		this.extra = new ExtraOverlays(mod);
	}

	/** True when the overlay replaces the normal Now Playing card. */
	public boolean replacesNowPlaying(Config cfg) {
		return cfg.overlay == Config.Overlay.VINYL || cfg.overlay == Config.Overlay.CASSETTE || cfg.overlay == Config.Overlay.VHS;
	}

	public void renderUnder(GuiGraphicsExtractor g, Font font, Config cfg, boolean active, float dt, float time) {
		strength = Draw.approach(strength, active && cfg.overlay != Config.Overlay.NONE ? 1f : 0f, dt * 2.5f);
		if (strength < 0.01f) return;
		extra.updateBeat();
		switch (cfg.overlay) {
			case CASSETTE -> extra.cassette(g, font, strength, dt);
			case RAIN -> extra.rain(g, strength, dt, time);
			case STARS -> extra.stars(g, strength, time);
			case OSCILLOSCOPE -> extra.oscilloscope(g, strength, time);
			case CONFETTI -> extra.confetti(g, strength, dt);
			case SYNTHWAVE -> synthwave(g, time);
			case VISUALIZER -> visualizer(g, time);
			case VINYL -> vinyl(g, font, dt, time);
			default -> {}
		}
	}

	public void renderOver(GuiGraphicsExtractor g, Font font, Config cfg, float time) {
		if (strength < 0.01f) return;
		if (cfg.overlay == Config.Overlay.VHS) vhs(g, font, time);
	}

	// ------------------------------------------------------------------ VINYL

	private void vinyl(GuiGraphicsExtractor g, Font font, float dt, float time) {
		MediaSnapshot track = mod.tracker.current();
		if (track == null) return;
		boolean playing = mod.tracker.isPlaying();
		float k = strength;
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();
		float pulse = mod.vibe.pulse(System.nanoTime());

		// 33 1/3 rpm ≈ 3.5 rad/s, eases to a stop when paused
		armLift = Draw.approach(armLift, playing ? 0f : 1f, dt * 2f);
		vinylAngle += dt * 3.49f * (1f - armLift);

		float cx = 34, cy = 34, r = 25;
		// plinth
		g.fill(4, 4, 186, 64, Draw.argb(0x0E0E10, 0.78f * k));
		g.fill(4, 4, 6, 64, Draw.argb(a, k));
		// record
		Draw.circle(g, cx, cy + 1, r + 1, Draw.argb(0x000000, 0.5f * k)); // shadow
		Draw.circle(g, cx, cy, r, Draw.argb(0x111111, k));
		for (float gr = r - 3; gr > 11; gr -= 3) Draw.ring(g, cx, cy, gr, gr - 0.6f, Draw.argb(0x262626, k));
		// light catching the grooves (two sheens that turn with the record)
		for (int s = 0; s < 2; s++) {
			double ang = vinylAngle + s * Math.PI;
			for (float rr = 12; rr < r - 1; rr += 1.0f) {
				float w = 0.18f + 0.1f * (float) Math.sin(rr);
				Draw.line(g, cx + (float) Math.cos(ang - w * 0.5) * rr, cy + (float) Math.sin(ang - w * 0.5) * rr,
						cx + (float) Math.cos(ang + w * 0.5) * rr, cy + (float) Math.sin(ang + w * 0.5) * rr,
						1f, Draw.argb(0xFFFFFF, 0.10f * k));
			}
		}
		// label in the song's colours, pulsing with the beat
		Draw.circle(g, cx, cy, 10, Draw.argb(Draw.mix(a, 0xFFFFFF, pulse * 0.25f), k));
		Draw.ring(g, cx, cy, 10, 7, Draw.argb(b, 0.8f * k));
		float mx = cx + (float) Math.cos(vinylAngle) * 5, my = cy + (float) Math.sin(vinylAngle) * 5;
		g.fill(Math.round(mx) - 1, Math.round(my) - 1, Math.round(mx) + 1, Math.round(my) + 1, Draw.argb(0xFFFFFF, 0.8f * k));
		Draw.circle(g, cx, cy, 1.5f, Draw.argb(0x000000, k));

		// tonearm: moves from outer edge to the label as the song plays, lifts when paused
		float progress = track.durationMs() > 0 ? Math.clamp(mod.tracker.positionMs() / (float) track.durationMs(), 0f, 1f) : 0f;
		float px = cx + r + 6, py = cy - r + 2;
		double armAngle = Math.toRadians(112 + progress * 22 - armLift * 20);
		float armLen = 27;
		float sx = px + (float) Math.cos(armAngle) * armLen, sy = py + (float) Math.sin(armAngle) * armLen;
		Draw.line(g, px, py, sx, sy, 1.5f, Draw.argb(0xC8C8C8, k));
		Draw.circle(g, px, py, 3, Draw.argb(0x9A9A9A, k));
		g.fill(Math.round(sx) - 2, Math.round(sy) - 1, Math.round(sx) + 2, Math.round(sy) + 2, Draw.argb(0xE0E0E0, k));

		// text: scrolling title, artist, time
		int tx = 70, maxW = 110;
		String title = track.title();
		int tw = font.width(title);
		g.enableScissor(tx, 8, tx + maxW, 20);
		if (tw > maxW) {
			marquee += dt * 18f;
			float span = tw + 30;
			float off = marquee % span;
			g.text(font, title, Math.round(tx - off), 10, Draw.argb(0xFFFFFF, k), true);
			g.text(font, title, Math.round(tx - off + span), 10, Draw.argb(0xFFFFFF, k), true);
		} else {
			g.text(font, title, tx, 10, Draw.argb(0xFFFFFF, k), true);
		}
		g.disableScissor();
		g.text(font, Draw.ellipsize(font, track.artist(), maxW), tx, 22, Draw.argb(0xB3B3B3, k), false);
		String clock = fmt(mod.tracker.positionMs()) + " / " + fmt(track.durationMs());
		g.text(font, (playing ? "▶ " : "❚❚ ") + clock, tx, 36, Draw.argb(a, k), false);
		g.fill(tx, 50, tx + maxW, 52, Draw.argb(0x3A3A3A, k));
		g.fill(tx, 50, tx + Math.round(maxW * progress), 52, Draw.argb(a, k));
	}

	// ------------------------------------------------------------------ VHS

	private void vhs(GuiGraphicsExtractor g, Font font, float time) {
		int w = g.guiWidth(), h = g.guiHeight();
		float k = strength;
		MediaSnapshot track = mod.tracker.current();
		boolean playing = mod.tracker.isPlaying();

		// warm, slightly washed-out tape colour
		g.fill(0, 0, w, h, Draw.argb(0x2A1A10, 0.10f * k));
		// scanlines
		for (int y = 0; y < h; y += 2) g.fill(0, y, w, y + 1, Draw.argb(0x000000, 0.10f * k));
		// tracking noise band rolling down the screen
		float bandY = ((time * 0.18f) % 1.4f - 0.2f) * h;
		for (int i = 0; i < 26; i++) {
			int y = Math.round(bandY + random.nextFloat() * 12);
			int x = random.nextInt(Math.max(1, w));
			g.fill(x, y, x + 10 + random.nextInt(60), y + 1, Draw.argb(0xFFFFFF, (0.08f + random.nextFloat() * 0.18f) * k));
		}
		// grain
		for (int i = 0; i < 220; i++) {
			int x = random.nextInt(Math.max(1, w)), y = random.nextInt(Math.max(1, h));
			g.fill(x, y, x + 1, y + 1, Draw.argb(random.nextBoolean() ? 0xFFFFFF : 0x000000, 0.12f * k));
		}
		// slightly darker edges
		g.fillGradient(0, 0, w, 24, Draw.argb(0x000000, 0.35f * k), Draw.argb(0x000000, 0f));
		g.fillGradient(0, h - 24, w, h, Draw.argb(0x000000, 0f), Draw.argb(0x000000, 0.35f * k));

		// on-screen display, chunky like an old camcorder
		float s = 1.5f;
		boolean blink = ((int) (time * 1.6f)) % 2 == 0;
		String state = playing ? "▶ PLAY" : (blink ? "❚❚ PAUSE" : "");
		Draw.text(g, font, state, 14, 12, s, Draw.argb(0xFFFFFF, 0.9f * k), true);
		if (playing && blink) Draw.circle(g, w - 64, 18, 3.5f, Draw.argb(0xFF2B2B, 0.95f * k));
		Draw.text(g, font, "REC", w - 56, 12, s, Draw.argb(0xFFFFFF, (playing ? 0.9f : 0.35f) * k), true);

		long ms = mod.tracker.positionMs();
		String counter = String.format(Locale.ROOT, "SP  %d:%02d:%02d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60);
		Draw.text(g, font, counter, 14, h - 28, s, Draw.argb(0xFFFFFF, 0.9f * k), true);
		int dw = Math.round(font.width(DATE) * s);
		Draw.text(g, font, DATE, w - 14 - dw, h - 28, s, Draw.argb(0xFFFFFF, 0.9f * k), true);
		if (track != null) {
			String t = Draw.ellipsize(font, track.title().toUpperCase(Locale.ROOT) + " - " + track.artist().toUpperCase(Locale.ROOT), Math.round((w - 60) / s));
			Draw.text(g, font, t, 14, 30, s * 0.7f, Draw.argb(0xFFFFFF, 0.65f * k), true);
		}
	}

	// ------------------------------------------------------------------ SYNTHWAVE

	private static final int PINK = 0xFF2E97, CYAN = 0x2DE2E6, SUN_TOP = 0xFFD319, SUN_BOTTOM = 0xFF2975;

	private void synthwave(GuiGraphicsExtractor g, float time) {
		int w = g.guiWidth(), h = g.guiHeight();
		float k = strength;
		float pulse = mod.vibe.pulse(System.nanoTime());
		int horizon = Math.round(h * 0.70f);

		// sunset haze above the horizon
		g.fillGradient(0, horizon - Math.round(h * 0.25f), w, horizon, Draw.argb(PINK, 0f), Draw.argb(PINK, 0.22f * k));

		// striped retro sun, sitting on the horizon
		float sunR = h * 0.17f * (1f + pulse * 0.04f);
		float scx = w / 2f;
		int top = Math.round(horizon - sunR);
		for (int y = top; y < horizon; y++) {
			float rel = (y - top) / sunR; // 0 top .. 1 horizon
			// stripes get thicker towards the bottom (the classic look)
			if (rel > 0.45f) {
				float stripe = (rel - 0.45f) * 14f;
				if ((stripe - (float) Math.floor(stripe)) < 0.25f + (rel - 0.45f) * 0.9f) continue;
			}
			float dy = horizon - y - sunR;
			float half = (float) Math.sqrt(Math.max(0, sunR * sunR - dy * dy));
			int col = Draw.mix(SUN_TOP, SUN_BOTTOM, rel);
			g.fill(Math.round(scx - half), y, Math.round(scx + half), y + 1, Draw.argb(col, 0.42f * k));
		}

		// floor fade + horizon line
		g.fillGradient(0, horizon, w, h, Draw.argb(0x1A0033, 0.30f * k), Draw.argb(0x0A0014, 0.45f * k));
		g.fill(0, horizon, w, horizon + 1, Draw.argb(CYAN, 0.8f * k));

		// grid: horizontal lines rushing towards you
		float speed = 0.6f + mod.vibe.energy() * 0.8f;
		float scroll = (time * speed) % 1f;
		for (int i = 0; i < 14; i++) {
			float z = (i + 1f - scroll); // 1..14, smaller = closer
			float yy = horizon + (h - horizon) * (1.2f / z);
			if (yy > h) continue;
			float fade = Math.clamp(1.4f / z, 0.15f, 1f);
			g.fill(0, Math.round(yy), w, Math.round(yy) + 1, Draw.argb(PINK, (0.55f + pulse * 0.3f) * fade * k));
		}
		// grid: lines converging to the vanishing point
		int lines = 22;
		for (int i = -lines / 2; i <= lines / 2; i++) {
			float bottomX = scx + i * (w / (float) lines) * 2.2f;
			Draw.line(g, scx + i * 3f, horizon, bottomX, h, 1f, Draw.argb(PINK, 0.45f * k));
		}
	}

	// ------------------------------------------------------------------ VISUALIZER

	private void visualizer(GuiGraphicsExtractor g, float time) {
		int w = g.guiWidth(), h = g.guiHeight();
		float k = strength;
		float pulse = mod.vibe.pulse(System.nanoTime());
		float energy = mod.vibe.energy();
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();
		float cx = w / 2f, cy = h / 2f;
		float inner = 22 + pulse * 3;
		int bars = 64;
		float rot = time * 0.25f;

		Draw.ring(g, cx, cy, inner - 2, inner - 3, Draw.argb(a, 0.35f * k));
		for (int i = 0; i < bars; i++) {
			double ang = rot + Math.PI * 2 * i / bars;
			// fake spectrum from a few layered waves, driven by the lyric beat
			float n = (float) (Math.sin(time * 4.1 + i * 0.55) * 0.35 + Math.sin(time * 7.3 - i * 1.3) * 0.25
					+ Math.sin(time * 1.7 + i * 0.21) * 0.4) * 0.5f + 0.5f;
			float len = 2 + n * (5 + energy * 6) + pulse * (6 + n * 10);
			float r0 = inner, r1 = inner + len;
			int col = Draw.mix(a, b, (float) (0.5 + 0.5 * Math.sin(ang * 2)));
			Draw.line(g, cx + (float) Math.cos(ang) * r0, cy + (float) Math.sin(ang) * r0,
					cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1, 1.6f, Draw.argb(col, (0.55f + 0.35f * n) * k));
		}
	}

	private static String fmt(long ms) {
		long s = Math.max(0, ms) / 1000;
		return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
	}
}
