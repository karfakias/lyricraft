package dev.lyricraft.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.media.MediaSnapshot;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches synced lyrics from LRCLIB (https://lrclib.net), a free, open lyrics database.
 * Only the song title, artist, album and length are sent, nothing about the player.
 */
public final class LyricsFetcher {

	private static final String API = "https://lrclib.net/api";
	private static final String USER_AGENT = "Lyricraft/" + LyricraftClient.VERSION + " (Minecraft mod; https://modrinth.com/mod/lyricraft)";

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

	public CompletableFuture<Lyrics> fetch(MediaSnapshot track) {
		String key = track.trackKey();
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

	private Lyrics lookup(MediaSnapshot t) throws Exception {
		String artist = primaryArtist(t.artist());
		long durSec = t.durationMs() / 1000;

		// 1) Exact match (fast, uses LRCLIB's own matching including duration)
		if (durSec > 0) {
			Lyrics exact = fromRecord(get(API + "/get?track_name=" + enc(t.title()) + "&artist_name=" + enc(artist)
					+ "&album_name=" + enc(t.album()) + "&duration=" + durSec), t.durationMs());
			if (exact != null && exact.kind != Lyrics.Kind.NOT_FOUND) return exact;
		}

		// 2) Search with the raw title, then with a cleaned one ("Song - Remastered 2011" -> "Song")
		String clean = cleanTitle(t.title());
		Lyrics best = search(t.title(), artist, t.durationMs());
		if ((best == null || best.kind != Lyrics.Kind.SYNCED) && !clean.equalsIgnoreCase(t.title())) {
			Lyrics second = search(clean, artist, t.durationMs());
			if (second != null && (best == null || second.kind == Lyrics.Kind.SYNCED)) best = second;
		}
		return best != null ? best : Lyrics.NOT_FOUND;
	}

	private Lyrics search(String title, String artist, long durationMs) throws Exception {
		JsonElement res = get(API + "/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
		if (res == null || !res.isJsonArray()) return null;
		JsonArray arr = res.getAsJsonArray();

		JsonObject bestSynced = null;
		double bestDiff = Double.MAX_VALUE;
		JsonObject anyRecord = null;
		for (JsonElement el : arr) {
			JsonObject o = el.getAsJsonObject();
			if (anyRecord == null) anyRecord = o;
			if (!hasText(o, "syncedLyrics")) continue;
			double diff = durationMs > 0 && o.has("duration") && !o.get("duration").isJsonNull()
					? Math.abs(o.get("duration").getAsDouble() - durationMs / 1000.0) : 0;
			if (diff < bestDiff) {
				bestDiff = diff;
				bestSynced = o;
			}
		}
		// Reject matches whose length is way off (probably a different version of the song)
		if (bestSynced != null && bestDiff <= 8) return fromRecord(bestSynced, durationMs);
		return anyRecord != null ? fromRecord(anyRecord, durationMs) : null;
	}

	private static Lyrics fromRecord(JsonElement el, long durationMs) {
		if (el == null || !el.isJsonObject()) return null;
		JsonObject o = el.getAsJsonObject();
		if (o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean()) {
			return Lyrics.INSTRUMENTAL;
		}
		if (hasText(o, "syncedLyrics")) return Lyrics.parseLrc(o.get("syncedLyrics").getAsString(), durationMs);
		if (hasText(o, "plainLyrics")) return Lyrics.PLAIN_ONLY;
		return null;
	}

	private JsonElement get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", USER_AGENT)
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
