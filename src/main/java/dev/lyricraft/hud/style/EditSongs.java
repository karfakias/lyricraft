package dev.lyricraft.hud.style;

import dev.lyricraft.LyricraftClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Popular songs that get TikTok-edit style effects in Echoes, from
 * {@code assets/lyricraft/edit_songs.txt}. Each has a style that fits how people edit to it.
 */
public final class EditSongs {
	private EditSongs() {}

	public enum Style { HYPE, DARK, SAD, LOVE, POP, RETRO }

	private record Entry(String artistKey, Style style) {}

	private static Map<String, List<Entry>> byTitle;

	/** The edit style for this song, or null if it isn't on the list. */
	public static Style find(String title, String artist) {
		if (title == null) return null;
		List<Entry> list = load().get(titleKey(title));
		if (list == null) return null;
		String a = key(artist == null ? "" : artist);
		for (Entry e : list) if (e.artistKey().isEmpty() || a.contains(e.artistKey())) return e.style();
		return null;
	}

	public static int size() {
		return load().values().stream().mapToInt(List::size).sum();
	}

	private static synchronized Map<String, List<Entry>> load() {
		if (byTitle != null) return byTitle;
		Map<String, List<Entry>> map = new HashMap<>();
		try (InputStream in = EditSongs.class.getResourceAsStream("/assets/lyricraft/edit_songs.txt")) {
			if (in != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				for (String line; (line = r.readLine()) != null; ) {
					line = line.strip();
					if (line.isEmpty() || line.startsWith("#")) continue;
					String[] p = line.split("\\|");
					if (p.length != 3) continue;
					try {
						Style s = Style.valueOf(p[2].strip());
						map.computeIfAbsent(titleKey(p[0]), k -> new ArrayList<>()).add(new Entry(firstArtistKey(p[1]), s));
					} catch (IllegalArgumentException ignored) {
						// unknown style name: skip the line
					}
				}
			}
		} catch (Exception e) {
			LyricraftClient.LOG.warn("Lyricraft: couldn't read the edit songs list: {}", e.toString());
		}
		byTitle = map;
		return map;
	}

	/** Title without "(feat. ...)", "(Slowed)", " - Remastered" and the like. */
	static String titleKey(String title) {
		String t = title.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
		int dash = t.indexOf(" - ");
		if (dash > 0) t = t.substring(0, dash);
		t = t.replaceAll("(?i)\\s(feat\\.?|ft\\.)\\s.*$", " ");
		return key(t);
	}

	static String firstArtistKey(String artist) {
		String a = artist.split("(?i)\\s*(,|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\band\\b)\\s*")[0];
		return key(a);
	}

	/** Lowercase letters and digits only, accents removed. */
	static String key(String s) {
		String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
		return n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}
}
