package dev.lyricraft.hud.style;

/** Shared placement helpers for the flat on-screen styles. */
final class Place {
	private Place() {}

	/** Centre x of the lyrics (follows the dragged position). */
	static float centerX(Frame f) {
		return f.cfg().position == dev.lyricraft.Config.Position.CUSTOM ? f.cfg().lyricsX * f.width() : f.width() / 2f;
	}

	/** Widest a line may be without running off the screen from {@link #centerX}. */
	static float maxWidth(Frame f) {
		float cx = centerX(f);
		return Math.max(80f, 2f * Math.min(cx, f.width() - cx) - 20f);
	}
}
