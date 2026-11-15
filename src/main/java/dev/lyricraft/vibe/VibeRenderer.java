package dev.lyricraft.vibe;

import dev.lyricraft.Config;
import dev.lyricraft.hud.Draw;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Screen-wide mood lighting:
 * - colour grade: the whole world gets washed in the song's colour (FULL)
 * - a coloured vignette that breathes with every word and flashes on new lines
 * - an aurora rippling across the top of the screen
 * - a little equalizer either side of the hotbar (FULL)
 */
public final class VibeRenderer {

	private float intensity; // eased 0..1

	public void render(GuiGraphicsExtractor g, VibeEngine vibe, Config cfg, boolean active, float dt, float time) {
		float target = active && cfg.vibe != Config.Vibe.OFF ? 1f : 0f;
		intensity = Draw.approach(intensity, target, dt * 1.5f);
		if (intensity < 0.01f) return;

		boolean full = cfg.vibe == Config.Vibe.FULL;
		int w = g.guiWidth(), h = g.guiHeight();
		int a = vibe.colorA(), b = vibe.colorB();
		float pulse = vibe.pulse(System.nanoTime());
		float k = intensity * cfg.vibeStrength;

		// 1) colour grade: dim slightly and tint everything, like club lighting
		if (full && cfg.vibeTint) {
			g.fill(0, 0, w, h, Draw.argb(0x05030A, 0.16f * k));
			float sweep = 0.5f + 0.5f * (float) Math.sin(time * 0.35);
			g.fill(0, 0, w, h, Draw.argb(Draw.mix(a, b, sweep), (0.07f + 0.05f * pulse) * k));
		}

		// 2) breathing vignette
		if (cfg.vibeEdgeGlow) drawEdgeGlow(g, w, h, a, b, pulse, full, k);

		// 3) aurora ribbons across the top
		if (cfg.vibeAurora) {
			float auroraA = (full ? 0.24f : 0.08f) * k;
			drawAurora(g, w, h, time, a, auroraA * (1f + pulse * 0.6f), 0.9f, 0f);
			drawAurora(g, w, h, time, b, auroraA * 0.8f, 1.35f, 2.1f);
		}

		// 4) equalizer bars beside the hotbar
		if (full && cfg.vibeEqualizer) drawEqualizer(g, w, h, time, a, b, pulse, vibe.energy(), k);
	}

	private static void drawEdgeGlow(GuiGraphicsExtractor g, int w, int h, int a, int b, float pulse, boolean full, float k) {
		float vigA = (full ? 0.14f : 0.05f) + pulse * (full ? 0.40f : 0.26f);
		int edge = Math.round(Math.min(w, h) * (full ? 0.22f : 0.16f));
		g.fillGradient(0, 0, w, edge, Draw.argb(a, vigA * k), Draw.argb(a, 0f));
		g.fillGradient(0, h - edge, w, h, Draw.argb(b, 0f), Draw.argb(b, vigA * k));
		int strips = 20;
		float stripW = edge / (float) strips;
		for (int i = 0; i < strips; i++) {
			float f = 1f - i / (float) strips;
			int col = Draw.argb(Draw.mix(a, b, 0.5f), vigA * f * f * k);
			int x0 = Math.round(i * stripW), x1 = Math.round((i + 1) * stripW);
			g.fill(x0, 0, x1, h, col);
			g.fill(w - x1, 0, w - x0, h, col);
		}
	}

	private static void drawAurora(GuiGraphicsExtractor g, int w, int h, float t, int rgb, float alpha, float speed, float phase) {
		int step = 3;
		float baseH = h * 0.16f;
		for (int x = 0; x < w; x += step) {
			float u = x / (float) w;
			double wave = Math.sin(u * 7 + t * speed + phase) * 0.5
					+ Math.sin(u * 13 - t * speed * 1.7 + phase * 2) * 0.3
					+ Math.sin(u * 3 + t * 0.4) * 0.2;
			float height = baseH * (0.55f + 0.45f * (float) wave);
			float shimmer = 0.6f + 0.4f * (float) Math.sin(u * 40 + t * 3 + phase);
			g.fillGradient(x, 0, x + step, Math.max(1, Math.round(height)), Draw.argb(rgb, alpha * shimmer), Draw.argb(rgb, 0f));
		}
	}

	private static void drawEqualizer(GuiGraphicsExtractor g, int w, int h, float t, int a, int b, float pulse, float energy, float k) {
		int hotbarHalf = 100;
		int bars = 14, barW = 4, gap = 2;
		for (int side = 0; side < 2; side++) {
			for (int i = 0; i < bars; i++) {
				float n = (float) (Math.sin(t * (5 + i * 0.9) + i * 1.7 + side) * 0.5 + 0.5);
				float n2 = (float) (Math.sin(t * (2.3 + i * 0.37) + i) * 0.5 + 0.5);
				float height = 3 + (n * 0.6f + n2 * 0.4f) * (8 + energy * 10) * (0.6f + pulse * 0.8f);
				int x = side == 0
						? w / 2 - hotbarHalf - 8 - (i + 1) * (barW + gap)
						: w / 2 + hotbarHalf + 8 + i * (barW + gap);
				int y1 = h - 3, y0 = Math.round(y1 - height);
				int col = Draw.mix(a, b, i / (float) bars);
				g.fillGradient(x, y0, x + barW, y1, Draw.argb(col, 0.85f * k), Draw.argb(col, 0.25f * k));
			}
		}
	}
}
