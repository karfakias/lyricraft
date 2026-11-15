package dev.lyricraft.lyrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyricraft.media.MediaSnapshot;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Shares a corrected version of a song's lyrics with LRCLIB, so the fix helps everyone.
 * Only ever runs when you press "Share" and confirm. No account is involved: LRCLIB asks for
 * a small proof-of-work puzzle to be solved instead, to keep spam out (takes a few seconds).
 */
public final class LrclibPublisher {
	private LrclibPublisher() {}

	private static final String API = "https://lrclib.net/api";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	/**
	 * Publishes {@code lyrics} with every line moved by {@code shiftMs}.
	 * Completes with a short human-readable result.
	 */
	public static CompletableFuture<String> publish(MediaSnapshot track, Lyrics lyrics, long shiftMs) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject challenge = JsonParser.parseString(post(API + "/request-challenge", "", null)).getAsJsonObject();
				String prefix = challenge.get("prefix").getAsString();
				String target = challenge.get("target").getAsString();
				String nonce = solve(prefix, target);

				JsonObject body = new JsonObject();
				body.addProperty("trackName", track.title());
				body.addProperty("artistName", track.artist());
				body.addProperty("albumName", track.album());
				body.addProperty("duration", Math.round(track.durationMs() / 1000.0));
				body.addProperty("plainLyrics", lyrics.lines.stream().map(Lyrics.Line::text).collect(Collectors.joining("\n")));
				body.addProperty("syncedLyrics", lyrics.toLrc(shiftMs));
				post(API + "/publish", body.toString(), prefix + ":" + nonce);
				return "Shared with LRCLIB, thanks!";
			} catch (Exception e) {
				return "Couldn't share: " + e.getMessage();
			}
		});
	}

	/** Finds a nonce so that sha256(prefix + nonce), read as a number, is at most target. */
	static String solve(String prefix, String targetHex) throws Exception {
		BigInteger target = new BigInteger(targetHex, 16);
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		byte[] pre = prefix.getBytes(StandardCharsets.UTF_8);
		for (long nonce = 0; nonce < 2_000_000_000L; nonce++) {
			if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
			sha.reset();
			sha.update(pre);
			sha.update(Long.toString(nonce).getBytes(StandardCharsets.UTF_8));
			if (new BigInteger(1, sha.digest()).compareTo(target) <= 0) return Long.toString(nonce);
		}
		throw new IllegalStateException("puzzle not solved");
	}

	private static String post(String url, String json, String token) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", LyricsFetcher.userAgent())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
		if (token != null) b.header("X-Publish-Token", token);
		HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() / 100 != 2) {
			String msg = res.body() == null ? "" : res.body();
			throw new IllegalStateException("LRCLIB said " + res.statusCode() + (msg.length() > 120 ? "" : " " + msg));
		}
		return res.body();
	}

	/** Hex helper kept for tests. */
	static String hex(byte[] b) {
		return HexFormat.of().formatHex(b);
	}
}
