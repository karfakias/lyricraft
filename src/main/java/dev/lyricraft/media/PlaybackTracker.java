package dev.lyricraft.media;

import dev.lyricraft.LyricraftClient;

import java.util.function.Consumer;

/**
 * Keeps a smooth playback clock. Readings from the OS arrive a few times a second (and the
 * Spotify app only reports a fresh position now and then), so between readings we keep
 * counting forward ourselves and only snap back when the OS clearly disagrees (a seek).
 */
public final class PlaybackTracker {

	private static final long SNAP_THRESHOLD_MS = 450;

	private volatile MediaSnapshot latest;
	private Thread thread;

	// Local clock (only touched on the reader thread, read with volatile semantics)
	private volatile long anchorPosMs;
	private volatile long anchorNanos;
	private volatile boolean playing;
	private volatile String trackKey = "";
	private Consumer<MediaSnapshot> onTrackChange = s -> {};

	public void onTrackChange(Consumer<MediaSnapshot> listener) {
		this.onTrackChange = listener;
	}

	public void start() {
		thread = new Thread(this::loop, "Lyricraft media reader");
		thread.setDaemon(true);
		thread.start();
	}

	private void loop() {
		MediaReader reader = MediaReader.forThisOs();
		while (!Thread.currentThread().isInterrupted()) {
			try {
				reader.run(this::accept);
			} catch (InterruptedException e) {
				return;
			} catch (Exception e) {
				LyricraftClient.LOG.warn("Lyricraft media reader stopped, restarting: {}", e.toString());
			}
			try {
				Thread.sleep(3000); // reader exited (e.g. PowerShell closed); restart it
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void accept(MediaSnapshot s) {
		latest = s;
		if (s == null || !s.hasTrack()) {
			playing = false;
			return;
		}

		boolean newTrack = !s.trackKey().equals(trackKey);
		long estimated = estimatedRawPositionMs();
		boolean seek = Math.abs(estimated - s.positionMs()) > SNAP_THRESHOLD_MS;

		if (newTrack || playing != s.playing() || (seek && s.fresh())) {
			anchorPosMs = s.positionMs();
			anchorNanos = s.sampledAtNanos();
			resetRate();
		} else if (s.playing() && s.fresh()) {
			// Only real reports from the player carry information. (On Windows, Spotify reports
			// every ~4.5 s; the readings in between are just its last report counted forward.)
			recordSample(s);
			learnRate(s);
			// Ease towards the reported position instead of jumping, so the karaoke fill never
			// stutters. Real reports are exact (we timestamp them ourselves), so ease in quickly.
			long drift = s.positionMs() - estimatedRawPositionMs();
			anchorPosMs = estimatedRawPositionMs() + drift / 2;
			anchorNanos = System.nanoTime();
			lastDriftMs = drift;
		}
		if (s.playing() && s.fresh() && sampleCount == 0) recordSample(s);
		playing = s.playing();

		if (newTrack) {
			trackKey = s.trackKey();
			onTrackChange.accept(s);
		}
	}

	private long estimatedRawPositionMs() {
		long pos = anchorPosMs;
		if (playing) pos += (long) ((System.nanoTime() - anchorNanos) / 1_000_000.0 * rate);
		return pos;
	}

	// ---------------------------------------------------------------- auto-sync (clock lock)
	//
	// Spotify's reported position and the game's clock don't always tick at exactly the same
	// speed (browser timelines update in chunks, the OS clock and Spotify's audio clock drift).
	// We fit a straight line through the last ~40 s of *real* reports from the player: its slope
	// is how fast the song really moves per real second. Our clock then runs at that speed, so it
	// stops drifting instead of constantly being corrected.

	private static final int MAX_SAMPLES = 16;
	private final long[] sampleNanos = new long[MAX_SAMPLES];
	private final long[] samplePos = new long[MAX_SAMPLES];
	private int sampleCount, sampleHead;
	private volatile double rate = 1.0;
	private volatile long lastDriftMs;
	private volatile boolean autoSync = true;

	public void setAutoSync(boolean enabled) {
		autoSync = enabled;
		if (!enabled) rate = 1.0;
	}

	/** How fast our clock currently runs compared to real time (1.0 = normal). */
	public double rate() {
		return rate;
	}

	/** Last measured difference between Spotify and our clock, in ms (before smoothing). */
	public long lastDriftMs() {
		return lastDriftMs;
	}

	private void resetRate() {
		sampleCount = 0;
		sampleHead = 0;
		rate = 1.0;
	}

	private void recordSample(MediaSnapshot s) {
		sampleNanos[sampleHead] = s.sampledAtNanos();
		samplePos[sampleHead] = s.positionMs();
		sampleHead = (sampleHead + 1) % MAX_SAMPLES;
		if (sampleCount < MAX_SAMPLES) sampleCount++;
	}

	private void learnRate(MediaSnapshot latest) {
		if (!autoSync || sampleCount < 4) return;
		// least squares: pos = a + slope * t
		long t0 = sampleNanos[(sampleHead - sampleCount + MAX_SAMPLES) % MAX_SAMPLES];
		double n = sampleCount, sx = 0, sy = 0, sxx = 0, sxy = 0;
		long p0 = samplePos[(sampleHead - sampleCount + MAX_SAMPLES) % MAX_SAMPLES];
		for (int i = 0; i < sampleCount; i++) {
			int idx = (sampleHead - sampleCount + i + MAX_SAMPLES) % MAX_SAMPLES;
			double x = (sampleNanos[idx] - t0) / 1e6;
			double y = samplePos[idx] - p0;
			sx += x; sy += y; sxx += x * x; sxy += x * y;
		}
		double span = (latest.sampledAtNanos() - t0) / 1e6;
		double denom = n * sxx - sx * sx;
		if (span < 12000 || denom <= 0) return;
		double slope = (n * sxy - sx * sy) / denom;
		if (Double.isNaN(slope)) return;
		slope = Math.clamp(slope, 0.98, 1.02); // never more than 2% faster/slower
		rate = rate * 0.7 + slope * 0.3;
	}

	// ---------------------------------------------------------------- demo (screenshots/tests only)

	private volatile MediaSnapshot demo;
	private volatile long demoStartNanos;

	/** Pretend this song is playing from its {@code positionMs()} (null = back to the real player). */
	public void setDemo(MediaSnapshot snapshot) {
		demo = snapshot;
		demoStartNanos = System.nanoTime();
	}

	public boolean demoActive() {
		return demo != null;
	}

	/** Current smooth position in ms. */
	public long positionMs() {
		MediaSnapshot d = demo;
		if (d != null) return d.positionMs() + (System.nanoTime() - demoStartNanos) / 1_000_000;
		long pos = estimatedRawPositionMs();
		MediaSnapshot s = latest;
		if (s != null && s.durationMs() > 0) pos = Math.min(pos, s.durationMs());
		return Math.max(0, pos);
	}

	public MediaSnapshot current() {
		MediaSnapshot d = demo;
		if (d != null) return d;
		MediaSnapshot s = latest;
		return s != null && s.hasTrack() ? s : null;
	}

	public boolean isPlaying() {
		if (demo != null) return true;
		return playing;
	}
}
