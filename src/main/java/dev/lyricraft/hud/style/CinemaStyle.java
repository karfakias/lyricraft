package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.MediaSnapshot;

/**
 * CINEMA: black letterbox bars slide in, the song title sits in the top bar, and the lyrics
 * appear as big movie subtitles. A thin progress line runs along the bottom bar.
 */
public final class CinemaStyle implements LyricStyle {

	private final LineClock clock = new LineClock();

	@Override
	public boolean showsNowPlayingCard() {
		return false;
	}

	@Override
	public void render(Frame f) {
		int w = f.width(), h = f.height();
		float vis = f.vis();
		int bar = Math.round(h * 0.1f * Draw.easeOutCubic(vis));
		int acc = f.accent();

		f.g().fill(0, 0, w, bar, Draw.argb(0x000000, 0.93f));
		f.g().fill(0, h - bar, w, h, Draw.argb(0x000000, 0.93f));

		MediaSnapshot track = f.track();
		if (track != null && bar > 10) {
			String head = "♪  " + track.title() + "  ·  " + track.artist();
			head = Draw.ellipsize(f.font(), head, w - 40);
			float hs = 0.9f;
			float hw = f.font().width(head) * hs;
			Draw.text(f.g(), f.font(), head, w / 2f - hw / 2f, bar / 2f - 4, hs, Draw.argb(0xB3B3B3, vis), false);
			if (track.durationMs() > 0) {
				float p = Math.clamp(f.songPositionMs() / (float) track.durationMs(), 0f, 1f);
				f.g().fill(0, h - bar, Math.round(w * p), h - bar + 1, Draw.argb(acc, 0.9f * vis));
			}
		}

		int slot = f.slot();
		clock.update(slot, f.nowNanos());
		float age = clock.age(f.nowNanos());
		float s = 1.9f * f.cfg().scale;
		float baseY = h - bar - f.font().lineHeight * s - 10;

		// previous line fades out quickly
		Lyrics.Line prev = f.line(clock.previous());
		if (prev != null && age < 0.3f) {
			float t = age / 0.3f;
			subtitle(f, prev.text(), baseY - t * 6, s, 1f, vis * (1f - t), 0.4f);
		}

		Lyrics.Line line = f.line(slot);
		if (line == null) return;
		boolean inBreak = f.active() < 0;
		if (inBreak && line.startMs() - f.posMs() > 1800) {
			BreakDots.draw(f, w / 2f, baseY, s * 0.8f, Draw.Align.CENTER, vis);
			return;
		}
		float enter = Math.clamp(age / 0.3f, 0f, 1f);
		subtitle(f, line.text(), baseY, s, inBreak ? 0f : line.progress(f.posMs()), vis * enter * (inBreak ? 0.5f : 1f), 0.6f);
	}

	private static void subtitle(Frame f, String text, float y, float s, float progress, float alpha, float glow) {
		int tw = f.font().width(text);
		s = Draw.fitScale(tw, s, f.width() - 40);
		float x = f.width() / 2f - tw * s / 2f;
		Draw.karaoke(f.g(), f.font(), text, x, y, s, progress, alpha, f.accent(), 0xFFFFFF, 0.92f, false, glow);
	}
}
