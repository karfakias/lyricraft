package dev.lyricraft.media;

import dev.lyricraft.LyricraftClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Linux: reads MPRIS (the standard Linux media-player interface) through {@code playerctl},
 * preferring Spotify and falling back to any player (e.g. a browser with the Web Player).
 */
final class LinuxMediaReader implements MediaReader {

	private static final String FORMAT =
			"{{playerName}}\t{{title}}\t{{artist}}\t{{album}}\t{{position}}\t{{mpris:length}}\t{{status}}";

	@Override
	public void run(Consumer<MediaSnapshot> sink) throws Exception {
		boolean warned = false;
		while (!Thread.currentThread().isInterrupted()) {
			try {
				Process p = new ProcessBuilder("playerctl", "-p", "spotify,%any", "metadata", "--format", FORMAT)
						.redirectError(ProcessBuilder.Redirect.DISCARD).start();
				String out;
				try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					out = in.readLine();
				}
				p.waitFor(2, TimeUnit.SECONDS);
				sink.accept(parse(out));
			} catch (IOException e) {
				if (!warned) {
					LyricraftClient.LOG.warn("Lyricraft needs 'playerctl' on Linux (e.g. sudo apt install playerctl).");
					warned = true;
				}
				sink.accept(null);
				Thread.sleep(5000);
			}
			Thread.sleep(500);
		}
	}

	private static MediaSnapshot parse(String line) {
		if (line == null || line.isBlank()) return null;
		String[] f = line.split("\t", -1);
		if (f.length < 7) return null;
		try {
			long posUs = f[4].isBlank() ? 0 : Long.parseLong(f[4].trim());
			long lenUs = f[5].isBlank() ? 0 : Long.parseLong(f[5].trim());
			MediaSnapshot snap = new MediaSnapshot(f[0], f[1], f[2], f[3], posUs / 1000, lenUs / 1000,
					f[6].trim().equalsIgnoreCase("Playing"), System.nanoTime());
			if (f.length > 7) CoverArt.offer(snap.trackKey(), f[7].trim());
			return snap;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
