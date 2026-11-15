package dev.lyricraft.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A frosted glass panel: rounded corners, a body tinted by the album colour, a soft frosty
 * highlight at the top, a fine grain, a light sheen that sweeps across now and then, and a
 * glowing edge that can pulse.
 */
public final class GlassPanel {

	private GlassPanel() {}

	/**
	 * @param tint  album colour the glass is tinted with
	 * @param edge  colour of the glowing rim
	 * @param vis   overall opacity 0..1
	 * @param glow  how strong the outer glow is (0 = none, 1 = normal, more on a beat)
	 * @param seed  0..1, so panels don't all shimmer in sync
	 * @param time  seconds, for the sheen
	 */
	public static void draw(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int tint, int edge,
	                        float vis, float glow, float seed, float time) {
		if (vis < 0.02f || x1 - x0 < 4 || y1 - y0 < 2) return;
		int h = y1 - y0;
		int r = Math.clamp(Math.round(h * 0.18f), 1, 5);

		// outer glow: a few soft rings around the panel
		if (glow > 0.01f) {
			for (int i = 1; i <= 4; i++) {
				float a = 0.11f * glow * vis / i;
				rounded(g, x0 - i, y0 - i, x1 + i, y1 + i, r + i, Draw.argb(edge, a), true);
			}
		}

		// body: tinted, darker at the bottom
		int top = Draw.mix(tint, 0x0B0D14, 0.70f), bottom = Draw.mix(tint, 0x05060A, 0.86f);
		rounded(g, x0, y0, x1, y1, r, Draw.argb(top, 0.50f * vis), false);
		g.fillGradient(x0, y0 + r, x1, y1 - r, Draw.argb(top, 0.14f * vis), Draw.argb(bottom, 0.30f * vis));

		// frost: brighter upper half, fading down
		int mid = y0 + h / 2;
		g.fillGradient(x0 + 1, y0 + 1, x1 - 1, mid, Draw.argb(0xFFFFFF, 0.10f * vis), Draw.argb(0xFFFFFF, 0f));

		// grain: a fixed scatter of faint specks, different for every panel
		int w = x1 - x0;
		int specks = Math.min(70, w * h / 90);
		long s = (long) (seed * 1_000_003) + 7;
		for (int i = 0; i < specks; i++) {
			s = s * 6364136223846793005L + 1442695040888963407L;
			int px = x0 + 2 + (int) ((s >>> 33) % Math.max(1, w - 4));
			int py = y0 + 2 + (int) ((s >>> 17) % Math.max(1, h - 4));
			g.fill(px, py, px + 1, py + 1, Draw.argb(0xFFFFFF, 0.06f * vis));
		}

		// sheen: a soft diagonal band that sweeps across every few seconds
		float u = ((time * 0.22f + seed * 3f) % 2.2f) / 2.2f;
		if (u < 0.6f) {
			float k = u / 0.6f;
			int sx = Math.round(x0 - h + (w + 2 * h) * k);
			g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
			for (int y = y0; y < y1; y++) {
				int off = (y - y0) / 2;
				g.fill(sx - off, y, sx - off + Math.max(3, h / 3), y + 1, Draw.argb(0xFFFFFF, 0.07f * vis));
			}
			g.disableScissor();
		}

		// rim: bright thin line on top, softer at the bottom and sides
		g.fill(x0 + r, y0, x1 - r, y0 + 1, Draw.argb(Draw.mix(edge, 0xFFFFFF, 0.55f), 0.75f * vis));
		g.fill(x0 + r, y1 - 1, x1 - r, y1, Draw.argb(edge, 0.45f * vis));
		g.fill(x0, y0 + r, x0 + 1, y1 - r, Draw.argb(edge, 0.30f * vis));
		g.fill(x1 - 1, y0 + r, x1, y1 - r, Draw.argb(edge, 0.30f * vis));
	}

	/** A rounded rectangle, filled or as a 1px outline. */
	private static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int r, int color, boolean outline) {
		if (outline) {
			g.fill(x0 + r, y0, x1 - r, y0 + 1, color);
			g.fill(x0 + r, y1 - 1, x1 - r, y1, color);
			g.fill(x0, y0 + r, x0 + 1, y1 - r, color);
			g.fill(x1 - 1, y0 + r, x1, y1 - r, color);
			for (int i = 0; i < r; i++) {
				int in = inset(r, i);
				g.fill(x0 + in, y0 + i, x0 + in + 1, y0 + i + 1, color);
				g.fill(x1 - in - 1, y0 + i, x1 - in, y0 + i + 1, color);
				g.fill(x0 + in, y1 - i - 1, x0 + in + 1, y1 - i, color);
				g.fill(x1 - in - 1, y1 - i - 1, x1 - in, y1 - i, color);
			}
			return;
		}
		g.fill(x0, y0 + r, x1, y1 - r, color);
		for (int i = 0; i < r; i++) {
			int in = inset(r, i);
			g.fill(x0 + in, y0 + i, x1 - in, y0 + i + 1, color);
			g.fill(x0 + in, y1 - i - 1, x1 - in, y1 - i, color);
		}
	}

	private static int inset(int r, int row) {
		float dy = r - row - 0.5f;
		return Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
	}
}
