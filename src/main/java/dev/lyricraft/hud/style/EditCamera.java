package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;

/**
 * Camera moves for the Edit style, like a video edit: a quick zoom punch and a bit of shake and
 * roll on the hits, easing back right away. Read by the camera mixins every frame.
 */
public final class EditCamera {
	private EditCamera() {}

	private static float punch, shake;
	private static long lastActive;
	private static float t;

	/** Called each frame while the Edit style is drawing. */
	static void tick(float dt, boolean enabled) {
		if (!enabled) {
			punch = shake = 0f;
			return;
		}
		lastActive = System.nanoTime();
		t += dt;
		punch = Draw.approach(punch, 0f, dt * 3.4f);
		shake = Draw.approach(shake, 0f, dt * 2.8f);
	}

	static void hit(float punchAmount, float shakeAmount) {
		punch = Math.max(punch, punchAmount);
		shake = Math.max(shake, shakeAmount);
	}

	private static boolean live() {
		return System.nanoTime() - lastActive < 200_000_000L;
	}

	/** Multiplier for the field of view: below 1 zooms in. */
	public static float fovFactor() {
		if (!live() || punch <= 0.001f) return 1f;
		float p = Draw.easeOutCubic(punch);
		return 1f - 0.11f * p;
	}

	/** Extra camera roll in degrees. */
	public static float roll() {
		if (!live() || shake <= 0.001f) return 0f;
		float s = shake * shake;
		return s * 2.2f * (float) (Math.sin(t * 37.0) + 0.5 * Math.sin(t * 61.0));
	}

	/** Extra pitch shake in degrees. */
	public static float pitch() {
		if (!live() || shake <= 0.001f) return 0f;
		float s = shake * shake;
		return s * 1.1f * (float) (Math.cos(t * 43.0) + 0.5 * Math.sin(t * 29.0));
	}

	/** Extra yaw shake in degrees. */
	public static float yaw() {
		if (!live() || shake <= 0.001f) return 0f;
		float s = shake * shake;
		return s * 1.1f * (float) (Math.sin(t * 53.0) + 0.5 * Math.cos(t * 23.0));
	}
}
