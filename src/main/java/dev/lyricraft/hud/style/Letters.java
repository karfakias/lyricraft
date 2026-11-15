package dev.lyricraft.hud.style;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/** Splits a line into single characters with their x position and karaoke timing. */
record Letters(List<Letter> letters, int totalWidth) {

	/** {@code at} = fraction of the line (0..1) at which this letter is sung. */
	record Letter(String ch, int x, int width, float at, boolean space) {}

	static Letters of(Font font, String text) {
		List<Letter> out = new ArrayList<>();
		int nonSpace = Math.max(1, (int) text.codePoints().filter(c -> !Character.isWhitespace(c)).count());
		int seen = 0, x = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			int w = font.width(ch);
			boolean space = Character.isWhitespace(cp);
			out.add(new Letter(ch, x, w, seen / (float) nonSpace, space));
			if (!space) seen++;
			x += w;
			i += Character.charCount(cp);
		}
		return new Letters(out, x);
	}
}
