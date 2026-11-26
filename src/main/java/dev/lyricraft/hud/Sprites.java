package dev.lyricraft.hud;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Small textures made in memory, so shapes that used to take hundreds of rectangles a frame
 * (circles, rings, soft glows, gradients, glass panels) are drawn with one textured quad each.
 * Everything is white with alpha unless said otherwise, and gets its colour from the tint.
 */
public final class Sprites {
	private Sprites() {}

	/** A texture with smooth (linear) filtering, optionally repeating. */
	public static final class Tex extends DynamicTexture {
		final Identifier id;
		final int w, h;

		public Identifier id() {
			return id;
		}
		final boolean repeat;

		Tex(String name, NativeImage img, boolean repeat) {
			super(() -> "lyricraft:" + name, img);
			this.id = Identifier.fromNamespaceAndPath("lyricraft", "dyn/" + name);
			this.w = img.getWidth();
			this.h = img.getHeight();
			this.repeat = repeat;
			Minecraft.getInstance().getTextureManager().register(id, this);
		}

		@Override
		public GpuSampler getSampler() {
			var cache = RenderSystem.getSamplerCache();
			return repeat ? cache.getRepeat(FilterMode.LINEAR) : cache.getClampToEdge(FilterMode.LINEAR);
		}

		public void release() {
			Minecraft.getInstance().getTextureManager().release(id);
		}
	}

	/** Builds a texture from a function returning ARGB for each pixel. */
	public static Tex make(String name, int w, int h, boolean repeat, IntBinaryOperator argb) {
		NativeImage img = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) img.setPixel(x, y, argb.applyAsInt(x, y));
		Tex t = new Tex(name, img, repeat);
		t.upload();
		return t;
	}

	// ------------------------------------------------------------------ drawing

	/** Draws the whole texture stretched over (x, y, w, h), at any sub-pixel position. */
	public static void draw(GuiGraphicsExtractor g, Tex t, float x, float y, float w, float h, int argb) {
		region(g, t, x, y, w, h, 0, 0, t.w, t.h, argb);
	}

	/** Draws part of a texture (u, v, uw, vh in texture pixels) stretched over (x, y, w, h). */
	public static void region(GuiGraphicsExtractor g, Tex t, float x, float y, float w, float h,
	                          float u, float v, float uw, float vh, int argb) {
		if ((argb >>> 24) < 2 || w <= 0 || h <= 0 || uw <= 0 || vh <= 0) return;
		// blit works in whole pixels, so draw a 1:1 region and stretch it with the pose
		int iuw = Math.max(1, Math.round(uw)), ivh = Math.max(1, Math.round(vh));
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / iuw, h / ivh);
		g.blit(RenderPipelines.GUI_TEXTURED, t.id, 0, 0, u, v, iuw, ivh, iuw, ivh, t.w, t.h, argb);
		g.pose().popMatrix();
	}

	/**
	 * Nine-slice: corners stay square-on, edges stretch one way, the middle stretches both.
	 * {@code border} is in texture pixels, {@code edge} is how big it's drawn on screen.
	 */
	public static void nine(GuiGraphicsExtractor g, Tex t, float x, float y, float w, float h, int border, float edge, int argb) {
		if ((argb >>> 24) < 2) return;
		float e = Math.min(edge, Math.min(w, h) / 2f);
		float mw = w - 2 * e, mh = h - 2 * e;
		int b = border, tm = t.w - 2 * b, tmh = t.h - 2 * b;
		// corners
		region(g, t, x, y, e, e, 0, 0, b, b, argb);
		region(g, t, x + w - e, y, e, e, t.w - b, 0, b, b, argb);
		region(g, t, x, y + h - e, e, e, 0, t.h - b, b, b, argb);
		region(g, t, x + w - e, y + h - e, e, e, t.w - b, t.h - b, b, b, argb);
		if (mw > 0.01f) {
			region(g, t, x + e, y, mw, e, b, 0, tm, b, argb);
			region(g, t, x + e, y + h - e, mw, e, b, t.h - b, tm, b, argb);
		}
		if (mh > 0.01f) {
			region(g, t, x, y + e, e, mh, 0, b, b, tmh, argb);
			region(g, t, x + w - e, y + e, e, mh, t.w - b, b, b, tmh, argb);
		}
		if (mw > 0.01f && mh > 0.01f) region(g, t, x + e, y + e, mw, mh, b, b, tm, tmh, argb);
	}

	// ------------------------------------------------------------------ shared shapes

	private static Tex disc, softDot, edgeL, edgeR, band, scan;
	private static final Map<Integer, Tex> RINGS = new LinkedHashMap<>(16, 0.75f, true);

	/** A solid anti-aliased circle. */
	public static Tex disc() {
		if (disc == null) disc = make("disc", 128, 128, false, (x, y) -> white(cover(x, y, 64, 64f, 0f)));
		return disc;
	}

	/** A soft round glow, bright in the middle and fading to nothing. */
	public static Tex softDot() {
		if (softDot == null) softDot = make("soft_dot", 64, 64, false, (x, y) -> {
			float dx = (x + 0.5f - 32) / 32f, dy = (y + 0.5f - 32) / 32f;
			float d = (float) Math.sqrt(dx * dx + dy * dy);
			float a = Math.max(0f, 1f - d);
			return white(a * a * (3 - 2 * a));
		});
		return softDot;
	}

	/** A ring whose hole is {@code inner} (0..1) of its size, anti-aliased. */
	public static Tex ring(float inner) {
		int key = Math.clamp(Math.round(inner * 512), 0, 512);
		Tex t = RINGS.get(key);
		if (t != null) return t;
		float in = key / 512f;
		t = make("ring_" + key, 256, 256, false, (x, y) -> white(cover(x, y, 128, 128f, in * 128f)));
		RINGS.put(key, t);
		if (RINGS.size() > 48) {
			var it = RINGS.entrySet().iterator();
			it.next().getValue().release();
			it.remove();
		}
		return t;
	}

	/** Horizontal fade, opaque on the left (edgeL) or right (edgeR), with a soft (squared) falloff. */
	public static Tex edgeL() {
		if (edgeL == null) edgeL = make("edge_l", 64, 1, false, (x, y) -> {
			float f = 1f - (x + 0.5f) / 64f;
			return white(f * f);
		});
		return edgeL;
	}

	public static Tex edgeR() {
		if (edgeR == null) edgeR = make("edge_r", 64, 1, false, (x, y) -> {
			float f = (x + 0.5f) / 64f;
			return white(f * f);
		});
		return edgeR;
	}

	/** A soft vertical band (bright in the middle), for sheens. */
	public static Tex band() {
		if (band == null) band = make("band", 32, 1, false, (x, y) -> {
			float u = (x + 0.5f) / 32f;
			return white((float) Math.pow(Math.sin(u * Math.PI), 2));
		});
		return band;
	}

	/** Repeating scanlines: one dark row, one clear row. */
	public static Tex scanlines() {
		if (scan == null) scan = make("scan", 1, 2, true, (x, y) -> y == 0 ? 0xFF000000 : 0x00000000);
		return scan;
	}

	/** Circle helper: one quad instead of a fill per row. */
	public static void circle(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
		if (r <= 0) return;
		draw(g, disc(), cx - r, cy - r, r * 2, r * 2, argb);
	}

	/** Ring helper: one quad instead of two fills per row. */
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float outer, float inner, int argb) {
		if (outer <= 0) return;
		if (inner <= 0) {
			circle(g, cx, cy, outer, argb);
			return;
		}
		draw(g, ring(inner / outer), cx - outer, cy - outer, outer * 2, outer * 2, argb);
	}

	/** Soft glow behind something, as one quad. */
	public static void glow(GuiGraphicsExtractor g, float x, float y, float w, float h, int argb) {
		draw(g, softDot(), x, y, w, h, argb);
	}

	// ------------------------------------------------------------------ helpers

	/** How much of pixel (x, y) is inside a ring around (c, c) between radius inner and outer (2x2 supersampled). */
	static float cover(int x, int y, int c, float outer, float inner) {
		float sum = 0;
		for (int sy = 0; sy < 2; sy++)
			for (int sx = 0; sx < 2; sx++) {
				float dx = x + 0.25f + sx * 0.5f - c, dy = y + 0.25f + sy * 0.5f - c;
				float d = (float) Math.sqrt(dx * dx + dy * dy);
				// soften the edge over about a pixel
				float o = Math.clamp(outer - d + 0.5f, 0f, 1f);
				float i = inner <= 0 ? 1f : Math.clamp(d - inner + 0.5f, 0f, 1f);
				sum += o * i;
			}
		return sum / 4f;
	}

	static int white(float a) {
		return (Math.clamp(Math.round(a * 255f), 0, 255) << 24) | 0xFFFFFF;
	}
}
