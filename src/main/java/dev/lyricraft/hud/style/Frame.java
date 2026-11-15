package dev.lyricraft.hud.style;

import dev.lyricraft.Config;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Everything a style needs to draw one frame.
 *
 * @param posMs   playback position (offset already applied)
 * @param nowNanos {@link System#nanoTime()}
 * @param dt      seconds since last frame
 * @param vis     0..1 overall fade of the lyrics display
 * @param accent  accent colour RGB
 * @param songPositionMs raw playback position (no offset), for progress bars
 */
public record Frame(GuiGraphicsExtractor g, Font font, Lyrics lyrics, long posMs, long nowNanos, float dt,
                    float vis, Config cfg, Projector proj, int accent,
                    MediaSnapshot track, long songPositionMs,
                    float pulse, int colorA, int colorB) {

	public int width() {
		return g.guiWidth();
	}

	public int height() {
		return g.guiHeight();
	}

	/** Index of the line being sung, or -1 in a break. */
	public int active() {
		return lyrics.activeIndex(posMs);
	}

	/** The line that "owns" the screen: the active one, or during a break the upcoming one. */
	public int slot() {
		int a = lyrics.activeIndex(posMs);
		return a >= 0 ? a : lyrics.lastStartedIndex(posMs) + 1;
	}

	public Lyrics.Line line(int i) {
		return i >= 0 && i < lyrics.lines.size() ? lyrics.lines.get(i) : null;
	}

	/** Seconds as a float, handy for wobble/bob animations. */
	public float time() {
		return (nowNanos % 3_600_000_000_000L) / 1e9f;
	}
}
