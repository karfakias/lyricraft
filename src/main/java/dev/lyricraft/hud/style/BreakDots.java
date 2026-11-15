package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;

/** Three bouncing notes shown during instrumental breaks. */
final class BreakDots {
	private BreakDots() {}

	static void draw(Frame f, float anchorX, float y, float scale, Draw.Align align, float alpha) {
		String note = "♪";
		float step = f.font().width(note + "  ") * scale;
		float total = step * 2 + f.font().width(note) * scale;
		float x = Draw.alignX(anchorX, total, align);
		for (int i = 0; i < 3; i++) {
			double phase = f.posMs() / 600.0 - i * 0.33;
			float wave = (float) Math.sin(phase * Math.PI * 2);
			float pulse = 0.5f + 0.5f * wave;
			Draw.glowText(f.g(), f.font(), note, x + step * i, y - wave * 1.5f * scale, scale,
					f.accent(), alpha * (0.35f + 0.65f * pulse), pulse);
		}
	}
}
