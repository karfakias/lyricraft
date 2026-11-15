package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.lyrics.Lyrics;
import org.joml.Vector3f;

/**
 * HOLOGRAM: a glowing sci-fi panel hovering in front of you. It trails your view with a little
 * lag when you turn, has scanlines, corner brackets and the odd flicker.
 */
public final class HologramStyle implements LyricStyle {

	private static final float DISTANCE = 2.6f;
	private static final float TEXT_BLOCKS = 0.17f;

	private final LineClock clock = new LineClock();
	private Vector3f anchor; // camera-relative
	private float flicker = 1f;

	@Override
	public void render(Frame f) {
		Projector p = f.proj();
		if (!p.valid()) return;

		Vector3f target = p.forward().mul(DISTANCE);
		target.y -= 0.25f;
		if (anchor == null) anchor = new Vector3f(target);
		float k = 1f - (float) Math.exp(-f.dt() * 5.5f);
		anchor.lerp(target, k);

		Projector.Point pt = p.projectRelative(anchor.x, anchor.y, anchor.z);
		if (pt == null || pt.pxPerBlock() <= 0) return;

		int slot = f.slot();
		clock.update(slot, f.nowNanos());
		float age = clock.age(f.nowNanos());

		// random little flickers
		if (Math.random() < f.dt() * 0.6) flicker = 0.35f;
		flicker = Draw.approach(flicker, 1f, f.dt() * 5f);
		float vis = f.vis() * flicker;

		float scale = pt.pxPerBlock() * TEXT_BLOCKS * f.cfg().scale / f.font().lineHeight;
		Lyrics.Line line = f.line(slot);
		Lyrics.Line next = f.line(slot + 1);
		String cur = line != null ? line.text() : "";
		int textW = Math.max(f.font().width(cur), 150);
		float maxW = f.width() * 0.8f;
		scale = Draw.fitScale(textW, scale, maxW);

		float lh = f.font().lineHeight * scale;
		float panelW = textW * scale + 24 * scale;
		float panelH = lh * 2.9f;
		float x0 = pt.x() - panelW / 2f, y0 = pt.y() - panelH / 2f;
		int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x0 + panelW), iy1 = Math.round(y0 + panelH);
		int acc = f.accent();

		// panel body + scanlines
		f.g().fill(ix0, iy0, ix1, iy1, Draw.argb(0x04140A, 0.45f * vis));
		int scanOffset = (int) (f.time() * 12) % 3;
		for (int y = iy0 + scanOffset; y < iy1; y += 3) f.g().fill(ix0, y, ix1, y + 1, Draw.argb(acc, 0.05f * vis));
		// edges
		f.g().fill(ix0, iy0, ix1, iy0 + 1, Draw.argb(acc, 0.7f * vis));
		f.g().fill(ix0, iy1 - 1, ix1, iy1, Draw.argb(acc, 0.7f * vis));
		int c = Math.max(4, Math.round(8 * scale));
		bracket(f, ix0, iy0, c, 1, 1, vis);
		bracket(f, ix1, iy0, c, -1, 1, vis);
		bracket(f, ix0, iy1, c, 1, -1, vis);
		bracket(f, ix1, iy1, c, -1, -1, vis);

		// header
		float hs = scale * 0.55f;
		boolean blink = ((int) (f.time() * 2)) % 2 == 0;
		Draw.text(f.g(), f.font(), (blink ? "● " : "  ") + "LYRICRAFT", x0 + 6 * scale, y0 + 3 * scale, hs, Draw.argb(acc, 0.8f * vis), false);

		if (line == null) return;
		boolean inBreak = f.active() < 0;
		float enter = Draw.easeOutCubic(age / 0.35f);
		float lineY = y0 + lh * 0.85f + (1f - enter) * 4 * scale;
		float lw = f.font().width(cur) * scale;
		if (inBreak && line.startMs() - f.posMs() > 1800) {
			BreakDots.draw(f, pt.x(), lineY, scale, Draw.Align.CENTER, vis);
		} else {
			float progress = inBreak ? 0f : line.progress(f.posMs());
			Draw.karaoke(f.g(), f.font(), cur, pt.x() - lw / 2f, lineY, scale, progress, vis * enter,
					acc, 0xD8FFE6, 0.5f, false, 0.8f);
		}
		if (next != null && f.cfg().showContextLines) {
			float ns = scale * 0.62f;
			String nt = Draw.ellipsize(f.font(), next.text(), Math.round(textW * 1.4f));
			float nw = f.font().width(nt) * ns;
			Draw.text(f.g(), f.font(), nt, pt.x() - nw / 2f, lineY + lh * 1.25f, ns, Draw.argb(0xD8FFE6, 0.38f * vis * enter), false);
		}
	}

	private static void bracket(Frame f, int x, int y, int len, int dx, int dy, float vis) {
		int col = Draw.argb(0xFFFFFF, 0.85f * vis);
		int xs = dx > 0 ? x : x - len, ys = dy > 0 ? y : y - 2;
		f.g().fill(xs, ys, xs + len, ys + 2, col);
		int vx = dx > 0 ? x : x - 2, vy = dy > 0 ? y : y - len;
		f.g().fill(vx, vy, vx + 2, vy + len, col);
	}
}
