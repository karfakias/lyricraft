package dev.lyricraft.hud;

import dev.lyricraft.Config;

/**
 * Where the on-screen lyrics sit, so they can be dragged around in the menu. The numbers here
 * mirror what Classic, Pop, Neon and Wave do for each preset position.
 */
public final class LyricsAnchor {
	private LyricsAnchor() {}

	/** Styles that are drawn flat on the screen (the others live in the world or fill the screen). */
	public static boolean movable(Config.Style s) {
		return s == Config.Style.CLASSIC || s == Config.Style.POP || s == Config.Style.NEON || s == Config.Style.WAVE;
	}

	/** Text scale of the current line for a style. */
	private static float textScale(Config cfg) {
		return switch (cfg.style) {
			case NEON -> 1.9f;
			case WAVE -> 1.7f;
			case POP -> 1.6f;
			default -> 1.45f;
		} * cfg.scale;
	}

	/** Centre of the current lyric line on screen. */
	public static float[] center(Config cfg, int w, int h) {
		if (cfg.position == Config.Position.CUSTOM) return new float[]{cfg.lyricsX * w, cfg.lyricsY * h};
		float s = textScale(cfg);
		if (cfg.style == Config.Style.CLASSIC) {
			float k = cfg.scale, slotCur = 9 * 1.45f * k, slotCtx = 9 * 0.95f * k, gap = 4 * k;
			float blockH = 2 * slotCtx + 2 * gap + slotCur, toLine = slotCtx + gap + slotCur / 2f;
			float bw = size(cfg, w)[0];
			return switch (cfg.position) {
				case TOP_CENTER -> new float[]{w / 2f, 22 + toLine};
				case LEFT_SIDE -> new float[]{10 + bw / 2f, h / 2f};
				case RIGHT_SIDE -> new float[]{w - 10 - bw / 2f, h / 2f};
				default -> new float[]{w / 2f, h - 74 - blockH + toLine};
			};
		}
		float top = switch (cfg.style) {
			case POP -> switch (cfg.position) {
				case TOP_CENTER -> 28;
				case LEFT_SIDE, RIGHT_SIDE -> h / 2f;
				default -> h - 92;
			};
			case NEON -> switch (cfg.position) {
				case TOP_CENTER -> 30;
				case LEFT_SIDE, RIGHT_SIDE -> h / 2f - 10;
				default -> h - 96;
			};
			default -> switch (cfg.position) { // WAVE
				case TOP_CENTER -> 30;
				case LEFT_SIDE, RIGHT_SIDE -> h / 2f - 10;
				default -> h - 92;
			};
		};
		return new float[]{w / 2f, top + 4.5f * s};
	}

	/** Size of the drag handle around the current line: {width, height}. */
	public static float[] size(Config cfg, int w) {
		float s = textScale(cfg);
		return new float[]{Math.min(w * 0.7f, 110 * s), 11 * s};
	}
}
