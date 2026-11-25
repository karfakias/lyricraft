package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;

/**
 * Camera moves for the Edit style, like a video edit: a quick zoom punch and a bit of shake and
 * roll on the hits, easing back right away. Read by the camera mixins every frame.
 */
public final class EditCamera {
	private EditCamera() {}

	private static float punch, shake, drift, driftNow, dive, diveNow, sway, swayNow;
	private static long lastActive;
	private static float t;

	/** Called each frame while the Edit style is drawing. */
	static void tick(float dt, boolean enabled) {
		if (!enabled) {
			punch = shake = drift = driftNow = dive = diveNow = sway = swayNow = 0f;
			return;
		}
		lastActive = System.nanoTime();
		t += dt;
		punch = Draw.approach(punch, 0f, dt * 3.4f);
		// follow the push-in smoothly, but snap back fast when a new line resets it
		driftNow = drift < driftNow ? Draw.approach(driftNow, drift, dt * 0.6f) : Draw.approach(driftNow, drift, dt * 0.08f);
		shake = Draw.approach(shake, 0f, dt * 2.8f);
		// the dive builds smoothly but lets go at once when the drop hits
		diveNow = dive > diveNow ? Draw.approach(diveNow, dive, dt * 3f) : dive;
		swayNow = Draw.approach(swayNow, sway, dt * 0.8f);
	}

	/** 0..1 as the drop gets close: the camera dives into the text. */
	static void dive(float amount) {
		dive = amount;
	}

	/** 1 while a chorus line is on: a slow handheld-like roll. */
	static void sway(float amount) {
		sway = amount;
	}

	/** Slow push-in while a line is sung (0..about 0.05 of the field of view). */
	static void drift(float amount) {
		drift = amount;
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
		if (!live()) return 1f;
		float p = Draw.easeOutCubic(punch);
		float d = diveNow * diveNow;
		return (1f - 0.11f * p) * (1f - driftNow) * (1f - 0.45f * d);
	}

	/** Extra camera roll in degrees. */
	public static float roll() {
		if (!live()) return 0f;
		float s = shake * shake;
		return s * 2.2f * (float) (Math.sin(t * 37.0) + 0.5 * Math.sin(t * 61.0))
				+ swayNow * 1.4f * (float) Math.sin(t * 0.9) + diveNow * diveNow * 4f;
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
