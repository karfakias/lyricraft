package dev.lyricraft.media;

import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.Draw;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The album cover of the song that's playing, taken from the same place as the song info
 * (Windows media controls, Spotify on macOS, MPRIS on Linux). It's shrunk to a small pixel
 * grid so it can be drawn as a crisp pixel-art cover, and its two strongest colours are used
 * for the card and the vibe lighting.
 */
public final class CoverArt {
	private CoverArt() {}

	public static final int SIZE = 48;

	/** @param pixels SIZE*SIZE RGB values, row by row */
	public record Cover(String trackKey, int[] pixels, int colorA, int colorB) {}

	private static volatile Cover current;
	private static volatile String requestedKey = "";
	private static volatile String requestedSource = "";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** Cover for this track, or {@code null} if there isn't one (yet). */
	private static volatile Cover demo;

	/** A made-up cover for the demo song (screenshots/tests); null image clears it. */
	public static void showDemo(String trackKey, BufferedImage image) {
		demo = image == null ? null : shrink(trackKey, image);
	}

	public static Cover get(String trackKey) {
		Cover d = demo;
		if (d != null && d.trackKey.equals(trackKey)) return d;
		Cover c = current;
		return c != null && c.trackKey.equals(trackKey) ? c : null;
	}

	/** Media readers call this with a file path or an http(s)/file URL for the track's cover. */
	public static void offer(String trackKey, String source) {
		if (source == null || source.isBlank() || trackKey == null) return;
		if (trackKey.equals(requestedKey) && source.equals(requestedSource)) return;
		requestedKey = trackKey;
		requestedSource = source;
		CompletableFuture.runAsync(() -> {
			try {
				byte[] bytes = read(source);
				if (bytes == null || bytes.length == 0) return;
				BufferedImage img = decodeSafely(bytes);
				if (img == null) return;
				Cover cover = shrink(trackKey, img);
				if (trackKey.equals(requestedKey)) current = cover;
			} catch (Exception e) {
				LyricraftClient.LOG.debug("Lyricraft: couldn't load cover art: {}", e.toString());
			}
		});
	}

	/** Album covers are a few hundred KB; refuse anything silly. */
	private static final int MAX_BYTES = 8 * 1024 * 1024, MAX_SIDE = 4096;

	private static byte[] read(String source) throws Exception {
		if (source.startsWith("http://") || source.startsWith("https://")) {
			HttpRequest req = HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(8)).GET().build();
			HttpResponse<java.io.InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
			try (java.io.InputStream in = res.body()) {
				if (res.statusCode() != 200) return null;
				byte[] data = in.readNBytes(MAX_BYTES + 1);
				return data.length > MAX_BYTES ? null : data;
			}
		}
		Path p = source.startsWith("file:") ? Path.of(URI.create(source)) : Path.of(source);
		if (!Files.isRegularFile(p) || Files.size(p) > MAX_BYTES) return null;
		return Files.readAllBytes(p);
	}

	/** Reads the image size first and only decodes sensible ones (stops "image bomb" files). */
	private static BufferedImage decodeSafely(byte[] bytes) throws Exception {
		try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (in == null) return null;
			java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) return null;
			javax.imageio.ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0), h = reader.getHeight(0);
				if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null;
				return reader.read(0);
			} finally {
				reader.dispose();
			}
		}
	}

	private static Cover shrink(String key, BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		// crop to a centred square (browser thumbnails are sometimes letterboxed)
		int side = Math.min(w, h), ox = (w - side) / 2, oy = (h - side) / 2;
		int[] px = new int[SIZE * SIZE];
		// colour histogram for the palette: 12 hue buckets weighted by saturation * brightness
		float[] weight = new float[12];
		int[] rSum = new int[12], gSum = new int[12], bSum = new int[12], count = new int[12];
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				// box-average the source pixels that fall into this cell
				int x0 = ox + x * side / SIZE, x1 = Math.max(x0 + 1, ox + (x + 1) * side / SIZE);
				int y0 = oy + y * side / SIZE, y1 = Math.max(y0 + 1, oy + (y + 1) * side / SIZE);
				long r = 0, g = 0, b = 0;
				int n = 0;
				for (int sy = y0; sy < y1; sy += Math.max(1, (y1 - y0) / 3)) {
					for (int sx = x0; sx < x1; sx += Math.max(1, (x1 - x0) / 3)) {
						int c = img.getRGB(sx, sy);
						r += (c >> 16) & 255;
						g += (c >> 8) & 255;
						b += c & 255;
						n++;
					}
				}
				int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
				px[y * SIZE + x] = (rr << 16) | (gg << 8) | bb;

				float max = Math.max(rr, Math.max(gg, bb)) / 255f, min = Math.min(rr, Math.min(gg, bb)) / 255f;
				float sat = max <= 0 ? 0 : (max - min) / max;
				float hue = hueOf(rr, gg, bb);
				int bucket = Math.min(11, (int) (hue / 30f));
				float wgt = sat * sat * max;
				weight[bucket] += wgt;
				rSum[bucket] += rr;
				gSum[bucket] += gg;
				bSum[bucket] += bb;
				count[bucket]++;
			}
		}
		int first = -1, second = -1;
		for (int i = 0; i < 12; i++) {
			if (first < 0 || weight[i] > weight[first]) first = i;
		}
		for (int i = 0; i < 12; i++) {
			if (i == first || Math.abs(i - first) == 1 || Math.abs(i - first) == 11) continue; // want a different hue
			if (second < 0 || weight[i] > weight[second]) second = i;
		}
		int a = vivid(first, weight, rSum, gSum, bSum, count, 0x1ED760);
		int b = second >= 0 && weight[second] > weight[first] * 0.12f
				? vivid(second, weight, rSum, gSum, bSum, count, a)
				: Draw.hsv(hueOf((a >> 16) & 255, (a >> 8) & 255, a & 255) + 40f, 0.8f, 1f);
		return new Cover(key, px, a, b);
	}

	/** Average colour of a bucket, pushed to full brightness/saturation so it glows nicely. */
	private static int vivid(int bucket, float[] weight, int[] r, int[] g, int[] b, int[] count, int fallback) {
		if (bucket < 0 || count[bucket] == 0 || weight[bucket] < 0.5f) return fallback;
		int rr = r[bucket] / count[bucket], gg = g[bucket] / count[bucket], bb = b[bucket] / count[bucket];
		return Draw.hsv(hueOf(rr, gg, bb), 0.72f, 1f);
	}

	private static float hueOf(int r, int g, int b) {
		float rf = r / 255f, gf = g / 255f, bf = b / 255f;
		float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf)), d = max - min;
		if (d <= 0.0001f) return 0f;
		float h;
		if (max == rf) h = ((gf - bf) / d) % 6f;
		else if (max == gf) h = (bf - rf) / d + 2f;
		else h = (rf - gf) / d + 4f;
		h *= 60f;
		return h < 0 ? h + 360f : h;
	}
}
