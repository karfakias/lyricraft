package dev.lyricraft.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen areas that are already taken this frame (the Now Playing card, toasts, the hotbar),
 * so world-pinned lyrics like Echoes can stay out of their way.
 */
public final class HudSpace {
	private HudSpace() {}

	private static final List<float[]> TAKEN = new ArrayList<>();

	public static void clear() {
		TAKEN.clear();
	}

	public static void reserve(float x0, float y0, float x1, float y1) {
		TAKEN.add(new float[]{x0, y0, x1, y1});
	}

	/** How much of this rectangle (0..1 of its own area) is covered by taken space. */
	public static float covered(float x0, float y0, float x1, float y1) {
		float area = Math.max(1f, (x1 - x0) * (y1 - y0)), hit = 0f;
		for (float[] r : TAKEN) {
			float w = Math.min(x1, r[2]) - Math.max(x0, r[0]);
			float h = Math.min(y1, r[3]) - Math.max(y0, r[1]);
			if (w > 0 && h > 0) hit += w * h;
		}
		return Math.min(1f, hit / area);
	}

	public static boolean overlaps(float x0, float y0, float x1, float y1) {
		return covered(x0, y0, x1, y1) > 0f;
	}
}
