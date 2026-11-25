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
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Words drawn with real fonts for the Edit style, like the lyric edits use (heavy condensed for
 * hype, serif for villain arcs, italic for sad, handwriting for love songs...). Each word is
 * drawn once with the fonts installed on the computer (white with a dark outline) into a small
 * texture, then stood up in the world as block letters. If a font isn't there, Java's own
 * serif / sans / mono stands in; if drawing fonts doesn't work at all, the pixel font is used.
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

	static boolean available() {
		if (works == null) {
			try {
				Set<String> installed = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
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
		return f.upper ? word.toUpperCase(Locale.ROOT) : word;
	}

	/** The word in this face (made once, then cached), or null if fonts can't be drawn. */
	static Glyph get(Face face, String word) {
		if (!available() || word.isBlank()) return null;
		String text = shown(face, word);
		String key = face.name() + "|" + text;
		Glyph g = CACHE.get(key);
		if (g != null) return g;
		try {
			g = draw(face, text);
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

	private static Glyph draw(Face face, String text) {
		Font font = face.font;
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		FontMetrics fm = pg.getFontMetrics(font);
		int w = Math.max(1, fm.stringWidth(text)) + PAD * 2 + SIZE / 8;
		int h = fm.getAscent() + fm.getDescent() + PAD * 2;
		pg.dispose();
		w = Math.min(w, 2048);

		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
		Shape shape = gv.getOutline();
		AffineTransform at = AffineTransform.getTranslateInstance(PAD + SIZE / 16.0, PAD + fm.getAscent());
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
