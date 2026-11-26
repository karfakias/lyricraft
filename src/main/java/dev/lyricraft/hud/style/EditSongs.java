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
import java.util.Locale;
import java.util.Map;

/**
 * Popular songs that get edit treatment in the Edit style, from
 * {@code assets/lyricraft/edit_songs.txt}. Each has a style that fits how people edit to it, and
 * can have its own look on top: a name for the opening slide, a colour, a font, a sky that takes
 * over on the chorus and weather that falls through it.
 */
public final class EditSongs {
	private EditSongs() {}

	public enum Style { HYPE, DARK, SAD, LOVE, POP, RETRO }

	/**
	 * A song's look. Anything not set comes from its style.
	 *
	 * @param look    name on the opening slide, or null
	 * @param color   signature colour (RGB), or -1
	 * @param sky     sky that takes over on the chorus, or null
	 * @param font    font, or null for the style's (PIXEL = the Minecraft font)
	 * @param weather weather through the chorus (snow, rain, petals, stars...), or null
	 */
	public record Profile(Style style, String look, int color, SceneFx.Scene sky, String font, WorldKeywords.Kind weather) {}

	private record Entry(String artistKey, Profile profile) {}

	private static Map<String, List<Entry>> byTitle;

	/** The edit style for this song, or null if it isn't on the list. */
	public static Style find(String title, String artist) {
		Profile p = profile(title, artist);
		return p == null ? null : p.style();
	}

	/** The song's full look, or null if it isn't on the list. */
	public static Profile profile(String title, String artist) {
		if (title == null) return null;
		List<Entry> list = load().get(titleKey(title));
		if (list == null) return null;
		String a = key(artist == null ? "" : artist);
		for (Entry e : list) if (e.artistKey().isEmpty() || a.contains(e.artistKey())) return e.profile();
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
					if (p.length < 3) continue;
					try {
						Profile prof = parse(Style.valueOf(p[2].strip()), p.length > 3 ? p[3] : "");
						map.computeIfAbsent(titleKey(p[0]), k -> new ArrayList<>()).add(new Entry(firstArtistKey(p[1]), prof));
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

	/** "look=NIGHTCALL, color=#ff3cac, sky=NIGHT, font=MONO, weather=STAR" (any of them, any order). */
	private static Profile parse(Style style, String extras) {
		String look = null, font = null;
		int color = -1;
		SceneFx.Scene sky = null;
		WorldKeywords.Kind weather = null;
		for (String part : extras.split(",")) {
			int eq = part.indexOf('=');
			if (eq < 0) continue;
			String k = part.substring(0, eq).strip().toLowerCase(Locale.ROOT), v = part.substring(eq + 1).strip();
			try {
				switch (k) {
					case "look" -> look = v.toUpperCase(Locale.ROOT);
					case "color" -> color = Integer.parseInt(v.replace("#", ""), 16) & 0xFFFFFF;
					case "sky" -> sky = SceneFx.Scene.valueOf(v.toUpperCase(Locale.ROOT));
					case "font" -> font = v.toUpperCase(Locale.ROOT);
					case "weather" -> weather = WorldKeywords.Kind.valueOf(v.toUpperCase(Locale.ROOT));
					default -> { }
				}
			} catch (IllegalArgumentException ignored) {
				// a typo in one extra shouldn't drop the song
			}
		}
		return new Profile(style, look, color, sky, font, weather);
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
		return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}
}
