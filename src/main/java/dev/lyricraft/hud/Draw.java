package dev.lyricraft.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small drawing helpers shared by every style. */
public final class Draw {
	private Draw() {}

	public enum Align { LEFT, CENTER, RIGHT }

	public static int argb(int rgb, float alpha) {
		int a = Math.clamp(Math.round(alpha * 255f), 0, 255);
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	/** HSV (h in degrees, s/v 0..1) to RGB. */
	public static int hsv(float h, float s, float v) {
		h = ((h % 360f) + 360f) % 360f;
		float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
		float r, g, b;
		if (h < 60) { r = c; g = x; b = 0; }
		else if (h < 120) { r = x; g = c; b = 0; }
		else if (h < 180) { r = 0; g = c; b = x; }
		else if (h < 240) { r = 0; g = x; b = c; }
		else if (h < 300) { r = x; g = 0; b = c; }
		else { r = c; g = 0; b = x; }
		return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
	}

	/** Blend two RGB colours. */
	public static int mix(int a, int b, float t) {
		t = Math.clamp(t, 0f, 1f);
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	// ------------------------------------------------------------ shapes (pixel-style)

	/** Filled circle drawn row by row; looks nicely pixel-art at GUI scale. */
	public static void circle(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
		ring(g, cx, cy, r, 0, color);
	}

	/** Filled ring between {@code inner} and {@code outer} radius. */
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float outer, float inner, int color) {
		if ((color >>> 24) < 2 || outer <= 0) return;
		int r = (int) Math.ceil(outer);
		for (int dy = -r; dy < r; dy++) {
			float yc = dy + 0.5f;
			float o2 = outer * outer - yc * yc;
			if (o2 <= 0) continue;
			int ox = Math.round((float) Math.sqrt(o2));
			int y = Math.round(cy) + dy;
			float i2 = inner * inner - yc * yc;
			if (inner <= 0 || i2 <= 0) {
				g.fill(Math.round(cx) - ox, y, Math.round(cx) + ox, y + 1, color);
			} else {
				int ix = Math.round((float) Math.sqrt(i2));
				g.fill(Math.round(cx) - ox, y, Math.round(cx) - ix, y + 1, color);
				g.fill(Math.round(cx) + ix, y, Math.round(cx) + ox, y + 1, color);
			}
		}
	}

	/** A straight line of the given thickness between two points (any angle). */
	public static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thickness, int color) {
		if ((color >>> 24) < 2) return;
		float dx = x2 - x1, dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.5f) return;
		g.pose().pushMatrix();
		g.pose().translate(x1, y1);
		g.pose().rotate((float) Math.atan2(dy, dx));
		g.pose().scale(1f, thickness);
		g.fill(0, 0, Math.round(len), 1, color);
		g.pose().popMatrix();
	}

	public static float easeOutCubic(float t) {
		t = Math.clamp(t, 0f, 1f);
		float u = 1f - t;
		return 1f - u * u * u;
	}

	/** Overshoots a little before settling: nice for "pop" animations. */
	public static float easeOutBack(float t) {
		t = Math.clamp(t, 0f, 1f);
		float c1 = 1.9f, c3 = c1 + 1f;
		float u = t - 1f;
		return 1f + c3 * u * u * u + c1 * u * u;
	}

	public static float approach(float cur, float target, float step) {
		return cur < target ? Math.min(target, cur + step) : Math.max(target, cur - step);
	}

	public static void text(GuiGraphicsExtractor g, Font font, String text, float x, float y, float scale, int color, boolean shadow) {
		if ((color >>> 24) < 4 || scale <= 0.01f) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, text, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	/** Text with a soft coloured halo (drawn as faint offset copies). */
	public static void glowText(GuiGraphicsExtractor g, Font font, String text, float x, float y, float scale, int rgb, float alpha, float glow) {
		if (glow > 0.01f) {
			float o = Math.max(0.6f, scale * 0.9f);
			int halo = argb(rgb, alpha * glow * 0.22f);
			text(g, font, text, x - o, y, scale, halo, false);
			text(g, font, text, x + o, y, scale, halo, false);
			text(g, font, text, x, y - o, scale, halo, false);
			text(g, font, text, x, y + o, scale, halo, false);
		}
		text(g, font, text, x, y, scale, argb(rgb, alpha), true);
	}

	/**
	 * Karaoke line: dim base text, then the accent colour revealed left-to-right up to {@code progress}.
	 *
	 * @return the screen rectangle the line occupies {x0, y0, x1, y1}
	 */
	public static int[] karaoke(GuiGraphicsExtractor g, Font font, String text, float x, float y, float scale,
	                            float progress, float alpha, int accentRgb, int baseRgb, float baseAlpha,
	                            boolean backdrop, float glow) {
		float drawW = font.width(text) * scale;
		int padX = Math.round(6 * scale), padY = Math.round(2 * scale);
		int bx0 = Math.round(x) - padX, by0 = Math.round(y) - padY;
		int bx1 = Math.round(x + drawW) + padX, by1 = Math.round(y + font.lineHeight * scale) + padY;

		if (backdrop) g.fill(bx0, by0, bx1, by1, argb(0x000000, 0.30f * alpha));
		text(g, font, text, x, y, scale, argb(baseRgb, baseAlpha * alpha), true);

		if (progress > 0f) {
			int fillTo = Math.round(x + drawW * progress);
			g.enableScissor(bx0 - 4, by0 - 4, fillTo, by1 + 4);
			glowText(g, font, text, x, y, scale, accentRgb, alpha, glow);
			g.disableScissor();
			if (backdrop) {
				int uy = by1 - 1;
				g.fill(bx0 + padX, uy, fillTo, uy + 1, argb(accentRgb, 0.85f * alpha));
				if (progress < 1f) g.fill(fillTo - 1, uy - 1, fillTo + 1, uy + 2, argb(0xFFFFFF, 0.9f * alpha));
			}
		}
		return new int[]{bx0, by0, bx1, by1};
	}

	public static float fitScale(int textWidth, float scale, float maxWidth) {
		if (textWidth > 0 && textWidth * scale > maxWidth) return Math.max(0.35f, maxWidth / textWidth);
		return scale;
	}

	public static float alignX(float anchorX, float width, Align align) {
		return switch (align) {
			case LEFT -> anchorX;
			case CENTER -> anchorX - width / 2f;
			case RIGHT -> anchorX - width;
		};
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (s == null) return "";
		if (font.width(s) <= maxW) return s;
		String dots = "…";
		int cut = s.length();
		while (cut > 0 && font.width(s.substring(0, cut)) + font.width(dots) > maxW) cut--;
		return s.substring(0, cut) + dots;
	}
}
