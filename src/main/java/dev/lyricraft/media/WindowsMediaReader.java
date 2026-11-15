package dev.lyricraft.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricraft.LyricraftClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * Windows: uses the system media controls (GlobalSystemMediaTransportControls) through a small,
 * readable PowerShell script that ships inside the mod and is copied to config/lyricraft/.
 * Works for the Spotify desktop app and for Spotify in any Chromium browser or Firefox.
 */
final class WindowsMediaReader implements MediaReader {

	@Override
	public void run(Consumer<MediaSnapshot> sink) throws Exception {
		Path script = extractScript();
		Process process = new ProcessBuilder(
				"powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
				"-WindowStyle", "Hidden", "-File", script.toString(),
				"-GamePid", Long.toString(ProcessHandle.current().pid()))
				.redirectErrorStream(false)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		// belt and braces: also stop it when the game shuts down normally
		Thread hook = new Thread(process::destroy, "Lyricraft media reader shutdown");
		Runtime.getRuntime().addShutdownHook(hook);

		try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			long lastRaw = Long.MIN_VALUE, lastUpd = Long.MIN_VALUE;
			while (!Thread.currentThread().isInterrupted() && (line = in.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.charAt(0) != '{') continue;
				try {
					JsonObject o = JsonParser.parseString(line).getAsJsonObject();
					if (o.has("error") || o.has("none")) {
						sink.accept(null);
						continue;
					}
					boolean playing = o.get("playing").getAsBoolean();
					long upd = o.has("upd") ? o.get("upd").getAsLong() : 0;
					long raw = o.has("raw") ? o.get("raw").getAsLong() : 0;
					long nowNanos = System.nanoTime();
					long pos;
					boolean fresh;
					if (upd > 0) {
						// Spotify said "I was at <raw> at wall-clock time <upd>". Work the position out
						// right now, here, so the time the line spent in the pipe doesn't make us late.
						long since = Math.max(0, System.currentTimeMillis() - upd);
						pos = playing ? raw + since : raw;
						fresh = raw != lastRaw || upd != lastUpd;
						lastRaw = raw;
						lastUpd = upd;
					} else {
						pos = o.get("pos").getAsLong(); // player without timestamps: every reading counts
						fresh = true;
					}
					MediaSnapshot snap = new MediaSnapshot(
							str(o, "app"), str(o, "title"), str(o, "artist"), str(o, "album"),
							pos, o.get("dur").getAsLong(), playing, nowNanos, fresh);
					CoverArt.offer(snap.trackKey(), str(o, "cover"));
					if (o.has("bt")) AudioOutput.update(str(o, "out"), o.get("bt").getAsBoolean());
					sink.accept(snap);
				} catch (RuntimeException e) {
					LyricraftClient.LOG.debug("Bad media line: {}", line);
				}
			}
		} finally {
			process.destroy();
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			} catch (IllegalStateException ignored) {
				// already shutting down
			}
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static Path extractScript() throws Exception {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("lyricraft");
		Files.createDirectories(dir);
		Path target = dir.resolve("media-windows.ps1");
		try (InputStream res = WindowsMediaReader.class.getResourceAsStream("/assets/lyricraft/media-windows.ps1")) {
			if (res == null) throw new IllegalStateException("media-windows.ps1 missing from jar");
			Files.copy(res, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}
}
