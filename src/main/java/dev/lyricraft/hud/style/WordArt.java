package dev.lyricraft.hud.style;

import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.Sprites;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Words drawn with real fonts for the Edit style, like the lyric edits use (heavy condensed for
 * hype, serif for villain arcs, italic for sad, handwriting for love songs...). Each word is
 * drawn once with the fonts installed on the computer (white with a dark outline) into a small
 * texture, then stood up in the world as block letters. If a font isn't there, Java's own
 * serif / sans / mono stands in; if drawing fonts doesn't work at all, the pixel font is used.
 *
 * <p>Other languages: Impact, Georgia and the rest have no Korean, Japanese, Chinese, Arabic,
 * Hindi, Thai... so when a font can't draw a word, a font that can is used instead (keeping it
 * bold or italic), picked from the usual ones for that script and otherwise found among the
 * installed fonts. Words go through TextLayout, which joins Arabic letters, puts right-to-left
 * text the right way round and places the marks in Hindi or Thai, which the pixel font can't.
 */
final class WordArt {
	private WordArt() {}

	/** A font look. */
	enum Face {
		IMPACT(Font.PLAIN, true, "Impact", "Bahnschrift Condensed", "Haettenschweiler", "Arial Black", Font.SANS_SERIF),
		SERIF_BOLD(Font.BOLD, true, "Times New Roman", "Georgia", "Times", "Liberation Serif", Font.SERIF),
		SERIF_ITALIC(Font.ITALIC, false, "Georgia", "Times New Roman", "Times", "Liberation Serif", Font.SERIF),
		SCRIPT(Font.PLAIN, false, "Segoe Script", "Brush Script MT", "Snell Roundhand", "Apple Chancery", "Lucida Handwriting", "Gabriola", "Ink Free", Font.SERIF),
		HEAVY(Font.PLAIN, true, "Arial Black", "Segoe UI Black", "Arial Rounded MT Bold", "Helvetica Neue", Font.SANS_SERIF),
		MONO(Font.BOLD, false, "Courier New", "Consolas", "Courier", "Liberation Mono", Font.MONOSPACED);

		final int style;
		final boolean upper;
		final String[] names;
		Font font;

		Face(int style, boolean upper, String... names) {
			this.style = style;
			this.upper = upper;
			this.names = names;
		}
	}

	/** A word drawn into a texture: width/height ratio for layout. */
	record Glyph(Sprites.Tex tex, float aspect) {}

	private static final int SIZE = 72, PAD = 7;
	private static final Map<String, Glyph> CACHE = new LinkedHashMap<>(64, 0.75f, true);
	private static Boolean works;
	private static int made;
	private static Set<String> installed = Set.of();
	/** Fallback font per face and script (null = nothing installed can draw it). */
	private static final Map<String, Font> FALLBACK = new HashMap<>();

	/** Fonts that usually have a script, bold-looking ones first (Windows, macOS, Linux). */
	private static final Map<Character.UnicodeScript, String[]> FOR_SCRIPT = Map.ofEntries(
			Map.entry(Character.UnicodeScript.HANGUL, new String[]{"Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans CJK KR", "Noto Sans KR", "NanumGothic", "Gulim"}),
			Map.entry(Character.UnicodeScript.HIRAGANA, new String[]{"Yu Gothic UI", "Yu Gothic", "Meiryo", "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Noto Sans CJK JP", "Noto Sans JP", "MS Gothic"}),
			Map.entry(Character.UnicodeScript.KATAKANA, new String[]{"Yu Gothic UI", "Yu Gothic", "Meiryo", "Hiragino Sans", "Hiragino Kaku Gothic ProN", "Noto Sans CJK JP", "Noto Sans JP", "MS Gothic"}),
			Map.entry(Character.UnicodeScript.HAN, new String[]{"Microsoft YaHei", "Microsoft JhengHei", "PingFang SC", "Noto Sans CJK SC", "Noto Sans SC", "Yu Gothic", "SimHei", "SimSun"}),
			Map.entry(Character.UnicodeScript.ARABIC, new String[]{"Segoe UI", "Arial", "Tahoma", "Geeza Pro", "Noto Sans Arabic", "Noto Naskh Arabic", "DejaVu Sans"}),
			Map.entry(Character.UnicodeScript.HEBREW, new String[]{"Segoe UI", "Arial", "Tahoma", "Arial Hebrew", "Noto Sans Hebrew", "DejaVu Sans"}),
			Map.entry(Character.UnicodeScript.DEVANAGARI, new String[]{"Nirmala UI", "Mangal", "Kohinoor Devanagari", "Noto Sans Devanagari"}),
			Map.entry(Character.UnicodeScript.BENGALI, new String[]{"Nirmala UI", "Vrinda", "Kohinoor Bangla", "Noto Sans Bengali"}),
			Map.entry(Character.UnicodeScript.TAMIL, new String[]{"Nirmala UI", "Latha", "Tamil Sangam MN", "Noto Sans Tamil"}),
			Map.entry(Character.UnicodeScript.TELUGU, new String[]{"Nirmala UI", "Gautami", "Kohinoor Telugu", "Noto Sans Telugu"}),
			Map.entry(Character.UnicodeScript.GURMUKHI, new String[]{"Nirmala UI", "Raavi", "Noto Sans Gurmukhi"}),
			Map.entry(Character.UnicodeScript.THAI, new String[]{"Leelawadee UI", "Leelawadee", "Tahoma", "Thonburi", "Noto Sans Thai"}),
			Map.entry(Character.UnicodeScript.GREEK, new String[]{"Arial Black", "Segoe UI Black", "Arial", "Segoe UI", "Helvetica Neue", "DejaVu Sans"}),
			Map.entry(Character.UnicodeScript.CYRILLIC, new String[]{"Arial Black", "Segoe UI Black", "Arial", "Segoe UI", "Helvetica Neue", "DejaVu Sans"}),
			Map.entry(Character.UnicodeScript.ARMENIAN, new String[]{"Segoe UI", "Sylfaen", "Noto Sans Armenian"}),
			Map.entry(Character.UnicodeScript.GEORGIAN, new String[]{"Segoe UI", "Sylfaen", "Noto Sans Georgian"}));

	static boolean available() {
		if (works == null) {
			try {
				installed = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
				for (Face f : Face.values()) {
					String pick = Font.SERIF;
					for (String n : f.names) {
						if (installed.contains(n) || n.equals(Font.SERIF) || n.equals(Font.SANS_SERIF) || n.equals(Font.MONOSPACED)) {
							pick = n;
							break;
						}
					}
					f.font = new Font(pick, f.style, SIZE);
				}
				works = true;
			} catch (Throwable t) {
				LyricraftClient.LOG.info("Lyricraft: system fonts not available, using the pixel font ({})", t.toString());
				works = false;
			}
		}
		return works;
	}

	static String shown(Face f, String word) {
		return f.upper ? upper(word) : word;
	}

	/** Capitals. Greek drops its accents in capitals (ΘΆΛΑΣΣΑ is written ΘΑΛΑΣΣΑ), the diaeresis stays. */
	static String upper(String word) {
		String u = word.toUpperCase(Locale.ROOT);
		if (u.codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.GREEK)) return u;
		String d = java.text.Normalizer.normalize(u, java.text.Normalizer.Form.NFD).replaceAll("[\u0301\u0300\u0342\u0313\u0314\u0345]", "");
		return java.text.Normalizer.normalize(d, java.text.Normalizer.Form.NFC);
	}

	/**
	 * Words the Minecraft pixel font draws wrong: Arabic and Hebrew (it doesn't join the letters
	 * or turn them right-to-left) and scripts with marks that stack on letters (Hindi, Thai...).
	 */
	static boolean needsRealFont(String word) {
		for (int i = 0; i < word.length(); ) {
			int cp = word.codePointAt(i);
			i += Character.charCount(cp);
			byte dir = Character.getDirectionality(cp);
			if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
			switch (Character.UnicodeScript.of(cp)) {
				case DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, ORIYA, TAMIL, TELUGU, KANNADA, MALAYALAM, SINHALA,
				     THAI, LAO, TIBETAN, MYANMAR, KHMER -> {
					return true;
				}
				default -> { }
			}
		}
		return false;
	}

	/** True if the text reads right to left (Arabic, Hebrew...): its first strong letter decides. */
	static boolean rightToLeft(String text) {
		return dev.lyricraft.hud.Draw.rightToLeft(text);
	}

	/** A font that can draw the whole word in this face's style, or null if there's none installed. */
	static Font fontFor(Face face, String text) {
		int bad = face.font.canDisplayUpTo(text);
		if (bad == -1) return face.font;
		Character.UnicodeScript script = Character.UnicodeScript.of(text.codePointAt(bad));
		String key = face.name() + "|" + script;
		Font f;
		if (FALLBACK.containsKey(key)) {
			f = FALLBACK.get(key);
		} else {
			f = search(face, text, FOR_SCRIPT.get(script));
			FALLBACK.put(key, f);
		}
		// a word can mix scripts (or have a rare letter): look again just for this one
		return f == null || f.canDisplayUpTo(text) == -1 ? f : search(face, text, FOR_SCRIPT.get(script));
	}

	private static Font search(Face face, String text, String[] preferred) {
		List<String> names = new ArrayList<>();
		if (preferred != null) for (String n : preferred) if (installed.contains(n)) names.add(n);
		// then anything installed, bold-looking names first
		List<String> rest = new ArrayList<>(installed);
		rest.sort((a, b) -> Boolean.compare(!heavy(a), !heavy(b)));
		names.addAll(rest);
		names.add(Font.SANS_SERIF);
		for (String n : names) {
			Font f = new Font(n, face.style == Font.ITALIC ? Font.ITALIC : Font.BOLD, SIZE);
			if (f.canDisplayUpTo(text) == -1) return f;
		}
		return null;
	}

	private static boolean heavy(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return n.contains("black") || n.contains("bold") || n.contains("heavy") || n.contains("gothic");
	}

	/** The word in this face (made once, then cached), or null if fonts can't be drawn. */
	static Glyph get(Face face, String word) {
		return get(face, word, false);
	}

	/** Same, but keeping the word's own capitals even in a face that's normally all caps. */
	static Glyph get(Face face, String word, boolean keepCase) {
		if (!available() || word.isBlank()) return null;
		String text = keepCase ? word : shown(face, word);
		String key = face.name() + "|" + text;
		Glyph g = CACHE.get(key);
		if (g != null) return g;
		try {
			Font font = fontFor(face, text);
			if (font == null) return null; // nothing installed can draw it: the pixel font takes over
			g = draw(font, text);
		} catch (Throwable t) {
			LyricraftClient.LOG.info("Lyricraft: couldn't draw a word with a system font ({})", t.toString());
			works = false;
			return null;
		}
		CACHE.put(key, g);
		if (CACHE.size() > 96) {
			var it = CACHE.entrySet().iterator();
			it.next().getValue().tex().release();
			it.remove();
		}
		return g;
	}

	private static Glyph draw(Font font, String text) {
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		pg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		FontRenderContext frc = pg.getFontRenderContext();
		// TextLayout does the shaping: joined Arabic, right-to-left order, stacked marks
		TextLayout layout = new TextLayout(text, font, frc);
		FontMetrics fm = pg.getFontMetrics(font);
		int ascent = Math.max(fm.getAscent(), (int) Math.ceil(layout.getAscent()));
		int descent = Math.max(fm.getDescent(), (int) Math.ceil(layout.getDescent()));
		int w = Math.max(1, (int) Math.ceil(layout.getAdvance())) + PAD * 2 + SIZE / 8;
		int h = ascent + descent + PAD * 2;
		Shape shape = layout.getOutline(null);
		pg.dispose();
		w = Math.min(w, 2048);

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		AffineTransform at = AffineTransform.getTranslateInstance(PAD + SIZE / 16.0, PAD + ascent);
		Shape placed = at.createTransformedShape(shape);
		// a dark outline first, then the white letters on top (the colour comes from the tint later)
		g.setColor(new Color(16, 16, 20, 235));
		g.setStroke(new BasicStroke(SIZE * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(placed);
		g.setColor(Color.WHITE);
		g.fill(placed);
		g.dispose();

		int iw = w, ih = h;
		Sprites.Tex tex = Sprites.make("word_" + Integer.toHexString(text.hashCode()) + "_" + (made++), iw, ih, false, (x, y) -> {
			int argb = img.getRGB(x, y);
			// keep the colour of transparent pixels white, so smooth filtering doesn't darken the edges
			return (argb >>> 24) == 0 ? 0x00FFFFFF : argb;
		});
		return new Glyph(tex, iw / (float) ih);
	}
}
