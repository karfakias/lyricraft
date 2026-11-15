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
			java.util.List<Word> cached = WORDS.get(this);
			if (cached != null) return cached;
			java.util.List<Word> out = splitWords();
			if (WORDS.size() > 4000) WORDS.clear();
			WORDS.put(this, out);
			return out;
		}

		private java.util.List<Word> splitWords() {
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
			return java.util.List.copyOf(out);
		}
	}

	/** Words per line, worked out once (they were split with a regex every frame before). */
	private static final java.util.Map<Line, java.util.List<Word>> WORDS = new java.util.concurrent.ConcurrentHashMap<>();

	/** A word and the fraction of the line at which it starts and ends. */
	public record Word(String text, float start, float end) {}

	public enum Kind { SYNCED, PLAIN_ONLY, INSTRUMENTAL, NOT_FOUND }

	public static final Lyrics NOT_FOUND = new Lyrics(Kind.NOT_FOUND, List.of());
	public static final Lyrics INSTRUMENTAL = new Lyrics(Kind.INSTRUMENTAL, List.of());
	public static final Lyrics PLAIN_ONLY = new Lyrics(Kind.PLAIN_ONLY, List.of());

	/** A line is "sung" for at most this long; long gaps after it count as an instrumental break. */
	private static final long MAX_LINE_MS = 7000;

	private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
	/** Limits for downloaded lyric files, so a broken or hostile file can't eat all the memory. */
	private static final int MAX_LRC_CHARS = 400_000, MAX_STAMPS = 2000;

	/** [offset:+300] — "everything in this file is 300 ms late, show it sooner". */
	private static final Pattern OFFSET = Pattern.compile("(?i)^\\s*\\[offset:\\s*([+-]?\\d+)\\s*]");

	/** One timestamp from an LRC file. An empty text marks the end of the previous line. */
	public record Stamp(long ms, String text) {}

	public final Kind kind;
	public final List<Line> lines;
	/** How many lyric versions were combined into these timings (1 = just one source). */
	public final int versions;
	/** Where the lyrics came from, for the menu (e.g. "LRCLIB", "LRCLIB + NetEase"). */
	public final String source;

	private Lyrics(Kind kind, List<Line> lines) {
		this(kind, lines, 0, "");
	}

	private Lyrics(Kind kind, List<Line> lines, int versions, String source) {
		this.kind = kind;
		this.lines = lines;
		this.versions = versions;
		this.source = source;
	}

	/** Same lyrics, labelled with how they were made. */
	public Lyrics withInfo(int versions, String source) {
		return new Lyrics(kind, lines, versions, source);
	}

	public static Lyrics parseLrc(String lrc, long durationMs) {
		return build(parseStamps(lrc), durationMs);
	}

	/** Every timestamp in an LRC file, sorted, with the file's [offset:] already applied. */
	public static List<Stamp> parseStamps(String lrc) {
		List<Stamp> stamps = new ArrayList<>();
		if (lrc == null) return stamps;
		if (lrc.length() > MAX_LRC_CHARS) lrc = lrc.substring(0, MAX_LRC_CHARS); // no real song is this long
		long offset = 0;
		for (String raw : lrc.split("\\r?\\n")) {
			Matcher off = OFFSET.matcher(raw);
			if (off.find()) {
				try {
					offset = Math.clamp(Long.parseLong(off.group(1)), -10_000, 10_000);
				} catch (NumberFormatException ignored) {
					// keep 0
				}
				continue;
			}
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
			// text comes from the internet: strip colour codes and invisible characters
			String text = dev.lyricraft.online.SafeText.clean(raw.substring(textStart), 300);
			for (long t : times) {
				if (stamps.size() >= MAX_STAMPS) break;
				stamps.add(new Stamp(Math.min(t, 24 * 3_600_000L), text));
			}
		}
		if (offset != 0) {
			// a positive offset means the lyrics should appear sooner
			final long o = offset;
			stamps.replaceAll(s -> new Stamp(Math.max(0, s.ms - o), s.text));
		}
		stamps.sort((a, b) -> Long.compare(a.ms, b.ms));
		return stamps;
	}

	/** Turns sorted timestamps into lines (blank stamps end the line before them). */
	public static Lyrics build(List<Stamp> stamps, long durationMs) {
		if (stamps.isEmpty()) return NOT_FOUND;
		List<Line> lines = new ArrayList<>();
		for (int i = 0; i < stamps.size(); i++) {
			Stamp s = stamps.get(i);
			if (s.text.isEmpty()) continue; // blank LRC lines just mark the end of the previous one
			long next = i + 1 < stamps.size() ? stamps.get(i + 1).ms : (durationMs > 0 ? durationMs : s.ms + 4000);
			long end = Math.min(next, s.ms + MAX_LINE_MS);
			lines.add(new Line(s.ms, Math.max(end, s.ms + 300), s.text));
		}
		return lines.isEmpty() ? NOT_FOUND : new Lyrics(Kind.SYNCED, Collections.unmodifiableList(lines), 1, "");
	}

	/**
	 * These lyrics as an LRC file, every timestamp moved by {@code shiftMs} (used to share a
	 * timing fix back to LRCLIB). Gaps after a line become a blank "end" stamp.
	 */
	public String toLrc(long shiftMs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			Line l = lines.get(i);
			sb.append(stamp(l.startMs + shiftMs)).append(' ').append(l.text).append('\n');
			long nextStart = i + 1 < lines.size() ? lines.get(i + 1).startMs : Long.MAX_VALUE;
			if (l.endMs + 250 < nextStart && i + 1 < lines.size()) sb.append(stamp(l.endMs + shiftMs)).append('\n');
		}
		return sb.toString();
	}

	private static String stamp(long ms) {
		ms = Math.max(0, ms);
		long cs = Math.round(ms / 10.0);
		return String.format("[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100);
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
