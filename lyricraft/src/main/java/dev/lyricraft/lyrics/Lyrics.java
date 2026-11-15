package dev.lyricraft.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-synced lyrics for one track (parsed from LRC). */
public final class Lyrics {

	public record Line(long startMs, long endMs, String text) {
		/** 0..1 how far through this line we are. */
		public float progress(long posMs) {
			long len = Math.max(1, endMs - startMs);
			return Math.clamp((posMs - startMs) / (float) len, 0f, 1f);
		}

		public long durationMs() {
			return Math.max(1, endMs - startMs);
		}

		/**
		 * Splits the line into words with an estimated start time (0..1 of the line) for each,
		 * based on character position, since LRC only gives one timestamp per line.
		 */
		public java.util.List<Word> words() {
			java.util.List<Word> out = new ArrayList<>();
			String[] parts = text.trim().split("\\s+");
			int total = Math.max(1, text.replaceAll("\\s+", "").length());
			int seen = 0;
			for (String p : parts) {
				if (p.isEmpty()) continue;
				float start = seen / (float) total;
				seen += p.length();
				out.add(new Word(p, start, seen / (float) total));
			}
			return out;
		}
	}

	/** A word and the fraction of the line at which it starts and ends. */
	public record Word(String text, float start, float end) {}

	public enum Kind { SYNCED, PLAIN_ONLY, INSTRUMENTAL, NOT_FOUND }

	public static final Lyrics NOT_FOUND = new Lyrics(Kind.NOT_FOUND, List.of());
	public static final Lyrics INSTRUMENTAL = new Lyrics(Kind.INSTRUMENTAL, List.of());
	public static final Lyrics PLAIN_ONLY = new Lyrics(Kind.PLAIN_ONLY, List.of());

	/** A line is "sung" for at most this long; long gaps after it count as an instrumental break. */
	private static final long MAX_LINE_MS = 7000;

	private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

	public final Kind kind;
	public final List<Line> lines;

	private Lyrics(Kind kind, List<Line> lines) {
		this.kind = kind;
		this.lines = lines;
	}

	public static Lyrics parseLrc(String lrc, long durationMs) {
		record Stamp(long ms, String text) {}
		List<Stamp> stamps = new ArrayList<>();
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher m = TIME.matcher(raw);
			List<Long> times = new ArrayList<>();
			int textStart = 0;
			while (m.find() && m.start() == textStart) {
				long min = Long.parseLong(m.group(1));
				long sec = Long.parseLong(m.group(2));
				String frac = m.group(3) == null ? "0" : m.group(3);
				long ms = switch (frac.length()) {
					case 1 -> Long.parseLong(frac) * 100;
					case 2 -> Long.parseLong(frac) * 10;
					default -> Long.parseLong(frac);
				};
				times.add(min * 60_000 + sec * 1000 + ms);
				textStart = m.end();
			}
			if (times.isEmpty()) continue;
			String text = raw.substring(textStart).trim();
			for (long t : times) stamps.add(new Stamp(t, text));
		}
		if (stamps.isEmpty()) return NOT_FOUND;
		stamps.sort((a, b) -> Long.compare(a.ms, b.ms));

		List<Line> lines = new ArrayList<>();
		for (int i = 0; i < stamps.size(); i++) {
			Stamp s = stamps.get(i);
			if (s.text.isEmpty()) continue; // blank LRC lines just mark the end of the previous one
			long next = i + 1 < stamps.size() ? stamps.get(i + 1).ms : (durationMs > 0 ? durationMs : s.ms + 4000);
			long end = Math.min(next, s.ms + MAX_LINE_MS);
			lines.add(new Line(s.ms, Math.max(end, s.ms + 300), s.text));
		}
		return lines.isEmpty() ? NOT_FOUND : new Lyrics(Kind.SYNCED, Collections.unmodifiableList(lines));
	}

	/** Index of the line being sung at {@code posMs}, or -1 before the first line / during breaks. */
	public int activeIndex(long posMs) {
		int idx = lastStartedIndex(posMs);
		if (idx < 0) return -1;
		return posMs < lines.get(idx).endMs ? idx : -1;
	}

	/** Index of the last line that has started (even if it has already ended), or -1. */
	public int lastStartedIndex(long posMs) {
		int lo = 0, hi = lines.size() - 1, ans = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (lines.get(mid).startMs <= posMs) {
				ans = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return ans;
	}
}
