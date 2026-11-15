package dev.lyricraft.media;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * macOS: asks the Spotify desktop app directly through AppleScript. The script checks
 * "is running" first, so it never launches Spotify by itself.
 */
final class MacMediaReader implements MediaReader {

	private static final String SCRIPT = String.join("\n",
			"if application \"Spotify\" is running then",
			"  tell application \"Spotify\"",
			"    if player state is stopped then return \"\"",
			"    set t to current track",
			"    return (name of t) & tab & (artist of t) & tab & (album of t) & tab & ((player position * 1000) as integer) & tab & (duration of t) & tab & (player state as string) & tab & (artwork url of t)",
			"  end tell",
			"end if",
			"return \"\"");

	@Override
	public void run(Consumer<MediaSnapshot> sink) throws Exception {
		while (!Thread.currentThread().isInterrupted()) {
			Process p = new ProcessBuilder("osascript", "-e", SCRIPT)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			String out;
			try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				out = in.readLine();
			}
			p.waitFor(2, TimeUnit.SECONDS);
			sink.accept(parse(out));
			Thread.sleep(700);
		}
	}

	private static MediaSnapshot parse(String line) {
		if (line == null || line.isBlank()) return null;
		String[] f = line.split("\t", -1);
		if (f.length < 6) return null;
		try {
			MediaSnapshot snap = new MediaSnapshot("Spotify", f[0], f[1], f[2],
					Long.parseLong(f[3].trim()), Long.parseLong(f[4].trim()),
					f[5].trim().equalsIgnoreCase("playing"), System.nanoTime());
			if (f.length > 6) CoverArt.offer(snap.trackKey(), f[6].trim());
			return snap;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
