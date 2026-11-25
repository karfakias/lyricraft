package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.Sprites;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * TikTok-edit style effects for popular songs in Echoes: flashes, shake and zoom punches on the
 * chorus, speed lines, colour split, letterbox bars, colour grading, particles that fit the song,
 * and a title card when the song starts. The first chorus is the big "drop".
 *
 * Everything is a handful of quads, so it doesn't cost frames.
 */
public final class EditFx {

	private EditSongs.Style style;
	private String songKey = "";
	private String introTitle, introArtist;
	private float introAge = 99f;

	private float flash, shake, punch, lines, chroma, slam;
	private float bars, grade;
	private int flashColor = 0xFFFFFF;
	private float hue;

	private final List<P> parts = new ArrayList<>();
	private final Random rng = new Random();
	private static final int MAX_PARTS = 70;

	private static final class P {
		float x, y, vx, vy, age, life, size, spin;
		int color;
		char kind; // 'h' heart, 's' sparkle, 'r' rain, 'e' ember, 'd' dot
	}

	/** Picks the song's style (or nothing) when the song changes. */
	void song(String title, String artist, boolean enabled) {
		String k = (title == null ? "" : title) + "\u0000" + (artist == null ? "" : artist) + enabled;
		if (k.equals(songKey)) return;
		songKey = k;
		style = enabled ? EditSongs.find(title, artist) : null;
		parts.clear();
		flash = shake = punch = lines = chroma = slam = 0f;
		if (style != null) {
			introTitle = title;
			introArtist = artist;
			introAge = 0f;
		}
	}

	boolean active() {
		return style != null;
	}

	EditSongs.Style style() {
		return style;
	}

	// ------------------------------------------------------------------ triggers

	/** A new line just started. */
	void onLine(boolean chorus, boolean drop, boolean hook, float w, float h) {
		if (style == null) return;
		float k = drop ? 1f : hook ? 0.8f : chorus ? 0.6f : 0f;
		if (k > 0) {
			flash = Math.max(flash, k * switch (style) {
				case SAD -> 0.35f;
				case LOVE -> 0.6f;
				default -> 1f;
			});
			flashColor = switch (style) {
				case DARK -> 0xFF2B3A;
				case LOVE -> 0xFF8AC4;
				case POP -> Draw.hsv(hue += 67, 0.6f, 1f);
				case SAD -> 0xBFD4FF;
				default -> 0xFFFFFF;
			};
			shake = Math.max(shake, k * switch (style) {
				case HYPE -> 1f;
				case DARK, RETRO -> 0.7f;
				case POP -> 0.45f;
				default -> 0f;
			});
			punch = Math.max(punch, k);
			lines = Math.max(lines, style == EditSongs.Style.HYPE || style == EditSongs.Style.DARK ? k : 0f);
			chroma = Math.max(chroma, style == EditSongs.Style.DARK || style == EditSongs.Style.RETRO || style == EditSongs.Style.HYPE ? k : 0f);
			burst(w, h, drop ? 26 : 12);
		} else {
			punch = Math.max(punch, 0.25f);
			if (style == EditSongs.Style.POP) flash = Math.max(flash, 0.12f);
		}
	}

	/** A word just got sung in the current line. */
	void onWord(boolean chorus, float w, float h) {
		if (style == null) return;
		slam = chorus ? 1f : 0.4f;
		if (!chorus) return;
		switch (style) {
			case HYPE -> shake = Math.max(shake, 0.35f);
			case DARK, RETRO -> chroma = Math.max(chroma, 0.4f);
			default -> { }
		}
		burst(w, h, 2);
	}

	void update(float dt, boolean inChorus, float w, float h) {
		introAge += dt;
		if (style == null) return;
		flash = Draw.approach(flash, 0f, dt * 3.2f);
		shake = Draw.approach(shake, 0f, dt * 2.6f);
		punch = Draw.approach(punch, 0f, dt * 2.2f);
		lines = Draw.approach(lines, 0f, dt * 2.5f);
		chroma = Draw.approach(chroma, 0f, dt * 2.4f);
		slam = Draw.approach(slam, 0f, dt * 5f);
		boolean wantBars = inChorus && style != EditSongs.Style.POP && style != EditSongs.Style.LOVE || introAge < 3f;
		bars = Draw.approach(bars, wantBars ? 1f : 0f, dt * 2.5f);
		grade = Draw.approach(grade, inChorus ? 1f : 0.25f, dt * 1.5f);

		// ambient particles that fit the song
		float rate = switch (style) {
			case SAD -> inChorus ? 40f : 18f;
			case LOVE -> inChorus ? 5f : 2f;
			case DARK -> inChorus ? 9f : 3f;
			case POP -> inChorus ? 4f : 1f;
			default -> 0f;
		};
		if (rng.nextFloat() < rate * dt) spawnAmbient(w, h);
		for (P p : parts) {
			p.age += dt;
			p.x += p.vx * dt;
			p.y += p.vy * dt;
		}
		parts.removeIf(p -> p.age >= p.life || p.y > h + 20 || p.y < -20);
	}

	// ------------------------------------------------------------------ what panels use

	float shakeX(float time) {
		return shake * shake * 4.5f * (float) (Math.sin(time * 71.3) + Math.sin(time * 43.1) * 0.5);
	}

	float shakeY(float time) {
		return shake * shake * 3.5f * (float) (Math.cos(time * 67.7) + Math.sin(time * 31.9) * 0.5);
	}

	/** Extra size for the current panel: a zoom punch that settles. */
	float punchScale() {
		return 1f + 0.22f * Draw.easeOutCubic(punch);
	}

	/** Words slam in from big when the chorus hits (0..1). */
	float slam() {
		return slam;
	}

	float chroma() {
		return chroma;
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * Colour grade and letterbox bars. They're drawn by a separate HUD layer underneath the
	 * hotbar and the Now Playing card (see {@link #drawUnder}), so here we only hand them over.
	 */
	void drawBehind(GuiGraphicsExtractor g, float vis) {
		if (style == null || vis < 0.02f) {
			under = null;
			return;
		}
		int tint = switch (style) {
			case DARK -> 0x5A0010;
			case SAD -> 0x0A1840;
			case LOVE -> 0x6A1A48;
			case RETRO -> 0x3A2410;
			case HYPE -> 0x2A0A00;
			case POP -> 0x000000;
		};
		float a = style == EditSongs.Style.POP ? 0f : (style == EditSongs.Style.SAD ? 0.22f : 0.16f) * grade * vis;
		under = new Under(tint, a, Draw.easeOutCubic(bars) * vis,
				style == EditSongs.Style.RETRO && grade > 0.3f ? 0.08f * grade * vis : 0f, System.nanoTime());
	}

	private record Under(int tint, float grade, float bars, float scan, long at) {}

	private static volatile Under under;

	/** The underlay: drawn before the hotbar and crosshair, so the game's HUD stays on top. */
	public static void drawUnder(GuiGraphicsExtractor g) {
		Under u = under;
		if (u == null || System.nanoTime() - u.at() > 250_000_000L) return; // Echoes isn't drawing anymore
		int w = g.guiWidth(), h = g.guiHeight();
		if (u.grade() > 0.005f) {
			g.fill(0, 0, w, h, Draw.argb(u.tint(), u.grade()));
			int edge = Math.round(Math.min(w, h) * 0.25f);
			Sprites.draw(g, Sprites.edgeL(), 0, 0, edge, h, Draw.argb(u.tint(), 1.6f * u.grade()));
			Sprites.draw(g, Sprites.edgeR(), w - edge, 0, edge, h, Draw.argb(u.tint(), 1.6f * u.grade()));
		}
		if (u.scan() > 0.005f) Sprites.region(g, Sprites.scanlines(), 0, 0, w, h, 0, 0, 1, h, Draw.argb(0xFFFFFF, u.scan()));
		if (u.bars() > 0.01f) {
			int bh = Math.round(h * 0.075f * u.bars());
			g.fill(0, 0, w, bh, Draw.argb(0x000000, 0.9f * Math.min(1f, u.bars() * 1.2f)));
			g.fill(0, h - bh, w, h, Draw.argb(0x000000, 0.9f * Math.min(1f, u.bars() * 1.2f)));
		}
	}

	/** Over everything: flash, speed lines, particles and the title card. */
	void drawFront(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, float vis, float time) {
		if (style == null || vis < 0.02f) return;
		int w = g.guiWidth(), h = g.guiHeight();

		if (lines > 0.02f) {
			float cx = w / 2f, cy = h / 2f, r0 = Math.min(w, h) * 0.32f, r1 = Math.max(w, h) * 0.75f;
			for (int i = 0; i < 18; i++) {
				double a = i * (Math.PI * 2 / 18) + time * 0.7 + (i % 3) * 0.11;
				float in = r0 * (0.9f + 0.25f * (float) Math.sin(time * 13 + i));
				Draw.line(g, cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in,
						cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, 1.5f,
						Draw.argb(0xFFFFFF, 0.35f * lines * vis));
			}
		}

		for (P p : parts) drawPart(g, font, p, vis);

		if (flash > 0.01f) g.fill(0, 0, w, h, Draw.argb(flashColor, 0.42f * flash * flash * vis));

		if (introAge < 3.2f && introTitle != null) {
			float t = introAge;
			float a = Math.min(1f, t / 0.35f) * Math.min(1f, (3.2f - t) / 0.6f) * vis;
			float s = 2.2f * (1f + 0.06f * (1f - Draw.easeOutCubic(Math.min(1f, t / 0.6f))));
			String title = Draw.ellipsize(font, introTitle.toUpperCase(java.util.Locale.ROOT), Math.round(w * 0.8f / s));
			float tw = font.width(title) * s, ty = h * 0.2f;
			int col = switch (style) {
				case DARK -> 0xFF4A55;
				case SAD -> 0xCFE0FF;
				case LOVE -> 0xFF9AD0;
				case RETRO -> 0xFFD27A;
				case POP -> Draw.hsv(time * 90f, 0.5f, 1f);
				case HYPE -> 0xFFFFFF;
			};
			// a quick colour split on the way in
			float split = (1f - Math.min(1f, t / 0.5f)) * 3f;
			if (split > 0.2f) {
				Draw.text(g, font, title, w / 2f - tw / 2f - split, ty, s, Draw.argb(0xFF3B6B, 0.5f * a), false);
				Draw.text(g, font, title, w / 2f - tw / 2f + split, ty, s, Draw.argb(0x3BE8FF, 0.5f * a), false);
			}
			Draw.glowText(g, font, title, w / 2f - tw / 2f, ty, s, col, a, 0.8f);
			if (introArtist != null && !introArtist.isBlank()) {
				String artist = Draw.ellipsize(font, introArtist, Math.round(w * 0.8f));
				float aw = font.width(artist);
				Draw.text(g, font, artist, w / 2f - aw / 2f, ty + font.lineHeight * s + 4, 1f, Draw.argb(0xE8E8F0, 0.85f * a), true);
			}
		}
	}

	// ------------------------------------------------------------------ particles

	private void burst(float w, float h, int n) {
		for (int i = 0; i < n; i++) {
			P p = make(w, h);
			if (p == null) return;
			// out from the middle of the screen
			p.x = w / 2f + (rng.nextFloat() - 0.5f) * w * 0.3f;
			p.y = h / 2f + (rng.nextFloat() - 0.5f) * h * 0.2f;
			double a = rng.nextDouble() * Math.PI * 2;
			float sp = 60 + rng.nextFloat() * 140;
			p.vx = (float) Math.cos(a) * sp;
			p.vy = (float) Math.sin(a) * sp - 30;
			if (style == EditSongs.Style.SAD) {
				p.vx = 0;
				p.vy = 160 + rng.nextFloat() * 80;
				p.y = -10;
				p.x = rng.nextFloat() * w;
			}
			parts.add(p);
		}
	}

	private void spawnAmbient(float w, float h) {
		P p = make(w, h);
		if (p == null) return;
		p.x = rng.nextFloat() * w;
		switch (style) {
			case SAD -> { // rain
				p.y = -10;
				p.vx = -12;
				p.vy = 170 + rng.nextFloat() * 90;
			}
			case LOVE, POP -> { // float up from the bottom
				p.y = h + 8;
				p.vx = (rng.nextFloat() - 0.5f) * 20;
				p.vy = -(25 + rng.nextFloat() * 30);
			}
			default -> { // embers drifting up
				p.y = h + 4;
				p.vx = (rng.nextFloat() - 0.5f) * 30;
				p.vy = -(35 + rng.nextFloat() * 45);
			}
		}
		parts.add(p);
	}

	private P make(float w, float h) {
		if (parts.size() >= MAX_PARTS) return null;
		P p = new P();
		p.life = 1.6f + rng.nextFloat() * 1.8f;
		p.size = 0.7f + rng.nextFloat() * 0.8f;
		p.spin = rng.nextFloat() * 6.28f;
		switch (style) {
			case LOVE -> {
				p.kind = 'h';
				p.color = rng.nextBoolean() ? 0xFF6FAE : 0xFFB3D6;
				p.life = 4f;
			}
			case POP -> {
				p.kind = 's';
				p.color = Draw.hsv(rng.nextFloat() * 360, 0.55f, 1f);
				p.life = 3f;
			}
			case SAD -> {
				p.kind = 'r';
				p.color = 0xBFD4FF;
				p.life = 2.5f;
			}
			case DARK -> {
				p.kind = 'e';
				p.color = rng.nextBoolean() ? 0xFF3A2A : 0xFF8A3A;
				p.life = 3f;
			}
			case HYPE -> {
				p.kind = 'd';
				p.color = rng.nextBoolean() ? 0xFFFFFF : 0xFFD86A;
			}
			default -> {
				p.kind = 'd';
				p.color = 0xFFE6B0;
			}
		}
		return p;
	}

	private static void drawPart(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, P p, float vis) {
		float u = p.age / p.life;
		float a = Math.min(1f, p.age / 0.2f) * (1f - u * u) * vis;
		if (a < 0.02f) return;
		switch (p.kind) {
			case 'h', 's' -> {
				String s = p.kind == 'h' ? "♥" : "✦";
				float sc = p.size * (p.kind == 'h' ? 1.4f : 1.1f);
				float wob = (float) Math.sin(p.age * 3 + p.spin) * 4;
				Sprites.glow(g, p.x + wob - 6 * sc, p.y - 6 * sc, 12 * sc, 12 * sc, Draw.argb(p.color, 0.35f * a));
				Draw.text(g, font, s, p.x + wob - 2.5f * sc, p.y - 4 * sc, sc, Draw.argb(p.color, 0.9f * a), false);
			}
			case 'r' -> Draw.line(g, p.x, p.y, p.x - 2, p.y + 9, 1f, Draw.argb(p.color, 0.35f * a));
			case 'e' -> {
				float r = 1.2f * p.size;
				Sprites.glow(g, p.x - r * 4, p.y - r * 4, r * 8, r * 8, Draw.argb(p.color, 0.5f * a));
				Sprites.circle(g, p.x, p.y, r, Draw.argb(0xFFE0B0, 0.9f * a));
			}
			default -> {
				float r = 1.3f * p.size;
				Sprites.glow(g, p.x - r * 4, p.y - r * 4, r * 8, r * 8, Draw.argb(p.color, 0.45f * a));
				Sprites.circle(g, p.x, p.y, r, Draw.argb(0xFFFFFF, 0.9f * a));
			}
		}
	}
}
