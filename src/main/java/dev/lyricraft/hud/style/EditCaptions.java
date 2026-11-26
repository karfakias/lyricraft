package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.Sprites;
import dev.lyricraft.lyrics.Lyrics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The words on screen while the Edit style is on a cinematic angle (the 3D words on the wall
 * are often out of the shot then), with black bars top and bottom. Two looks from the edits:
 *
 * <ul>
 * <li>One word at a time in the middle, big Minecraft letters with a soft pale-blue glow that
 * pop in on every word (the classic "lyrics over cinematic shots" Minecraft edit).</li>
 * <li>Hitsync, for pop and love songs: the line builds up across the screen word by word, each
 * word in a different real font and colour (lavender, mint, white), slightly tilted, zooming in
 * with a blur trail; a long word gets the whole middle of the screen to itself.</li>
 * </ul>
 */
final class EditCaptions {
	private static final int[] HITSYNC_COLORS = {0xB9A6FF, 0xCFF7E6, 0xF4F4FF, 0x8F7BFF, 0xFFC6E8};
	private static final WordArt.Face[] HITSYNC_FACES = {WordArt.Face.HEAVY, WordArt.Face.SERIF_ITALIC, WordArt.Face.HEAVY, WordArt.Face.SERIF_BOLD};

	/** A hitsync word on screen: where (0..1 of the screen), how tall (0..1), tilt, colour, when it came in. */
	private record Placed(WordArt.Glyph glyph, String text, float x, float y, float h, float tilt, int color, float at) {}

	private float show, linger;
	private int lineIdx = Integer.MIN_VALUE, wordIdx = -1;
	private float wordAt;
	private String word = "";
	private final List<Placed> placed = new ArrayList<>();
	private float nextX, nextY;

	/**
	 * @param on    true while the camera is on an angle
	 * @param style the song's edit style (pop and love songs get hitsync), or null
	 * @param color the song's colour
	 */
	void render(Frame f, float clock, float dt, boolean on, EditSongs.Style style, int color, float vis) {
		show = Draw.approach(show, on ? 1f : 0f, dt * (on ? 5f : 3f));
		if (show < 0.01f) {
			placed.clear();
			lineIdx = Integer.MIN_VALUE;
			return;
		}
		GuiGraphicsExtractor g = f.g();
		int w = f.width(), h = f.height();
		float k = Draw.easeOutCubic(show);

		// cinema bars
		int bar = Math.round(h * 0.085f * k);
		g.fill(0, 0, w, bar, Draw.argb(0x000000, 0.92f * vis));
		g.fill(0, h - bar, w, h, Draw.argb(0x000000, 0.92f * vis));

		// which word is being sung
		int active = f.active();
		Lyrics.Line line = active >= 0 ? f.line(active) : null;
		linger = Draw.approach(linger, line != null ? 1f : 0f, dt * (line != null ? 8f : 2.5f));
		boolean hitsync = style == EditSongs.Style.POP || style == EditSongs.Style.LOVE;
		if (line != null) {
			String[] words = EditStyle.splitWords(line.text());
			float[] at = EditStyle.startsOf(words);
			float p = line.progress(f.posMs());
			int wi = 0;
			for (int i = 0; i < words.length; i++) if (at[i] <= p) wi = i;
			if (active != lineIdx) {
				lineIdx = active;
				wordIdx = -1;
				placed.clear();
				nextX = 0.1f;
				nextY = 0.24f;
			}
			if (wi != wordIdx) {
				for (int i = wordIdx + 1; i <= wi; i++) {
					if (hitsync) place(words, i, clock);
				}
				wordIdx = wi;
				word = clean(words[wi]);
				wordAt = clock;
			}
		}
		float a = vis * k * linger;
		if (a < 0.02f) return;
		if (hitsync) drawHitsync(g, w, h, clock, a);
		else drawCenter(g, f.font(), w, h, clock, a, color);
	}

	// ------------------------------------------------------------------ one word in the middle

	private void drawCenter(GuiGraphicsExtractor g, Font font, int w, int h, float clock, float a, int color) {
		if (word.isEmpty()) return;
		String text = WordArt.upper(word);
		float age = clock - wordAt;
		float pop = 1f + 0.22f * (1f - Draw.easeOutCubic(Math.clamp(age / 0.14f, 0f, 1f)));
		float in = Math.clamp(age / 0.05f, 0f, 1f);
		int rgb = Draw.mix(0xC4E4FF, color, 0.22f);
		float s = Math.max(2f, h / 92f) * pop;
		if (WordArt.needsRealFont(text) && WordArt.available()) {
			// Arabic, Hindi, Thai...: the pixel font can't draw them, so the real-font version
			WordArt.Glyph gl = WordArt.get(WordArt.Face.HEAVY, text);
			if (gl != null) {
				float gh = 13f * s * 0.9f, gw = gh * gl.aspect();
				Sprites.glow(g, w / 2f - gw / 2f - 10, h / 2f - gh / 2f - 6, gw + 20, gh + 12, Draw.argb(rgb, 0.35f * a * in));
				Sprites.draw(g, gl.tex(), w / 2f - gw / 2f, h / 2f - gh / 2f, gw, gh, Draw.argb(rgb, a * in));
				return;
			}
		}
		float tw = font.width(Draw.visual(text)) * s;
		Draw.glowText(g, font, text, w / 2f - tw / 2f, h / 2f - 4.5f * s, s, rgb, a * in, 1f);
	}

	// ------------------------------------------------------------------ hitsync

	/** The edits show clean words: no commas or brackets round them (apostrophes stay). */
	private static String clean(String w) {
		String c = w.replaceAll("^[\\p{P}&&[^'’]]+|[\\p{P}&&[^'’]]+$", "");
		return c.isEmpty() ? w : c;
	}

	private void place(String[] words, int i, float clock) {
		String wd = clean(words[i]);
		Random r = new Random(lineIdx * 977L + i * 31L + wd.hashCode());
		int longest = 0;
		for (int j = 1; j < words.length; j++) if (words[j].length() > words[longest].length()) longest = j;
		boolean hero = words.length <= 2 || (i == longest && wd.length() >= 5);
		WordArt.Face face = hero ? (r.nextBoolean() ? WordArt.Face.SERIF_ITALIC : WordArt.Face.HEAVY)
				: HITSYNC_FACES[r.nextInt(HITSYNC_FACES.length)];
		// hitsync keeps the words as they're written ("Something straight out of a"), not all caps
		WordArt.Glyph gl = WordArt.get(face, wd, true);
		if (gl == null) gl = WordArt.get(WordArt.Face.HEAVY, wd, true);
		if (gl == null) return; // no system fonts at all: nothing to show in this look
		int color = HITSYNC_COLORS[r.nextInt(HITSYNC_COLORS.length)];
		float tilt = (r.nextFloat() - 0.5f) * 0.22f;
		if (hero) {
			// a big word gets the middle of the screen to itself (the rest of the line fades back)
			placed.add(new Placed(gl, wd, 0.5f, 0.5f, 0.2f, tilt * 0.5f, color, clock));
			nextX = 0.12f + r.nextFloat() * 0.1f;
			nextY = 0.66f;
			return;
		}
		float hh = 0.075f + r.nextFloat() * 0.02f;
		float est = hh * gl.aspect() * 9f / 16f; // width as a part of the screen (16:9)
		if (nextX + est > 0.92f) {
			nextX = 0.08f + r.nextFloat() * 0.12f;
			nextY += hh * 1.25f;
		}
		if (nextY > 0.7f) { // keep clear of the bars: clear the screen and start again near the top
			nextY = 0.22f + r.nextFloat() * 0.06f;
			nextX = 0.08f + r.nextFloat() * 0.12f;
			placed.removeIf(p -> p.h() < 0.15f);
		}
		placed.add(new Placed(gl, wd, nextX + est / 2f, nextY, hh, tilt, color, clock));
		// the next word steps along and down a little, like they build up in the edits
		nextX += est + 0.02f + r.nextFloat() * 0.05f;
		nextY += hh * (0.35f + r.nextFloat() * 0.5f);
	}

	private void drawHitsync(GuiGraphicsExtractor g, int w, int h, float clock, float a) {
		Placed hero = null;
		for (Placed p : placed) if (p.h() > 0.15f) hero = p;
		for (Placed p : placed) {
			float age = clock - p.at();
			float k = Draw.easeOutCubic(Math.clamp(age / 0.16f, 0f, 1f));
			// once a big word lands, the others step back
			float dim = hero != null && p != hero && hero.at() > p.at() ? 0.35f : 1f;
			float ph = p.h() * h, pw = ph * p.glyph().aspect();
			float cx = p.x() * w, cy = p.y() * h;
			g.pose().pushMatrix();
			g.pose().translate(cx, cy);
			g.pose().rotate(p.tilt());
			// zoom in with a blur trail
			if (k < 1f) {
				for (int c = 2; c >= 1; c--) {
					float s = 1f + (0.35f + 0.25f * c) * (1f - k);
					Sprites.draw(g, p.glyph().tex(), -pw * s / 2f, -ph * s / 2f, pw * s, ph * s, Draw.argb(p.color(), 0.22f * (1f - k) * a));
				}
			}
			float s = 1f + 0.5f * (1f - k);
			Sprites.draw(g, p.glyph().tex(), -pw * s / 2f, -ph * s / 2f, pw * s, ph * s, Draw.argb(p.color(), k * a * dim));
			g.pose().popMatrix();
		}
	}
}
