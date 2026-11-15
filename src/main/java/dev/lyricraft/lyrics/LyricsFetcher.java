package dev.lyricraft.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.media.MediaSnapshot;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Finds synced lyrics. Main source: LRCLIB (https://lrclib.net), a free, open lyrics database.
 * Optional extra source: NetEase Cloud Music (off by default). Only the song title, artist,
 * album and length are sent.
 *
 * Popular songs usually have several synced versions on LRCLIB, each made by a different
 * person. Instead of trusting just one, we collect every version that matches the song's
 * length and let {@link Consensus} combine them into one better-timed version.
 */
public final class LyricsFetcher {

	private static final String API = "https://lrclib.net/api";
	/** Who's asking, as LRCLIB requests (computed on use so this class loads without the game). */
	static String userAgent() {
		return "Lyricraft/" + LyricraftClient.VERSION + " (Minecraft mod; https://modrinth.com/mod/lyricraft)";
	}

	/** Versions whose length is this close to the song's are the same recording. */
	private static final double SAME_LENGTH_S = 2.0;
	/** If nothing is that close, still accept the closest one up to this far off. */
	private static final double MAX_LENGTH_S = 8.0;

	/** NetEase starts its lyrics with credit lines like "作词 : name" ("lyrics by"); drop those. */
	private static final Pattern CREDIT = Pattern.compile(
			"(?i)^\\s*(作词|作曲|编曲|制作人|制作|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|出品|发行|词|曲|lyricist|composer|lyrics by|written by|composed by|produced by|producer|arranger|arranged by|mixed by|mastered by)\\s*[:：]");

	private final Config config;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(6))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Lyricraft lyrics fetcher");
		t.setDaemon(true);
		return t;
	});

	private final Map<String, Lyrics> cache = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Lyrics> e) {
			return size() > 200;
		}
	};

	public LyricsFetcher(Config config) {
		this.config = config;
	}

	/** One version of the lyrics we found somewhere. */
	private record Candidate(String id, String source, double lengthDiffS, String lrc, boolean plain, boolean instrumental) {}

	public CompletableFuture<Lyrics> fetch(MediaSnapshot track) {
		// the settings change the result, so they're part of the cache key
		String key = track.trackKey() + "|" + config.combineVersions + "|" + config.extraLyricsSource;
		synchronized (cache) {
			Lyrics hit = cache.get(key);
			if (hit != null) return CompletableFuture.completedFuture(hit);
		}
		return CompletableFuture.supplyAsync(() -> {
			Lyrics result;
			try {
				result = lookup(track);
			} catch (Exception e) {
				LyricraftClient.LOG.warn("Lyricraft: lyrics lookup failed for '{}': {}", track.title(), e.toString());
				return Lyrics.NOT_FOUND; // don't cache network errors, so we retry next time
			}
			synchronized (cache) {
				cache.put(key, result);
			}
			return result;
		}, pool);
	}

	/** Forget cached lyrics (after changing lyric settings). */
	public void clearCache() {
		synchronized (cache) {
			cache.clear();
		}
	}

	private Lyrics lookup(MediaSnapshot t) throws Exception {
		String artist = primaryArtist(t.artist());
		long durMs = t.durationMs();
		List<Candidate> found = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		// 1) LRCLIB's own best match, then every version its search knows about
		if (durMs > 0) {
			addLrclib(found, seen, get(API + "/get?track_name=" + enc(t.title()) + "&artist_name=" + enc(artist)
					+ "&album_name=" + enc(t.album()) + "&duration=" + durMs / 1000), durMs);
		}
		if (config.combineVersions || syncedCount(found) == 0) {
			addLrclibList(found, seen, get(API + "/search?track_name=" + enc(t.title()) + "&artist_name=" + enc(artist)), durMs);
			String clean = cleanTitle(t.title());
			if (syncedCount(found) < 3 && !clean.equalsIgnoreCase(t.title())) {
				addLrclibList(found, seen, get(API + "/search?track_name=" + enc(clean) + "&artist_name=" + enc(artist)), durMs);
			}
		}

		// 2) optional second opinion from NetEase
		if (config.extraLyricsSource) {
			try {
				addNetEase(found, t, artist, durMs);
			} catch (Exception e) {
				LyricraftClient.LOG.debug("Lyricraft: NetEase lookup failed: {}", e.toString());
			}
		}

		// 3) pick the synced versions that belong to this exact recording
		List<Candidate> synced = new ArrayList<>(found.stream().filter(c -> c.lrc != null).toList());
		synced.sort(Comparator.comparingDouble(Candidate::lengthDiffS));
		List<Candidate> sameLength = synced.stream().filter(c -> c.lengthDiffS <= SAME_LENGTH_S).toList();
		if (sameLength.isEmpty() && !synced.isEmpty() && synced.getFirst().lengthDiffS <= MAX_LENGTH_S) {
			sameLength = List.of(synced.getFirst());
		}

		if (!sameLength.isEmpty()) {
			List<Consensus.Version> versions = new ArrayList<>();
			for (Candidate c : config.combineVersions ? sameLength : sameLength.subList(0, 1)) {
				List<Lyrics.Stamp> stamps = Lyrics.parseStamps(c.lrc);
				if (stamps.stream().anyMatch(s -> !s.text().isEmpty())) versions.add(new Consensus.Version(stamps, c.source));
			}
			if (!versions.isEmpty()) {
				Consensus.Result r = Consensus.combine(versions);
				Lyrics lyrics = Lyrics.build(r.stamps(), durMs);
				if (lyrics.kind == Lyrics.Kind.SYNCED) {
					String src = String.join(" + ", r.sources().stream().sorted().toList());
					LyricraftClient.LOG.info("Lyricraft: '{}' timed from {} version(s) ({}), {} found", t.title(),
							r.versionsUsed(), src, versions.size());
					return lyrics.withInfo(r.versionsUsed(), src);
				}
			}
		}
		if (found.stream().anyMatch(Candidate::instrumental)) return Lyrics.INSTRUMENTAL;
		if (found.stream().anyMatch(Candidate::plain)) return Lyrics.PLAIN_ONLY;
		return Lyrics.NOT_FOUND;
	}

	// ------------------------------------------------------------------ LRCLIB

	private void addLrclibList(List<Candidate> out, Set<String> seen, JsonElement res, long durMs) {
		if (res == null || !res.isJsonArray()) return;
		JsonArray arr = res.getAsJsonArray();
		for (JsonElement el : arr) addLrclib(out, seen, el, durMs);
	}

	private void addLrclib(List<Candidate> out, Set<String> seen, JsonElement el, long durMs) {
		if (el == null || !el.isJsonObject()) return;
		JsonObject o = el.getAsJsonObject();
		String id = "lrclib:" + (o.has("id") ? o.get("id").getAsString() : out.size());
		if (!seen.add(id)) return;
		double diff = durMs > 0 && o.has("duration") && !o.get("duration").isJsonNull()
				? Math.abs(o.get("duration").getAsDouble() - durMs / 1000.0) : 0;
		boolean instrumental = o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean();
		String lrc = hasText(o, "syncedLyrics") ? o.get("syncedLyrics").getAsString() : null;
		out.add(new Candidate(id, "LRCLIB", diff, lrc, hasText(o, "plainLyrics"), instrumental));
	}

	private static int syncedCount(List<Candidate> list) {
		return (int) list.stream().filter(c -> c.lrc != null).count();
	}

	// ------------------------------------------------------------------ NetEase (optional)

	private void addNetEase(List<Candidate> out, MediaSnapshot t, String artist, long durMs) throws Exception {
		JsonElement res = getNetEase("https://music.163.com/api/search/get?type=1&limit=8&s=" + enc(cleanTitle(t.title()) + " " + artist));
		if (res == null || !res.isJsonObject()) return;
		JsonObject root = res.getAsJsonObject();
		if (!root.has("result") || !root.getAsJsonObject("result").has("songs")) return;
		String wantArtist = Consensus.normalize(artist);
		String wantTitle = Consensus.normalize(cleanTitle(t.title()));
		int added = 0;
		for (JsonElement el : root.getAsJsonObject("result").getAsJsonArray("songs")) {
			if (added >= 2) break;
			JsonObject s = el.getAsJsonObject();
			double diff = durMs > 0 && s.has("duration") ? Math.abs(s.get("duration").getAsLong() - durMs) / 1000.0 : 99;
			if (diff > SAME_LENGTH_S) continue;
			String title = Consensus.normalize(s.get("name").getAsString());
			boolean artistOk = false;
			for (JsonElement a : s.getAsJsonArray("artists")) {
				String n = Consensus.normalize(a.getAsJsonObject().get("name").getAsString());
				if (!n.isEmpty() && (n.contains(wantArtist) || wantArtist.contains(n))) artistOk = true;
			}
			if (!artistOk || !(title.contains(wantTitle) || wantTitle.contains(title))) continue;
			JsonElement lyr = getNetEase("https://music.163.com/api/song/lyric?lv=1&id=" + s.get("id").getAsLong());
			if (lyr == null || !lyr.isJsonObject() || !lyr.getAsJsonObject().has("lrc")) continue;
			JsonObject lrcObj = lyr.getAsJsonObject().getAsJsonObject("lrc");
			if (!hasText(lrcObj, "lyric")) continue;
			String cleaned = stripCredits(lrcObj.get("lyric").getAsString());
			out.add(new Candidate("netease:" + s.get("id").getAsLong(), "NetEase", diff, cleaned, false, false));
			added++;
		}
	}

	/** Remove "作词 : someone" style credit lines (they're timed like lyrics in NetEase files). */
	static String stripCredits(String lrc) {
		StringBuilder sb = new StringBuilder();
		for (String line : lrc.split("\\r?\\n")) {
			String text = line.replaceAll("^(\\[[^]]*])+", "");
			if (CREDIT.matcher(text).find()) continue;
			sb.append(line).append('\n');
		}
		return sb.toString();
	}

	private JsonElement getNetEase(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", "Mozilla/5.0")
				.header("Referer", "https://music.163.com/")
				.GET().build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() / 100 != 2) return null;
		return JsonParser.parseString(res.body());
	}

	// ------------------------------------------------------------------ helpers

	private JsonElement get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", userAgent())
				.header("Accept", "application/json")
				.GET().build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() == 404) return null;
		if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode());
		return JsonParser.parseString(res.body());
	}

	private static boolean hasText(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() && !o.get(key).getAsString().isBlank();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
	}

	/** "Artist A, Artist B" / "A & B" / "A feat. B" -> "Artist A" */
	static String primaryArtist(String artist) {
		if (artist == null) return "";
		String a = artist.split("(?i),|;| & | x | feat\\.? | ft\\.? ")[0];
		return a.trim();
	}

	/** Strip things like " - Remastered 2011", " (feat. X)", " [Live]" from a title. */
	static String cleanTitle(String title) {
		String t = title.replaceAll("(?i)\\s*[(\\[](feat|ft|with|prod)\\.?[^)\\]]*[)\\]]", "");
		t = t.replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|live|mix|mono|stereo|from).*$", "");
		t = t.replaceAll("(?i)\\s*[(\\[][^)\\]]*(remaster|version|edit|live|mono|stereo)[^)\\]]*[)\\]]", "");
		return t.trim().isEmpty() ? title : t.trim();
	}
}
