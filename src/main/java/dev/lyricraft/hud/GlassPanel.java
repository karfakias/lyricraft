package dev.lyricraft.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A frosted glass panel: rounded corners, a body tinted by the album colour, a soft frosty
 * highlight at the top, a light sheen that sweeps across now and then, and a glowing edge
 * that can pulse.
 *
 * The glass is baked into a small texture per colour pair and drawn as a nine-slice, and the
 * glow is one shared soft texture, so a panel is about 20 quads however big it is.
 */
public final class GlassPanel {

	private GlassPanel() {}

	private static final int SIZE = 32, BORDER = 8, RADIUS = 6;
	private static final int GLOW_SIZE = 40, GLOW_BORDER = 16, GLOW_SPREAD = 12;
	private static final Map<Long, Sprites.Tex> GLASS = new LinkedHashMap<>(16, 0.75f, true);
	private static Sprites.Tex glow;

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
		int w = x1 - x0, h = y1 - y0;
		float r = Math.clamp(h * 0.18f, 1f, 5f);
		float cornerOnScreen = r * BORDER / (float) RADIUS;

		// outer glow
		if (glow > 0.01f) {
			float spread = 4f;
			float e = spread * GLOW_BORDER / (float) GLOW_SPREAD;
			int a = Draw.argb(edge, Math.min(1f, 0.30f * glow * vis));
			Sprites.nine(g, glowTex(), x0 - spread, y0 - spread, w + spread * 2, h + spread * 2, GLOW_BORDER, e, a);
		}

		// the glass itself
		Sprites.nine(g, glass(tint & 0xFFFFFF, edge & 0xFFFFFF), x0, y0, w, h, BORDER, cornerOnScreen,
				Draw.argb(0xFFFFFF, vis));

		// sheen: a soft band that sweeps across every few seconds
		float u = ((time * 0.22f + seed * 3f) % 2.2f) / 2.2f;
		if (u < 0.6f) {
			float bw = Math.max(4f, h * 0.6f);
			float k = u / 0.6f;
			float bx = x0 - bw + (w + bw) * k;
			float cx0 = Math.max(bx, x0 + 1), cx1 = Math.min(bx + bw, x1 - 1);
			if (cx1 > cx0) {
				Sprites.Tex band = Sprites.band();
				float u0 = (cx0 - bx) / bw * 32f, u1 = (cx1 - bx) / bw * 32f;
				Sprites.region(g, band, cx0, y0 + 1, cx1 - cx0, h - 2, u0, 0, u1 - u0, 1, Draw.argb(0xFFFFFF, 0.08f * vis));
			}
		}
	}

	/** Baked glass for one tint/edge colour pair. */
	private static Sprites.Tex glass(int tint, int edge) {
		long key = ((long) tint << 24) | edge;
		Sprites.Tex t = GLASS.get(key);
		if (t != null) return t;
		int top = Draw.mix(tint, 0x0B0D14, 0.70f), bottom = Draw.mix(tint, 0x05060A, 0.86f);
		int rimTop = Draw.mix(edge, 0xFFFFFF, 0.55f);
		t = Sprites.make("glass_" + Long.toHexString(key), SIZE, SIZE, false, (x, y) -> {
			float v = y / (float) (SIZE - 1);
			int rgb = Draw.mix(top, bottom, v);
			float a = 0.56f + 0.12f * v;
			// frost: brighter upper half
			if (v < 0.5f) rgb = Draw.mix(rgb, 0xFFFFFF, 0.10f * (1f - v * 2f));
			// rim: bright line on top, softer at the bottom and sides (between the corners)
			boolean inCornerX = x < RADIUS || x >= SIZE - RADIUS, inCornerY = y < RADIUS || y >= SIZE - RADIUS;
			if (y == 0 && !inCornerX) {
				rgb = Draw.mix(rgb, rimTop, 0.75f);
				a = Math.max(a, 0.85f);
			} else if (y == SIZE - 1 && !inCornerX) {
				rgb = Draw.mix(rgb, edge, 0.45f);
				a = Math.max(a, 0.7f);
			} else if ((x == 0 || x == SIZE - 1) && !inCornerY) {
				rgb = Draw.mix(rgb, edge, 0.30f);
			}
			a *= roundedCover(x, y, SIZE, SIZE, RADIUS);
			return (Math.clamp(Math.round(a * 255f), 0, 255) << 24) | rgb;
		});
		GLASS.put(key, t);
		if (GLASS.size() > 24) {
			var it = GLASS.entrySet().iterator();
			it.next().getValue().release();
			it.remove();
		}
		return t;
	}

	/** Soft glow around a rounded rectangle, nothing inside it. */
	private static Sprites.Tex glowTex() {
		if (glow == null) {
			int in0 = GLOW_SPREAD, in1 = GLOW_SIZE - GLOW_SPREAD, rr = 4;
			glow = Sprites.make("glass_glow", GLOW_SIZE, GLOW_SIZE, false, (x, y) -> {
				float px = x + 0.5f, py = y + 0.5f;
				// distance to the rounded rectangle from in0..in1
				float cx = Math.clamp(px, in0 + rr, in1 - rr), cy = Math.clamp(py, in0 + rr, in1 - rr);
				float d = (float) Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)) - rr;
				if (d <= 0) return 0x00FFFFFF;
				float f = Math.max(0f, 1f - d / GLOW_SPREAD);
				return Sprites.white(f * f);
			});
		}
		return glow;
	}

	/** Coverage of pixel (x, y) by a w x h rounded rectangle with corner radius r (2x2 supersampled). */
	private static float roundedCover(int x, int y, int w, int h, int r) {
		float sum = 0;
		for (int sy = 0; sy < 2; sy++)
			for (int sx = 0; sx < 2; sx++) {
				float px = x + 0.25f + sx * 0.5f, py = y + 0.25f + sy * 0.5f;
				float cx = Math.clamp(px, r, w - r), cy = Math.clamp(py, r, h - r);
				float d = (float) Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
				sum += d <= r ? 1f : 0f;
			}
		return sum / 4f;
	}
}
