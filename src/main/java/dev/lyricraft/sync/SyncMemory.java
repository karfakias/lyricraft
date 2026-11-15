package dev.lyricraft.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.media.AudioOutput;
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
 * the median of your recent corrections. So a constant delay gets fixed everywhere after a
 * few nudges, while one weirdly-timed song stays special.
 *
 * Speakers and Bluetooth headphones are learned separately, because Bluetooth plays sound
 * ~150-250 ms late. A fix you made for a song on one device is carried over to the other:
 * only the part that's about the song itself moves over, not the device's own delay.
 *
 * Stored in config/lyricraft/sync.json.
 */
public final class SyncMemory {

	private static final int MAX_SONGS = 500;
	private static final int LEARN_FROM_LAST = 25;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** A song's timing and whether it was set while listening on Bluetooth. */
	private record Entry(int ms, boolean bluetooth) {}

	private final Config config;
	private final Map<String, Entry> songs = new LinkedHashMap<>();
	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("lyricraft").resolve("sync.json");

	public SyncMemory(Config config) {
		this.config = config;
		load();
	}

	/** Learned default for the device the sound is coming out of right now. */
	public int defaultOffset() {
		return defaultFor(AudioOutput.bluetooth());
	}

	private int defaultFor(boolean bluetooth) {
		return bluetooth ? config.offsetBluetoothMs : config.offsetMs;
	}

	/** Timing to use for this song: what you set for it before, or the learned default. */
	public synchronized int offsetFor(String trackKey) {
		Entry own = songs.get(trackKey);
		boolean bt = AudioOutput.bluetooth();
		if (own == null) return defaultFor(bt);
		if (own.bluetooth == bt) return own.ms;
		// set on the other device: keep the song's own part, swap the device delay
		return own.ms - defaultFor(own.bluetooth) + defaultFor(bt);
	}

	public synchronized boolean hasOwnOffset(String trackKey) {
		return songs.containsKey(trackKey);
	}

	/**
	 * The part of this song's timing that's about the song itself (bad lyrics timing), with the
	 * device delay taken out. This is what gets shared with LRCLIB.
	 */
	public synchronized int songOnlyOffset(String trackKey) {
		Entry own = songs.get(trackKey);
		return own == null ? 0 : own.ms - defaultFor(own.bluetooth);
	}

	/** Applies a nudge for this song and re-learns the default. Returns the song's new offset. */
	public synchronized int nudge(String trackKey, int deltaMs) {
		boolean bt = AudioOutput.bluetooth();
		int updated = Math.clamp(offsetFor(trackKey) + deltaMs, -5000, 5000);
		songs.remove(trackKey); // re-insert so it counts as "recent"
		songs.put(trackKey, new Entry(updated, bt));
		while (songs.size() > MAX_SONGS) songs.remove(songs.keySet().iterator().next());
		relearnDefault(bt);
		save();
		return updated;
	}

	/** Forget this song's own timing so it uses the learned default again. */
	public synchronized void reset(String trackKey) {
		if (songs.remove(trackKey) != null) save();
	}

	private void relearnDefault(boolean bluetooth) {
		List<Integer> recent = new ArrayList<>();
		for (Entry e : songs.values()) if (e.bluetooth == bluetooth) recent.add(e.ms);
		if (recent.isEmpty()) return;
		recent = new ArrayList<>(recent.subList(Math.max(0, recent.size() - LEARN_FROM_LAST), recent.size()));
		Collections.sort(recent);
		// round to 10 ms so the number stays readable
		int median = Math.round(recent.get(recent.size() / 2) / 10f) * 10;
		if (bluetooth) config.offsetBluetoothMs = median;
		else config.offsetMs = median;
		config.save();
	}

	private void load() {
		if (!Files.exists(file)) return;
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (root == null || !root.isJsonObject()) return;
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
				JsonElement v = e.getValue();
				if (v.isJsonPrimitive()) {
					// older versions stored just a number
					songs.put(e.getKey(), new Entry(v.getAsInt(), false));
				} else if (v.isJsonObject()) {
					JsonObject o = v.getAsJsonObject();
					songs.put(e.getKey(), new Entry(o.get("ms").getAsInt(), o.has("bt") && o.get("bt").getAsBoolean()));
				}
			}
		} catch (Exception e) {
			LyricraftClient.LOG.warn("Lyricraft: couldn't read sync memory: {}", e.toString());
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			for (Map.Entry<String, Entry> e : songs.entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("ms", e.getValue().ms);
				o.addProperty("bt", e.getValue().bluetooth);
				root.add(e.getKey(), o);
			}
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(root, w);
			}
		} catch (Exception e) {
			LyricraftClient.LOG.warn("Lyricraft: couldn't save sync memory: {}", e.toString());
		}
	}
}
