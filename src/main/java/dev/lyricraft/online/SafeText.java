package dev.lyricraft.online;

import java.util.regex.Pattern;

/**
 * Cleans text that came from somewhere else (other players, lyric sites) before we draw it:
 * removes Minecraft colour/format codes (§), control characters and invisible characters that
 * flip text direction or hide content, squashes whitespace and limits the length.
 */
public final class SafeText {
	private SafeText() {}

	private static final Pattern FORMAT = Pattern.compile("§.?", Pattern.DOTALL);
	private static final Pattern INVISIBLE = Pattern.compile(
			"[\u0000-\u001f\u007f-\u009f\u00ad\u061c\u115f\u1160\u180e\u200b-\u200f\u202a-\u202e\u2060-\u206f\u3164\ufeff\uffa0\ufff0-\uffff]");

	public static String clean(String s, int max) {
		if (s == null) return "";
		String t = FORMAT.matcher(s).replaceAll("");
		t = INVISIBLE.matcher(t).replaceAll("");
		t = t.replaceAll("\s+", " ").trim();
		return t.length() > max ? t.substring(0, max) : t;
	}
}
