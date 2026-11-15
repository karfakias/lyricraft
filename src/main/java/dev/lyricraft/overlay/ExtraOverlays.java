package dev.lyricraft.overlay;

import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * More overlays: CASSETTE (walkman tape widget), RAIN (lo-fi rain on glass), STARS (starry night
 * with shooting stars), OSCILLOSCOPE (glowing waveform) and CONFETTI (party bursts on new lines).
 */
final class ExtraOverlays {

	private final LyricraftClient mod;
	private final Random random = new Random();
	private int lastLine = Integer.MIN_VALUE;
	private boolean lineChangedThisFrame;

	ExtraOverlays(LyricraftClient mod) {
		this.mod = mod;
	}

	/** Detects when a new lyric line starts (for shooting stars / confetti). Call once per frame. */
	void updateBeat() {
		lineChangedThisFrame = false;
		Lyrics lyrics = mod.lyrics();
		if (lyrics == null || lyrics.kind != Lyrics.Kind.SYNCED || !mod.tracker.isPlaying()) return;
		int line = lyrics.activeIndex(mod.tracker.positionMs() - mod.currentOffsetMs());
		if (line >= 0 && line != lastLine) {
			lineChangedThisFrame = lastLine != Integer.MIN_VALUE;
			lastLine = line;
		}
	}

	// ================================================================== CASSETTE

	private float reelAngleL, reelAngleR, marquee;

	void cassette(GuiGraphicsExtractor g, Font font, float k, float dt) {
		MediaSnapshot track = mod.tracker.current();
		if (track == null) return;
		boolean playing = mod.tracker.isPlaying();
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();
		float progress = track.durationMs() > 0 ? Math.clamp(mod.tracker.positionMs() / (float) track.durationMs(), 0f, 1f) : 0f;

		g.fill(4, 4, 186, 64, Draw.argb(0x0E0E10, 0.78f * k));
		g.fill(4, 4, 6, 64, Draw.argb(a, k));

		// shell
		int x0 = 10, y0 = 10, x1 = 66, y1 = 56;
		g.fill(x0, y0, x1, y1, Draw.argb(0x2B2B30, k));
		g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, Draw.argb(0x3A3A42, k));
		// label strip in the song's colours
		g.fillGradient(x0 + 3, y0 + 3, x1 - 3, y0 + 13, Draw.argb(a, k), Draw.argb(b, k));
		Draw.text(g, font, Draw.ellipsize(font, track.title().toUpperCase(Locale.ROOT), 85), x0 + 5, y0 + 5, 0.55f, Draw.argb(0x101010, k), false);
		// window
		g.fill(x0 + 8, y0 + 17, x1 - 8, y0 + 33, Draw.argb(0x15151A, k));

		// reels: tape moves from the left reel to the right one as the song plays
		float lcx = x0 + 17, rcx = x1 - 17, rcy = y0 + 25;
		float rMin = 4.5f, rMax = 9f;
		float rl = rMin + (rMax - rMin) * (1f - progress);
		float rr = rMin + (rMax - rMin) * progress;
		if (playing) {
			// a smaller reel has to spin faster for the same tape speed
			reelAngleL += dt * 18f / rl;
			reelAngleR += dt * 18f / rr;
		}
		Draw.circle(g, lcx, rcy, rl, Draw.argb(0x5A3A22, k));
		Draw.circle(g, rcx, rcy, rr, Draw.argb(0x5A3A22, k));
		hub(g, lcx, rcy, reelAngleL, k);
		hub(g, rcx, rcy, reelAngleR, k);
		// tape running along the bottom
		g.fill(Math.round(lcx), y0 + 37, Math.round(rcx), y0 + 38, Draw.argb(0x5A3A22, k));
		// screw holes
		Draw.circle(g, x0 + 4, y1 - 4, 1.2f, Draw.argb(0x1A1A1A, k));
		Draw.circle(g, x1 - 4, y1 - 4, 1.2f, Draw.argb(0x1A1A1A, k));

		marquee = trackText(g, font, track, 72, k, dt, marquee, playing, progress, a);
	}

	private static void hub(GuiGraphicsExtractor g, float cx, float cy, float angle, float k) {
		Draw.circle(g, cx, cy, 3.2f, Draw.argb(0xEDEDED, k));
		for (int i = 0; i < 3; i++) {
			double ang = angle + i * Math.PI * 2 / 3;
			Draw.line(g, cx, cy, cx + (float) Math.cos(ang) * 3.2f, cy + (float) Math.sin(ang) * 3.2f, 1f, Draw.argb(0x2A2A2A, k));
		}
		Draw.circle(g, cx, cy, 1f, Draw.argb(0x2A2A2A, k));
	}

	/** Title (scrolling if long), artist, time and progress bar next to a widget. Returns new marquee offset. */
	static float trackText(GuiGraphicsExtractor g, Font font, MediaSnapshot track, int tx, float k, float dt,
	                       float marquee, boolean playing, float progress, int accent) {
		int maxW = 110;
		String title = track.title();
		int tw = font.width(title);
		g.enableScissor(tx, 8, tx + maxW, 20);
		if (tw > maxW) {
			marquee += dt * 18f;
			float span = tw + 30, off = marquee % span;
			g.text(font, title, Math.round(tx - off), 10, Draw.argb(0xFFFFFF, k), true);
			g.text(font, title, Math.round(tx - off + span), 10, Draw.argb(0xFFFFFF, k), true);
		} else {
			g.text(font, title, tx, 10, Draw.argb(0xFFFFFF, k), true);
		}
		g.disableScissor();
		g.text(font, Draw.ellipsize(font, track.artist(), maxW), tx, 22, Draw.argb(0xB3B3B3, k), false);
		long pos = Math.max(0, Math.round(progress * track.durationMs()));
		String clock = mmss(pos) + " / " + mmss(track.durationMs());
		g.text(font, (playing ? "▶ " : "❚❚ ") + clock, tx, 36, Draw.argb(accent, k), false);
		g.fill(tx, 50, tx + maxW, 52, Draw.argb(0x3A3A3A, k));
		g.fill(tx, 50, tx + Math.round(maxW * progress), 52, Draw.argb(accent, k));
		return marquee;
	}

	private static String mmss(long ms) {
		long s = Math.max(0, ms) / 1000;
		return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
	}

	// ================================================================== RAIN

	private record Drop(float x, float y, float speed, float len) {}
	private record Bead(float x, float y, float r, float slide) {}
	private final List<Drop> drops = new ArrayList<>();
	private final List<Bead> beads = new ArrayList<>();

	void rain(GuiGraphicsExtractor g, float k, float dt, float time) {
		int w = g.guiWidth(), h = g.guiHeight();
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();
		float pulse = mod.vibe.pulse(System.nanoTime());

		// cosy blue-grey mood + soft bokeh city lights
		g.fill(0, 0, w, h, Draw.argb(0x0B1220, 0.18f * k));
		for (int i = 0; i < 7; i++) {
			float bx = (float) ((Math.sin(i * 12.9898) * 43758.5453) % 1 + 1) % 1 * w;
			float by = h * (0.25f + 0.5f * (float) (((Math.sin(i * 78.233) * 12345.678) % 1 + 1) % 1));
			bx += (float) Math.sin(time * 0.2 + i) * 12;
			float r = 14 + (i % 3) * 8;
			Draw.circle(g, bx, by, r, Draw.argb(i % 2 == 0 ? a : b, (0.05f + pulse * 0.02f) * k));
			Draw.circle(g, bx, by, r * 0.6f, Draw.argb(i % 2 == 0 ? a : b, 0.05f * k));
		}

		// falling streaks
		int target = 140;
		while (drops.size() < target) drops.add(new Drop(random.nextFloat() * (w + 60), random.nextFloat() * h - h, 220 + random.nextFloat() * 220, 6 + random.nextFloat() * 10));
		List<Drop> next = new ArrayList<>(drops.size());
		for (Drop d : drops) {
			float ny = d.y + d.speed * dt, nx = d.x - d.speed * dt * 0.18f;
			Draw.line(g, nx, ny, nx + d.len * 0.18f, ny - d.len, 1f, Draw.argb(0xCFE3FF, 0.22f * k));
			if (ny - d.len < h) next.add(new Drop(nx, ny, d.speed, d.len));
		}
		drops.clear();
		drops.addAll(next);

		// droplets on the glass that slowly slide down
		while (beads.size() < 45) beads.add(new Bead(random.nextFloat() * w, random.nextFloat() * h, 0.8f + random.nextFloat() * 1.8f, random.nextFloat() < 0.25f ? 8 + random.nextFloat() * 25 : 0));
		Iterator<Bead> it = beads.iterator();
		List<Bead> moved = new ArrayList<>();
		while (it.hasNext()) {
			Bead bd = it.next();
			float ny = bd.y + bd.slide * dt;
			Draw.circle(g, bd.x, ny, bd.r, Draw.argb(0xE8F2FF, 0.18f * k));
			g.fill(Math.round(bd.x - bd.r * 0.4f), Math.round(ny - bd.r * 0.5f), Math.round(bd.x), Math.round(ny), Draw.argb(0xFFFFFF, 0.25f * k));
			if (bd.slide > 0) g.fill(Math.round(bd.x) - 0, Math.round(ny - 14), Math.round(bd.x) + 1, Math.round(ny), Draw.argb(0xE8F2FF, 0.06f * k));
			it.remove();
			if (ny < h + 5 && random.nextFloat() > 0.0015f) moved.add(new Bead(bd.x, ny, bd.r, bd.slide));
		}
		beads.addAll(moved);
	}

	// ================================================================== STARS

	private record Shooter(float x, float y, float vx, float vy, float born) {}
	private final List<Shooter> shooters = new ArrayList<>();

	void stars(GuiGraphicsExtractor g, float k, float time) {
		int w = g.guiWidth(), h = g.guiHeight();
		int a = mod.vibe.colorA();
		int skyH = Math.round(h * 0.55f);
		float pulse = mod.vibe.pulse(System.nanoTime());

		// night sky gradient
		g.fillGradient(0, 0, w, skyH, Draw.argb(0x060A24, 0.55f * k), Draw.argb(0x060A24, 0f));
		// a faint milky-way band in the song's colour
		for (int x = 0; x < w; x += 4) {
			float y = skyH * 0.35f + (float) Math.sin(x / (float) w * 3.0 + 0.6) * skyH * 0.18f;
			g.fillGradient(x, Math.round(y - 14), x + 4, Math.round(y + 14), Draw.argb(a, 0f), Draw.argb(a, 0.05f * k));
		}
		// twinkling stars (fixed positions from a seed so they don't jump around)
		Random r = new Random(1337);
		for (int i = 0; i < 170; i++) {
			float sx = r.nextFloat() * w, sy = r.nextFloat() * r.nextFloat() * skyH;
			float tw = 0.5f + 0.5f * (float) Math.sin(time * (1.5 + r.nextFloat() * 3) + i);
			float big = r.nextFloat();
			float alpha = (0.25f + 0.65f * tw) * (1f - sy / skyH * 0.7f) * k;
			int col = big > 0.92f ? Draw.mix(0xFFFFFF, a, 0.4f) : 0xFFFFFF;
			int size = big > 0.97f ? 2 : 1;
			g.fill(Math.round(sx), Math.round(sy), Math.round(sx) + size, Math.round(sy) + size, Draw.argb(col, alpha * (1f + pulse * 0.3f)));
			if (big > 0.985f && tw > 0.8f) { // little sparkle cross
				g.fill(Math.round(sx) - 2, Math.round(sy), Math.round(sx) + 3, Math.round(sy) + 1, Draw.argb(col, alpha * 0.5f));
				g.fill(Math.round(sx), Math.round(sy) - 2, Math.round(sx) + 1, Math.round(sy) + 3, Draw.argb(col, alpha * 0.5f));
			}
		}
		// shooting star every time a new line starts
		if (lineChangedThisFrame) {
			float sx = w * (0.2f + random.nextFloat() * 0.7f), sy = skyH * (0.05f + random.nextFloat() * 0.3f);
			shooters.add(new Shooter(sx, sy, -(260 + random.nextFloat() * 160), 110 + random.nextFloat() * 70, time));
		}
		shooters.removeIf(s -> time - s.born > 0.9f);
		for (Shooter s : shooters) {
			float t = time - s.born;
			float hx = s.x + s.vx * t, hy = s.y + s.vy * t;
			float fade = 1f - t / 0.9f;
			for (int i = 0; i < 12; i++) {
				float tt = Math.max(0, t - i * 0.018f);
				float px = s.x + s.vx * tt, py = s.y + s.vy * tt;
				g.fill(Math.round(px), Math.round(py), Math.round(px) + 2, Math.round(py) + 1, Draw.argb(0xFFFFFF, fade * (1f - i / 12f) * 0.8f * k));
			}
			Draw.circle(g, hx, hy, 1.5f, Draw.argb(0xFFFFFF, fade * k));
		}
	}

	// ================================================================== OSCILLOSCOPE

	void oscilloscope(GuiGraphicsExtractor g, float k, float time) {
		int w = g.guiWidth();
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();
		float pulse = mod.vibe.pulse(System.nanoTime());
		float energy = mod.vibe.energy();
		float cy = 44;
		float amp = 3 + pulse * 10 + energy * 3;

		// faint scope grid
		for (int x = 0; x < w; x += 24) g.fill(x, Math.round(cy - 16), x + 1, Math.round(cy + 16), Draw.argb(a, 0.06f * k));
		g.fill(0, Math.round(cy), w, Math.round(cy) + 1, Draw.argb(a, 0.10f * k));

		float prevX = 0, prevY = cy;
		int step = 3;
		for (int x = 0; x <= w; x += step) {
			float u = x / (float) w;
			// envelope keeps the edges calm and the middle lively
			float env = (float) Math.sin(u * Math.PI);
			float y = cy + env * amp * (float) (Math.sin(u * 38 - time * 9) * 0.55
					+ Math.sin(u * 91 + time * 13) * 0.25 * (0.5 + energy)
					+ Math.sin(u * 7 - time * 2) * 0.2);
			int col = Draw.mix(a, b, u);
			if (x > 0) {
				Draw.line(g, prevX, prevY, x, y, 3f, Draw.argb(col, 0.12f * k)); // glow
				Draw.line(g, prevX, prevY, x, y, 1f, Draw.argb(Draw.mix(col, 0xFFFFFF, 0.3f), 0.85f * k));
			}
			prevX = x;
			prevY = y;
		}
	}

	// ================================================================== CONFETTI

	private static final class Piece {
		float x, y, vx, vy, rot, vr, w, h;
		int color;
	}
	private final List<Piece> confetti = new ArrayList<>();

	void confetti(GuiGraphicsExtractor g, float k, float dt) {
		int w = g.guiWidth(), h = g.guiHeight();
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();

		if (lineChangedThisFrame) {
			int count = 35 + Math.round(mod.vibe.energy() * 35);
			boolean left = random.nextBoolean();
			for (int i = 0; i < count; i++) {
				Piece p = new Piece();
				// burst from the top corners like party poppers
				p.x = left ? -5 : w + 5;
				p.y = h * 0.15f + random.nextFloat() * 20;
				p.vx = (left ? 1 : -1) * (120 + random.nextFloat() * 260);
				p.vy = -(80 + random.nextFloat() * 220);
				p.rot = random.nextFloat() * 6.28f;
				p.vr = (random.nextFloat() - 0.5f) * 14;
				p.w = 2 + random.nextFloat() * 3;
				p.h = 1 + random.nextFloat() * 2;
				float pick = random.nextFloat();
				p.color = pick < 0.35f ? a : pick < 0.7f ? b : pick < 0.85f ? 0xFFFFFF : Draw.hsv(random.nextFloat() * 360, 0.7f, 1f);
				confetti.add(p);
			}
			left = !left;
		}

		Iterator<Piece> it = confetti.iterator();
		while (it.hasNext()) {
			Piece p = it.next();
			p.vy += 260 * dt;           // gravity
			p.vx *= (1f - 1.6f * dt);   // air drag
			p.vy = Math.min(p.vy, 90);  // flutter instead of falling fast
			p.x += (p.vx + (float) Math.sin(p.rot * 1.3f) * 25) * dt;
			p.y += p.vy * dt;
			p.rot += p.vr * dt;
			if (p.y > h + 10) {
				it.remove();
				continue;
			}
			// spinning pieces look thinner when edge-on
			float flip = Math.abs((float) Math.cos(p.rot));
			g.pose().pushMatrix();
			g.pose().translate(p.x, p.y);
			g.pose().rotate(p.rot);
			g.pose().scale(p.w, Math.max(0.3f, p.h * flip));
			g.fill(0, 0, 1, 1, Draw.argb(Draw.mix(p.color, 0x000000, (1f - flip) * 0.35f), 0.95f * k));
			g.pose().popMatrix();
		}
		if (confetti.size() > 600) confetti.subList(0, confetti.size() - 600).clear();
	}
}
