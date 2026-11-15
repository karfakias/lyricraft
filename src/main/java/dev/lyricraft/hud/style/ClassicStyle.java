package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;

/** The original look: a scrolling lyric sheet with a karaoke sweep, placed on the HUD. */
public final class ClassicStyle implements LyricStyle {

	private static final float CURRENT_SCALE = 1.45f;
	private static final float CONTEXT_SCALE = 0.95f;
	private final LineClock clock = new LineClock();
	private int direction = 1;

	@Override
	public void render(Frame f) {
		int slot = f.slot();
		int prevSlot = clock.update(slot, f.nowNanos()) ? clock.previous() : Integer.MIN_VALUE;
		if (prevSlot != Integer.MIN_VALUE) direction = slot >= prevSlot ? 1 : -1;
		float age = clock.age(f.nowNanos());
		float ease = Draw.easeOutCubic(age / 0.28f);
		float fadeIn = Math.clamp(age / 0.45f, 0f, 1f);

		float s = f.cfg().scale;
		int lineH = f.font().lineHeight;
		float slotCur = lineH * CURRENT_SCALE * s, slotCtx = lineH * CONTEXT_SCALE * s, gap = 4 * s;
		float blockH = slotCtx + gap + slotCur + gap + slotCtx;

		int sw = f.width(), sh = f.height();
		float top, anchorX;
		Draw.Align align;
		switch (f.cfg().position) {
			case TOP_CENTER -> { top = 22; align = Draw.Align.CENTER; anchorX = sw / 2f; }
			case LEFT_SIDE -> { top = sh / 2f - blockH / 2f; align = Draw.Align.LEFT; anchorX = 10; }
			case RIGHT_SIDE -> { top = sh / 2f - blockH / 2f; align = Draw.Align.RIGHT; anchorX = sw - 10; }
			case CUSTOM -> {
				top = f.cfg().lyricsY * sh - (slotCtx + gap + slotCur / 2f);
				align = Draw.Align.CENTER;
				anchorX = f.cfg().lyricsX * sw;
			}
			default -> { top = sh - 74 - blockH; align = Draw.Align.CENTER; anchorX = sw / 2f; }
		}
		float maxWidth = align == Draw.Align.CENTER ? sw - 30 : sw * 0.45f;
		// dragged off-centre: don't let long lines run off the screen edge
		if (f.cfg().position == dev.lyricraft.Config.Position.CUSTOM) maxWidth = Math.max(80f, 2f * Math.min(anchorX, sw - anchorX) - 12f);
		float scroll = (1f - ease) * (slotCur + gap) * direction;
		float vis = f.vis();

		if (f.cfg().showContextLines) {
			plain(f, f.line(slot - 1), anchorX, top + scroll, CONTEXT_SCALE * s, maxWidth, align, 0.38f * vis * ease);
			plain(f, f.line(slot + 1), anchorX, top + slotCtx + gap + slotCur + gap + scroll, CONTEXT_SCALE * s, maxWidth, align, 0.5f * vis * fadeIn);
		}

		Lyrics.Line line = f.line(slot);
		if (line == null) return;
		float y = top + slotCtx + gap + scroll;
		boolean inBreak = f.active() < 0;
		float progress = inBreak ? 0f : line.progress(f.posMs());
		float alpha = vis * Math.max(fadeIn, 0.25f);

		if (inBreak) {
			long until = line.startMs() - f.posMs();
			if (until > 1800) {
				BreakDots.draw(f, anchorX, y, CURRENT_SCALE * s, align, vis);
				return;
			}
			alpha = vis * (0.35f + 0.4f * (1f - Math.clamp(until / 1800f, 0f, 1f)));
		}
		int w = f.font().width(line.text());
		float scale = Draw.fitScale(w, CURRENT_SCALE * s, maxWidth);
		Draw.karaoke(f.g(), f.font(), line.text(), Draw.alignX(anchorX, w * scale, align), y, scale,
				progress, alpha, f.accent(), 0xFFFFFF, 0.55f, true, 0f);
	}

	private static void plain(Frame f, Lyrics.Line line, float anchorX, float y, float scale, float maxW, Draw.Align align, float alpha) {
		if (line == null || alpha < 0.02f) return;
		int w = f.font().width(line.text());
		scale = Draw.fitScale(w, scale, maxW);
		Draw.text(f.g(), f.font(), line.text(), Draw.alignX(anchorX, w * scale, align), y, scale, Draw.argb(0xFFFFFF, alpha), true);
	}
}
