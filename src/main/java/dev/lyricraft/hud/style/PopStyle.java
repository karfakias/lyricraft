package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;

import java.util.List;

/**
 * POP: words appear one by one exactly as they're sung, each popping in with a bouncy overshoot.
 * The word being sung glows in the accent colour and floats up a bit.
 */
public final class PopStyle implements LyricStyle {

	private final LineClock clock = new LineClock();

	@Override
	public void render(Frame f) {
		int slot = f.slot();
		clock.update(slot, f.nowNanos());
		float age = clock.age(f.nowNanos());
		float s = 1.6f * f.cfg().scale;
		boolean custom = f.cfg().position == dev.lyricraft.Config.Position.CUSTOM;
		float cx = custom ? f.cfg().lyricsX * f.width() : f.width() / 2f;
		float y = switch (f.cfg().position) {
			case TOP_CENTER -> 28;
			case LEFT_SIDE, RIGHT_SIDE -> f.height() / 2f;
			case CUSTOM -> f.cfg().lyricsY * f.height() - 4.5f * s;
			default -> f.height() - 92;
		};

		// previous line drifts up and fades as a whole
		Lyrics.Line prev = f.line(clock.previous());
		if (prev != null && age < 0.5f) {
			float t = age / 0.5f;
			drawWords(f, prev, 1f, cx, y - Draw.easeOutCubic(t) * 14, s, f.vis() * (1f - t), true);
		}

		Lyrics.Line line = f.line(slot);
		if (line == null) return;
		if (f.active() < 0) {
			if (line.startMs() - f.posMs() > 1500) BreakDots.draw(f, cx, y, s, Draw.Align.CENTER, f.vis());
			return;
		}
		drawWords(f, line, line.progress(f.posMs()), cx, y, s, f.vis(), false);
	}

	private static void drawWords(Frame f, Lyrics.Line line, float progress, float cx, float y, float s, float alpha, boolean finished) {
		if (alpha < 0.02f) return;
		List<Lyrics.Word> words = line.words();
		String full = String.join(" ", words.stream().map(Lyrics.Word::text).toList());
		int fullW = f.font().width(full);
		s = Draw.fitScale(fullW, s, Place.maxWidth(f));
		float x = cx - fullW * s / 2f;
		float space = f.font().width(" ") * s;
		float lh = f.font().lineHeight * s;

		for (Lyrics.Word w : words) {
			float ww = f.font().width(w.text()) * s;
			if (finished || progress >= w.start()) {
				float ageMs = finished ? 10_000 : (progress - w.start()) * line.durationMs();
				boolean current = !finished && progress < w.end();
				float pop = Draw.easeOutBack(ageMs / 240f);
				float a = alpha * Math.clamp(ageMs / 90f, 0f, 1f);
				float ws = s * (0.55f + 0.45f * pop) * (current ? 1.08f : 1f);
				float wwScaled = f.font().width(w.text()) * ws;
				float lift = current ? 3f * s / 1.6f : 0f;
				float wx = x + ww / 2f - wwScaled / 2f;
				float wy = y + lh / 2f - f.font().lineHeight * ws / 2f - lift;
				int rgb = current ? f.accent() : 0xFFFFFF;
				Draw.glowText(f.g(), f.font(), w.text(), wx, wy, ws, rgb, a, current ? 1f : 0f);
			}
			x += ww + space;
		}
	}
}
