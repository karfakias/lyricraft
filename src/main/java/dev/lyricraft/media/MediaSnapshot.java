package dev.lyricraft.media;

/**
 * One reading of "what is playing right now", taken from the operating system.
 *
 * @param app       which app reported it (e.g. "Spotify.exe", "chrome")
 * @param positionMs playback position at the moment {@code sampledAtNanos}
 * @param durationMs track length, or 0 if unknown
 * @param sampledAtNanos {@link System#nanoTime()} the position belongs to
 * @param fresh     true when the player itself reported a new position with this reading.
 *                  Spotify on Windows only does that every few seconds; the readings in
 *                  between are just the last report counted forward, so they teach us nothing.
 */
public record MediaSnapshot(
		String app,
		String title,
		String artist,
		String album,
		long positionMs,
		long durationMs,
		boolean playing,
		long sampledAtNanos,
		boolean fresh
) {
	/** For readers where every reading is a real report from the player (macOS, Linux). */
	public MediaSnapshot(String app, String title, String artist, String album, long positionMs, long durationMs,
	                     boolean playing, long sampledAtNanos) {
		this(app, title, artist, album, positionMs, durationMs, playing, sampledAtNanos, true);
	}

	public String trackKey() {
		return artist + "\u0000" + title;
	}

	public boolean hasTrack() {
		return title != null && !title.isBlank();
	}
}
