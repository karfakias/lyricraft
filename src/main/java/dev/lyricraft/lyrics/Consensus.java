package dev.lyricraft.lyrics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Combines several timed versions of the same lyrics into one better-timed version.
 *
 * Synced lyrics on LRCLIB are made by volunteers, so each version is a little early or late in
 * its own way (we measured the same first line at anywhere between 7.32 s and 7.95 s across 20
 * versions of one song). Taking the middle value of every line across versions cancels most of
 * those personal errors out, and versions that were timed to a different cut of the song
 * (radio edit, live, extended intro) disagree with everyone else and get dropped.
 *
 * How it works:
 * 1. Every pair of versions is lined up by their text (a longest-common-subsequence match, so
 *    a chorus that appears four times can't get mixed up with a different repeat).
 * 2. The version that agrees best with all the others becomes the reference.
 * 3. Versions whose overall timing is more than a second away from it are thrown out.
 * 4. Each reference line gets the median time of that line across the remaining versions.
 */
public final class Consensus {
	private Consensus() {}

	/** A version further than this from the reference was timed to a different cut of the song. */
	private static final long MAX_VERSION_SHIFT_MS = 1000;
	/** Ignore one version's time for one line if it's this far from the rest (a typo in that line). */
	private static final long MAX_LINE_SPREAD_MS = 2500;
	/** Don't spend forever on hugely popular songs. */
	private static final int MAX_VERSIONS = 16;
	/** No real song has more lines; keeps the line-by-line matching quick and small. */
	private static final int MAX_LINES = 600;

	/** One version of the lyrics: its timestamps in order. */
	public record Version(List<Lyrics.Stamp> stamps, String source) {}

	/** Result: the combined timestamps and how many versions agreed. */
	public record Result(List<Lyrics.Stamp> stamps, int versionsUsed, Set<String> sources) {}

	public static Result combine(List<Version> input) {
		List<Version> versions = input.size() > MAX_VERSIONS ? input.subList(0, MAX_VERSIONS) : input;
		int n = versions.size();
		List<List<Lyrics.Stamp>> sung = new ArrayList<>();
		List<String[]> keys = new ArrayList<>();
		for (Version v : versions) {
			List<Lyrics.Stamp> lines = v.stamps().stream().filter(s -> !s.text().isEmpty()).limit(MAX_LINES).toList();
			sung.add(lines);
			keys.add(lines.stream().map(s -> normalize(s.text())).toArray(String[]::new));
		}
		if (n == 1) return new Result(versions.getFirst().stamps(), 1, Set.of(versions.getFirst().source()));

		// pairwise alignment + overall shift (median time difference of matched lines)
		int[][][] match = new int[n][n][];
		long[][] shift = new long[n][n];
		double[][] agreement = new double[n][n];
		for (int a = 0; a < n; a++) {
			for (int b = 0; b < n; b++) {
				if (a == b) continue;
				int[] m = align(keys.get(a), keys.get(b));
				match[a][b] = m;
				List<Lyrics.Stamp> la = sung.get(a), lb = sung.get(b);
				long[] diffs = Arrays.stream(indices(m)).mapToLong(i -> lb.get(m[i]).ms() - la.get(i).ms()).toArray();
				shift[a][b] = diffs.length == 0 ? Long.MAX_VALUE / 4 : median(diffs);
				agreement[a][b] = diffs.length / (double) Math.max(1, keys.get(a).length);
			}
		}

		// reference = the version closest to everyone else (and that shares most of their lines)
		int ref = 0;
		double best = Double.MAX_VALUE;
		for (int a = 0; a < n; a++) {
			double cost = 0;
			for (int b = 0; b < n; b++) {
				if (a == b) continue;
				cost += Math.min(Math.abs(shift[a][b]), 3000) + (1 - agreement[a][b]) * 3000;
			}
			if (cost < best) {
				best = cost;
				ref = a;
			}
		}

		// keep versions that agree with the reference
		List<Integer> keep = new ArrayList<>();
		keep.add(ref);
		for (int b = 0; b < n; b++) {
			if (b != ref && Math.abs(shift[ref][b]) <= MAX_VERSION_SHIFT_MS && agreement[ref][b] >= 0.6) keep.add(b);
		}
		Set<String> sources = new HashSet<>();
		for (int k : keep) sources.add(versions.get(k).source());
		List<Lyrics.Stamp> refLines = sung.get(ref);
		if (keep.size() < 2) return new Result(versions.get(ref).stamps(), 1, sources);

		// each reference line: median time across the versions that have it
		long[] moved = new long[refLines.size()];
		for (int i = 0; i < refLines.size(); i++) {
			long own = refLines.get(i).ms();
			List<Long> times = new ArrayList<>();
			times.add(own);
			for (int b : keep) {
				if (b == ref) continue;
				int j = match[ref][b][i];
				if (j < 0) continue;
				long t = sung.get(b).get(j).ms();
				if (Math.abs(t - own - shift[ref][b]) <= MAX_LINE_SPREAD_MS) times.add(t);
			}
			long[] arr = times.stream().mapToLong(Long::longValue).toArray();
			moved[i] = median(arr) - own;
		}

		// apply the corrections, keeping the reference's blank "line ended" stamps in step
		List<Lyrics.Stamp> out = new ArrayList<>();
		long lastDelta = moved.length > 0 ? moved[0] : 0;
		int line = 0;
		for (Lyrics.Stamp s : versions.get(ref).stamps()) {
			if (!s.text().isEmpty()) lastDelta = moved[line++];
			out.add(new Lyrics.Stamp(Math.max(0, s.ms() + lastDelta), s.text()));
		}
		// a blank stamp must never jump ahead of the line after it
		out.sort((x, y) -> Long.compare(x.ms(), y.ms()));
		return new Result(out, keep.size(), sources);
	}

	/**
	 * Longest common subsequence of two line lists. Returns, for each line of {@code a}, the index
	 * of the matching line in {@code b}, or -1.
	 */
	static int[] align(String[] a, String[] b) {
		int[][] dp = new int[a.length + 1][b.length + 1];
		for (int i = a.length - 1; i >= 0; i--) {
			for (int j = b.length - 1; j >= 0; j--) {
				dp[i][j] = similar(a[i], b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
			}
		}
		int[] out = new int[a.length];
		Arrays.fill(out, -1);
		int i = 0, j = 0;
		while (i < a.length && j < b.length) {
			if (similar(a[i], b[j]) && dp[i][j] == dp[i + 1][j + 1] + 1) {
				out[i++] = j++;
			} else if (dp[i + 1][j] >= dp[i][j + 1]) {
				i++;
			} else {
				j++;
			}
		}
		return out;
	}

	/** Same line, allowing for small differences in spelling and punctuation. */
	static boolean similar(String a, String b) {
		if (a.equals(b)) return true;
		if (a.isEmpty() || b.isEmpty()) return false;
		Set<String> wa = new HashSet<>(Arrays.asList(a.split(" ")));
		Set<String> wb = new HashSet<>(Arrays.asList(b.split(" ")));
		Set<String> inter = new HashSet<>(wa);
		inter.retainAll(wb);
		int union = wa.size() + wb.size() - inter.size();
		return union > 0 && inter.size() / (double) union >= 0.6;
	}

	/** Lower case, no accents or punctuation, single spaces. */
	static String normalize(String s) {
		String t = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
		t = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
		return t;
	}

	private static int[] indices(int[] match) {
		return java.util.stream.IntStream.range(0, match.length).filter(i -> match[i] >= 0).toArray();
	}

	private static long median(long[] values) {
		long[] v = values.clone();
		Arrays.sort(v);
		int m = v.length / 2;
		return v.length % 2 == 1 ? v[m] : (v[m - 1] + v[m]) / 2;
	}
}
