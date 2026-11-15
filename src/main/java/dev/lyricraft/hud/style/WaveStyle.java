package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;

/**
 * WAVE: the letters ride a travelling wave. Sung letters turn into a flowing rainbow and bounce
 * harder on every beat; the letter being sung right now jumps up and grows.
 */
public final class WaveStyle implements LyricStyle {

	private final LineClock clock = new LineClock();

	@Override
	public void render(Frame f) {
		int slot = f.slot();
		clock.update(slot, f.nowNanos());
		float age = clock.age(f.nowNanos());
		float s = 1.7f * f.cfg().scale;
		float y = switch (f.cfg().position) {
			case TOP_CENTER -> 30;
			case LEFT_SIDE, RIGHT_SIDE -> f.height() / 2f - 10;
			case CUSTOM -> f.cfg().lyricsY * f.height() - 4.5f * s;
			default -> f.height() - 92;
		};

		// previous line: letters fall away and fade
		Lyrics.Line prev = f.line(clock.previous());
		if (prev != null && age < 0.6f) drawWave(f, prev.text(), y, s, 1f, f.vis() * (1f - age / 0.6f), age / 0.6f);

		Lyrics.Line line = f.line(slot);
		if (line == null) return;
		if (f.active() < 0) {
			if (line.startMs() - f.posMs() > 1500) {
				BreakDots.draw(f, Place.centerX(f), y, s, Draw.Align.CENTER, f.vis());
				return;
			}
			drawWave(f, line.text(), y, s, 0f, f.vis() * 0.5f, 0f);
			return;
		}
		float enter = Draw.easeOutCubic(age / 0.3f);
		drawWave(f, line.text(), y + (1f - enter) * 8, s, line.progress(f.posMs()), f.vis() * enter, 0f);
	}

	private static void drawWave(Frame f, String text, float y, float s, float progress, float alpha, float fall) {
		if (alpha < 0.02f) return;
		Letters ls = Letters.of(f.font(), text);
		s = Draw.fitScale(ls.totalWidth(), s, Place.maxWidth(f));
		float x0 = Place.centerX(f) - ls.totalWidth() * s / 2f;
		float t = f.time();
		float beat = f.pulse();
		int idx = 0;

		for (Letters.Letter l : ls.letters()) {
			idx++;
			if (l.space()) continue;
			boolean sung = progress > 0f && progress >= l.at();
			float since = (progress - l.at()) * 10f; // ~0..1 right after being sung
			boolean leading = sung && since < 1f && fall == 0f;

			float amp = (sung ? 2.6f + beat * 3.5f : 0.8f) * s / 1.7f;
			float wave = (float) Math.sin(t * 5.0 - idx * 0.45) * amp;
			float jump = leading ? (1f - since) * 5f * s / 1.7f : 0f;
			float drop = fall * fall * 30f + (float) Math.sin(idx * 2.3) * fall * 6f;
			float ls2 = leading ? s * (1f + 0.3f * (1f - since)) : s;

			float lx = x0 + l.x() * s - (l.width() * (ls2 - s)) / 2f;
			float ly = y - wave - jump + drop - f.font().lineHeight * (ls2 - s) / 2f;

			if (sung || fall > 0f) {
				int rgb = Draw.hsv(t * 70f + idx * 14f, 0.7f, 1f);
				rgb = Draw.mix(rgb, f.colorA(), 0.25f);
				Draw.glowText(f.g(), f.font(), l.ch(), lx, ly, ls2, rgb, alpha, leading ? 1.2f : 0.5f);
			} else {
				Draw.text(f.g(), f.font(), l.ch(), lx, ly, ls2, Draw.argb(0xFFFFFF, 0.45f * alpha), true);
			}
		}
	}
}
