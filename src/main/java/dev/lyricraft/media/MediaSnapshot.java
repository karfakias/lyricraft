package dev.lyricraft.media;

/**
 * One reading of "what is playing right now", taken from the operating system.
 *
 * @param app       which app reported it (e.g. "Spotify.exe", "chrome")
 * @param positionMs playback position at the moment the reading was taken
 * @param durationMs track length, or 0 if unknown
 * @param sampledAtNanos {@link System#nanoTime()} when the reading arrived
 */
public record MediaSnapshot(
		String app,
		String title,
		String artist,
		String album,
		long positionMs,
		long durationMs,
		boolean playing,
		long sampledAtNanos
) {
	public String trackKey() {
		return artist + "\u0000" + title;
	}

	public boolean hasTrack() {
		return title != null && !title.isBlank();
	}
}
