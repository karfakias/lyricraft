package dev.lyricraft.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lyricraft.Config;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.LyricsAnchor;
import dev.lyricraft.hud.LyricsHud;
import dev.lyricraft.hud.NowPlayingCard;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Dragging the Now Playing card and the lyrics around the screen. Shared by the main menu
 * (drag right on the game next to the panel) and the full-screen layout editor.
 *
 * Left-drag moves, scroll resizes, right-click puts it back where it started.
 * Snaps to the screen edges and centre.
 */
final class LayoutDrag {

	enum Target { NONE, CARD, LYRICS }

	private static final int SNAP = 5;

	private final Config cfg;
	private Target dragging = Target.NONE;
	private float grabX, grabY;
	private float guideX = -1, guideY = -1;

	LayoutDrag(Config cfg) {
		this.cfg = cfg;
	}

	boolean isDragging() {
		return dragging != Target.NONE;
	}

	// ------------------------------------------------------------------ hit areas

	private boolean cardAvailable() {
		return cfg.showNowPlaying && NowPlayingCard.drawnRecently();
	}

	private boolean lyricsAvailable() {
		return cfg.enabled && LyricsAnchor.movable(cfg.style);
	}

	/** x0, y0, x1, y1 of the card on screen. */
	private static float[] cardBox() {
		float[] r = NowPlayingCard.lastRect;
		return new float[]{r[0] - 2, r[1] - 2, r[0] + r[2] + 2, r[1] + r[3] + 2};
	}

	private float[] lyricsBox(int w, int h) {
		float[] c = LyricsAnchor.center(cfg, w, h), s = LyricsAnchor.size(cfg, w);
		return new float[]{c[0] - s[0] / 2f, c[1] - s[1] / 2f - 3, c[0] + s[0] / 2f, c[1] + s[1] / 2f + 3};
	}

	Target hit(double mx, double my, int w, int h) {
		// the card sits on top, so it wins when they overlap
		if (cardAvailable() && inside(cardBox(), mx, my, 3)) return Target.CARD;
		if (lyricsAvailable() && inside(lyricsBox(w, h), mx, my, 3)) return Target.LYRICS;
		return Target.NONE;
	}

	private static boolean inside(float[] b, double mx, double my, float pad) {
		return mx >= b[0] - pad && mx <= b[2] + pad && my >= b[1] - pad && my <= b[3] + pad;
	}

	// ------------------------------------------------------------------ mouse

	boolean press(MouseButtonEvent e, int w, int h) {
		Target t = hit(e.x(), e.y(), w, h);
		if (t == Target.NONE) return false;
		// note: since 26.x the left button is 1 and the right button is 3 (InputConstants)
		if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			reset(t);
			return true;
		}
		if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		dragging = t;
		if (t == Target.CARD) {
			grabX = (float) e.x() - NowPlayingCard.lastRect[0];
			grabY = (float) e.y() - NowPlayingCard.lastRect[1];
		} else {
			float[] c = LyricsAnchor.center(cfg, w, h);
			grabX = (float) e.x() - c[0];
			grabY = (float) e.y() - c[1];
			// from now on the lyrics use the dragged spot
			cfg.lyricsX = Math.clamp(c[0] / w, 0.1f, 0.9f);
			cfg.lyricsY = Math.clamp(c[1] / h, 0.05f, 0.95f);
			cfg.position = Config.Position.CUSTOM;
		}
		return true;
	}

	boolean drag(MouseButtonEvent e, int w, int h) {
		if (dragging == Target.CARD) moveCard((float) e.x() - grabX, (float) e.y() - grabY, w, h, true);
		else if (dragging == Target.LYRICS) moveLyrics((float) e.x() - grabX, (float) e.y() - grabY, w, h);
		else return false;
		return true;
	}

	boolean release() {
		if (dragging == Target.NONE) return false;
		dragging = Target.NONE;
		guideX = guideY = -1;
		cfg.save();
		return true;
	}

	boolean scroll(double mx, double my, double amount, int w, int h) {
		if (amount == 0) return false;
		Target t = hit(mx, my, w, h);
		if (t == Target.CARD) {
			cfg.cardScale = Math.clamp(Math.round((cfg.cardScale + (float) amount * 0.05f) * 20f) / 20f, 0.5f, 1.5f);
		} else if (t == Target.LYRICS) {
			cfg.scale = Math.clamp(Math.round((cfg.scale + (float) amount * 0.05f) * 20f) / 20f, 0.5f, 3f);
		} else return false;
		cfg.save();
		return true;
	}

	/** Nudge whatever is under the mouse by one pixel (arrow keys). */
	boolean nudge(double mx, double my, int dx, int dy, int w, int h) {
		Target t = hit(mx, my, w, h);
		if (t == Target.NONE && cardAvailable()) t = Target.CARD;
		if (t == Target.CARD) {
			float[] r = NowPlayingCard.lastRect;
			moveCard(r[0] + dx, r[1] + dy, w, h, false);
		} else if (t == Target.LYRICS) {
			float[] c = LyricsAnchor.center(cfg, w, h);
			cfg.position = Config.Position.CUSTOM;
			moveLyrics(c[0] + dx, c[1] + dy, w, h);
		} else return false;
		guideX = guideY = -1;
		cfg.save();
		return true;
	}

	void reset(Target t) {
		if (t == Target.CARD) {
			cfg.cardX = 0f;
			cfg.cardY = 0f;
		} else if (t == Target.LYRICS) {
			cfg.position = Config.Position.ABOVE_HOTBAR;
			cfg.lyricsX = 0.5f;
			cfg.lyricsY = 0.84f;
		}
		cfg.save();
	}

	private void moveCard(float x, float y, int w, int h, boolean snap) {
		float cw = NowPlayingCard.lastRect[2], ch = NowPlayingCard.lastRect[3];
		int m = NowPlayingCard.MARGIN;
		guideX = guideY = -1;
		if (snap) {
			float[] xs = {m, (w - cw) / 2f, w - cw - m}, gx = {m, w / 2f, w - m};
			for (int i = 0; i < 3; i++) if (Math.abs(x - xs[i]) < SNAP) { x = xs[i]; guideX = gx[i]; }
			float[] ys = {m, (h - ch) / 2f, h - ch - m}, gy = {m, h / 2f, h - m};
			for (int i = 0; i < 3; i++) if (Math.abs(y - ys[i]) < SNAP) { y = ys[i]; guideY = gy[i]; }
		}
		float freeW = Math.max(1, w - cw - m * 2), freeH = Math.max(1, h - ch - m * 2);
		cfg.cardX = Math.clamp((x - m) / freeW, 0f, 1f);
		cfg.cardY = Math.clamp((y - m) / freeH, 0f, 1f);
	}

	private void moveLyrics(float cx, float cy, int w, int h) {
		guideX = guideY = -1;
		if (Math.abs(cx - w / 2f) < SNAP) { cx = w / 2f; guideX = cx; }
		if (Math.abs(cy - h / 2f) < SNAP) { cy = h / 2f; guideY = cy; }
		cfg.lyricsX = Math.clamp(cx / w, 0.1f, 0.9f);
		cfg.lyricsY = Math.clamp(cy / h, 0.05f, 0.95f);
	}

	// ------------------------------------------------------------------ drawing

	/**
	 * @param always draw both outlines all the time (layout editor); otherwise only on hover/drag
	 */
	void draw(GuiGraphicsExtractor g, Font font, int w, int h, int mx, int my, boolean always) {
		int acc = Widgets.accent.getAsInt();
		if (guideX >= 0) g.fill(Math.round(guideX), 0, Math.round(guideX) + 1, h, Draw.argb(acc, 0.7f));
		if (guideY >= 0) g.fill(0, Math.round(guideY), w, Math.round(guideY) + 1, Draw.argb(acc, 0.7f));
		Target hover = dragging != Target.NONE ? dragging : hit(mx, my, w, h);
		int phase = (int) (System.nanoTime() / 80_000_000L);

		if (lyricsAvailable() && (always || hover == Target.LYRICS)) {
			float[] b = lyricsBox(w, h);
			boolean on = hover == Target.LYRICS;
			if (!LyricsHud.lyricsOnScreen) {
				// nothing being sung right now: show a sample so you can see what you're moving
				float[] c = LyricsAnchor.center(cfg, w, h);
				String sample = "♪ Your lyrics show up here ♪";
				float sc = Math.min(1.25f * cfg.scale, (b[2] - b[0]) / Math.max(1, font.width(sample)));
				Draw.text(g, font, sample, c[0] - font.width(sample) * sc / 2f, c[1] - 4.5f * sc, sc, Draw.argb(0xFFFFFF, 0.75f), true);
			}
			outline(g, font, b, on ? acc : 0xFFFFFF, on ? 1f : 0.45f, phase, "Lyrics  " + Math.round(cfg.scale * 100) + "%", h);
		}
		if (cardAvailable() && (always || hover == Target.CARD)) {
			float[] b = cardBox();
			boolean on = hover == Target.CARD;
			outline(g, font, b, on ? acc : 0xFFFFFF, on ? 1f : 0.45f, phase, "Card  " + Math.round(cfg.cardScale * 100) + "%", h);
		}
		if (!always && hover != Target.NONE && dragging == Target.NONE) {
			String tip = "Drag to move · Scroll to resize · Right-click to reset";
			int tw = font.width(tip);
			int tx = Math.clamp(mx + 10, 2, w - tw - 8), ty = Math.clamp(my + 14, 2, h - 14);
			Widgets.panel(g, tx - 4, ty - 3, tx + tw + 4, ty + 11, Draw.argb(0x0A0A0E, 0.9f));
			g.text(font, tip, tx, ty, 0xFFE0E0E8, false);
		}
	}

	private static void outline(GuiGraphicsExtractor g, Font font, float[] b, int rgb, float alpha, int phase, String label, int screenH) {
		int x0 = Math.round(b[0]), y0 = Math.round(b[1]), x1 = Math.round(b[2]), y1 = Math.round(b[3]);
		int col = Draw.argb(rgb, alpha);
		int dash = 4;
		for (int x = x0 + (phase % (dash * 2)) - dash * 2; x < x1; x += dash * 2) {
			int a = Math.max(x0, x), e = Math.min(x1, x + dash);
			if (e > a) {
				g.fill(a, y0, e, y0 + 1, col);
				g.fill(a, y1 - 1, e, y1, col);
			}
		}
		for (int y = y0 + (phase % (dash * 2)) - dash * 2; y < y1; y += dash * 2) {
			int a = Math.max(y0, y), e = Math.min(y1, y + dash);
			if (e > a) {
				g.fill(x0, a, x0 + 1, e, col);
				g.fill(x1 - 1, a, x1, e, col);
			}
		}
		int ly = y1 + 3 + 9 > screenH ? y0 - 11 : y1 + 3;
		Draw.text(g, font, label, x0, ly, 0.75f, Draw.argb(rgb, Math.max(0.6f, alpha)), true);
	}
}
