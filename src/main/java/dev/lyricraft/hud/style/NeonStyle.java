package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.lyrics.Lyrics;

/**
 * NEON: the line is a neon sign. Unsung letters are dark glass tubes; as each letter is sung
 * it buzzes on in the song's colours with a heavy glow. Lit letters flicker now and then,
 * the whole sign hums with the beat, and finished lines switch off letter by letter.
 */
public final class NeonStyle implements LyricStyle {

	private final LineClock clock = new LineClock();

	@Override
	public void render(Frame f) {
		int slot = f.slot();
		clock.update(slot, f.nowNanos());
		float age = clock.age(f.nowNanos());
		float s = 1.9f * f.cfg().scale;
		float y = switch (f.cfg().position) {
			case TOP_CENTER -> 30;
			case LEFT_SIDE, RIGHT_SIDE -> f.height() / 2f - 10;
			case CUSTOM -> f.cfg().lyricsY * f.height() - 4.5f * s;
			default -> f.height() - 96;
		};

		// previous sign switches off from left to right
		Lyrics.Line prev = f.line(clock.previous());
		if (prev != null && age < 0.45f) drawSign(f, prev.text(), y, s, 1f, age / 0.45f, f.vis());

		Lyrics.Line line = f.line(slot);
		if (line == null) return;
		boolean inBreak = f.active() < 0;
		float progress = inBreak ? 0f : line.progress(f.posMs());
		float alpha = f.vis() * Math.clamp(age / 0.2f, 0f, 1f);
		drawSign(f, line.text(), y, s, progress, 0f, inBreak ? alpha * 0.6f : alpha);

		Lyrics.Line next = f.line(slot + 1);
		if (next != null && f.cfg().showContextLines) {
			float ns = s * 0.45f;
			int nw = f.font().width(next.text());
			ns = Draw.fitScale(nw, ns, Place.maxWidth(f));
			Draw.text(f.g(), f.font(), next.text(), Place.centerX(f) - nw * ns / 2f, y + f.font().lineHeight * s + 6,
					ns, Draw.argb(0xFFFFFF, 0.3f * f.vis()), true);
		}
	}

	/**
	 * @param progress how much of the line is lit (0..1)
	 * @param offSweep 0..1 sweep that switches letters off (for the outgoing line)
	 */
	private static void drawSign(Frame f, String text, float y, float s, float progress, float offSweep, float alpha) {
		if (alpha < 0.02f) return;
		Letters ls = Letters.of(f.font(), text);
		s = Draw.fitScale(ls.totalWidth(), s, Place.maxWidth(f));
		float x0 = Place.centerX(f) - ls.totalWidth() * s / 2f;
		float t = f.time();
		float hum = 0.9f + 0.1f * (float) Math.sin(t * 47) + f.pulse() * 0.15f;

		int i = 0;
		for (Letters.Letter l : ls.letters()) {
			i++;
			if (l.space()) continue;
			float lx = x0 + l.x() * s;
			boolean lit = progress >= l.at() && progress > 0f && l.at() >= offSweep;
			if (offSweep > 0f) lit = l.at() >= offSweep;

			if (!lit) {
				// dark glass tube
				Draw.text(f.g(), f.font(), l.ch(), lx, y, s, Draw.argb(0x3A3A44, 0.75f * alpha), false);
				continue;
			}
			// colour runs along the sign: colorA -> colorB
			int rgb = Draw.mix(f.colorA(), f.colorB(), l.x() / (float) Math.max(1, ls.totalWidth()));
			// occasional flicker per letter
			double fl = Math.sin(t * 13.0 + i * 7.31) * Math.sin(t * 3.1 + i * 1.7);
			float flicker = fl > 0.93 ? 0.25f : 1f;
			// just-lit letters flash white for a moment
			float sinceLit = (progress - l.at()) * 8f;
			if (offSweep == 0f && sinceLit < 1f) rgb = Draw.mix(0xFFFFFF, rgb, sinceLit);
			Draw.glowText(f.g(), f.font(), l.ch(), lx, y, s, rgb, alpha * flicker * Math.min(1f, hum), 1.4f);
		}
	}
}
