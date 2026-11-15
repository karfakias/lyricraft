package dev.lyricraft.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns timing from you. Every time you nudge the lyrics with [ or ] the correction is
 * remembered for that exact song, and your "default" timing for new songs is re-learned from
 * the median of your recent corrections. So a constant delay (Bluetooth headphones, a slow
 * PC) gets fixed everywhere after a few nudges, while one weirdly-timed song stays special.
 *
 * Stored in config/lyricraft/sync.json.
 */
public final class SyncMemory {

	private static final int MAX_SONGS = 500;
	private static final int LEARN_FROM_LAST = 25;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Config config;
	private final Map<String, Integer> songs = new LinkedHashMap<>();
	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("lyricraft").resolve("sync.json");

	public SyncMemory(Config config) {
		this.config = config;
		load();
	}

	/** Timing to use for this song: what you set for it before, or the learned default. */
	public synchronized int offsetFor(String trackKey) {
		Integer own = songs.get(trackKey);
		return own != null ? own : config.offsetMs;
	}

	public synchronized boolean hasOwnOffset(String trackKey) {
		return songs.containsKey(trackKey);
	}

	/** Applies a nudge for this song and re-learns the default. Returns the song's new offset. */
	public synchronized int nudge(String trackKey, int deltaMs) {
		int updated = Math.clamp(offsetFor(trackKey) + deltaMs, -5000, 5000);
		songs.remove(trackKey); // re-insert so it counts as "recent"
		songs.put(trackKey, updated);
		while (songs.size() > MAX_SONGS) songs.remove(songs.keySet().iterator().next());
		relearnDefault();
		save();
		return updated;
	}

	/** Forget this song's own timing so it uses the learned default again. */
	public synchronized void reset(String trackKey) {
		if (songs.remove(trackKey) != null) save();
	}

	private void relearnDefault() {
		List<Integer> recent = new ArrayList<>(songs.values());
		if (recent.isEmpty()) return;
		recent = new ArrayList<>(recent.subList(Math.max(0, recent.size() - LEARN_FROM_LAST), recent.size()));
		Collections.sort(recent);
		int median = recent.get(recent.size() / 2);
		// round to 10 ms so the number stays readable
		config.offsetMs = Math.round(median / 10f) * 10;
		config.save();
	}

	private void load() {
		if (!Files.exists(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Map<String, Integer> data = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Integer>>() {}.getType());
			if (data != null) songs.putAll(data);
		} catch (Exception e) {
			LyricraftClient.LOG.warn("Lyricraft: couldn't read sync memory: {}", e.toString());
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(songs, w);
			}
		} catch (Exception e) {
			LyricraftClient.LOG.warn("Lyricraft: couldn't save sync memory: {}", e.toString());
		}
	}
}
