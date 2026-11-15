package dev.lyricraft.hud.style;

/** One way of showing lyrics. Each style keeps its own animation state. */
public interface LyricStyle {
	void render(Frame f);

	/** Whether the small Now Playing card should be shown with this style. */
	default boolean showsNowPlayingCard() {
		return true;
	}

	/** Tracks when the current line changed, for enter/exit animations. */
	final class LineClock {
		private int index = Integer.MIN_VALUE;
		private int previous = -1;
		private long changedAt;

		/** Call each frame; returns true on the frame the line changed. */
		public boolean update(int newIndex, long now) {
			if (newIndex == index) return false;
			previous = index == Integer.MIN_VALUE ? -1 : index;
			changedAt = index == Integer.MIN_VALUE ? now - 10_000_000_000L : now;
			index = newIndex;
			return true;
		}

		public int previous() {
			return previous;
		}

		/** Seconds since the line changed. */
		public float age(long now) {
			return (now - changedAt) / 1e9f;
		}
	}
}
