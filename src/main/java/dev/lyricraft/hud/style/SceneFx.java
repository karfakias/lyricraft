package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Scene changes for the Edit style, like a cut in an edit: when a keyword is sung the sky fades
 * into a matching look ("night" gives a starry night sky, "sun" a golden sunset, "storm" a dark
 * sky with real rain and thunder, "hell" a red sky...). Only on your screen, only the sky, fog and
 * clouds: the light on the world stays the same, so it's still bright and you can still play.
 * It fades back a few seconds later. The real time and weather never change.
 */
public final class SceneFx {
	private SceneFx() {}

	enum Scene {
		// sky, fog, cloud (rgba), sunset glow (rgba), sun angle (deg, 0 = noon, NaN = keep), stars, rain
		NIGHT(v(0.03f, 0.04f, 0.12f), v(0.05f, 0.06f, 0.15f), v4(0.16f, 0.17f, 0.26f, 0.8f), v4(0, 0, 0, 0), 175f, 1f, 0f),
		GOLDEN(v(0.95f, 0.56f, 0.36f), v(1f, 0.66f, 0.42f), v4(1f, 0.74f, 0.58f, 0.95f), v4(1f, 0.52f, 0.18f, 0.95f), 84f, 0f, 0f),
		STORM(v(0.24f, 0.26f, 0.31f), v(0.3f, 0.32f, 0.36f), v4(0.24f, 0.25f, 0.29f, 1f), v4(0, 0, 0, 0), Float.NaN, 0f, 1f),
		RAIN(v(0.44f, 0.5f, 0.6f), v(0.52f, 0.57f, 0.66f), v4(0.5f, 0.53f, 0.6f, 1f), v4(0, 0, 0, 0), Float.NaN, 0f, 1f),
		BLOOD(v(0.55f, 0.04f, 0.06f), v(0.42f, 0.04f, 0.05f), v4(0.6f, 0.1f, 0.1f, 0.95f), v4(0.95f, 0.1f, 0.08f, 0.9f), 86f, 0f, 0f),
		DREAM(v(0.86f, 0.62f, 0.95f), v(0.95f, 0.78f, 0.96f), v4(1f, 0.88f, 1f, 1f), v4(1f, 0.6f, 0.85f, 0.7f), Float.NaN, 0.5f, 0f),
		OCEAN(v(0.08f, 0.42f, 0.74f), v(0.12f, 0.5f, 0.78f), v4(0.75f, 0.9f, 1f, 1f), v4(0, 0, 0, 0), Float.NaN, 0f, 0f),
		WINTER(v(0.74f, 0.84f, 0.95f), v(0.86f, 0.92f, 1f), v4(0.95f, 0.97f, 1f, 1f), v4(0, 0, 0, 0), Float.NaN, 0f, 0f);

		final Vector3f sky, fog;
		final Vector4f cloud, glow;
		final float sun, stars, rain;

		Scene(Vector3f sky, Vector3f fog, Vector4f cloud, Vector4f glow, float sun, float stars, float rain) {
			this.sky = sky;
			this.fog = fog;
			this.cloud = cloud;
			this.glow = glow;
			this.sun = sun;
			this.stars = stars;
			this.rain = rain;
		}

		private static Vector3f v(float r, float g, float b) {
			return new Vector3f(r, g, b);
		}

		private static Vector4f v4(float r, float g, float b, float a) {
			return new Vector4f(r, g, b, a);
		}
	}

	/** The scene a keyword brings, if any. */
	static Scene sceneFor(WorldKeywords.Kind k) {
		return switch (k) {
			case NIGHT, STAR, GHOST -> Scene.NIGHT;
			case SUN, GOLD -> Scene.GOLDEN;
			case STORM, BOOM -> Scene.STORM;
			case RAIN, TEARS -> Scene.RAIN;
			case HELL, BLOOD, FIRE -> Scene.BLOOD;
			case SKY, HEART, FLOWER -> Scene.DREAM;
			case WATER -> Scene.OCEAN;
			case SNOW -> Scene.WINTER;
			default -> null;
		};
	}

	private static Scene scene;
	private static float strength, until, clock;
	private static long lastTick;

	static void trigger(Scene s, float seconds) {
		if (s != scene) {
			// cut straight from one look to the next, like an edit
			if (scene != null) strength = Math.min(strength, 0.35f);
			scene = s;
		}
		until = Math.max(until, clock + seconds);
	}

	/** Called every frame by the Edit style. */
	static void tick(float dt, boolean enabled) {
		lastTick = System.nanoTime();
		clock += dt;
		if (!enabled) {
			scene = null;
			strength = 0f;
			return;
		}
		boolean on = scene != null && clock < until;
		strength = Draw.approach(strength, on ? 1f : 0f, dt * (on ? 1.1f : 0.6f));
		if (!on && strength <= 0f) scene = null;
	}

	static void clear() {
		scene = null;
		strength = 0f;
		until = 0f;
	}

	private static boolean live() {
		return scene != null && strength > 0.001f && System.nanoTime() - lastTick < 300_000_000L;
	}

	/** How much fake rain to show (0..1), for the client world only. */
	public static float rain() {
		return live() ? scene.rain * strength : 0f;
	}

	/** Thunder for the storm look (darker sky, like a real thunderstorm). */
	public static float thunder() {
		return live() && scene == Scene.STORM ? strength : 0f;
	}

	/** Change one sky value (only the look of the sky, fog and clouds). */
	@SuppressWarnings("unchecked")
	public static <V> V apply(EnvironmentAttribute<V> attr, V value) {
		if (!live() || value == null || !Minecraft.getInstance().isSameThread()) return value;
		Scene s = scene;
		float k = strength * strength * (3 - 2 * strength); // smooth
		Object out = value;
		if (attr == EnvironmentAttributes.SKY_COLOR && value instanceof Vector3fc c) out = new Vector3f(c).lerp(s.sky, k);
		else if (attr == EnvironmentAttributes.FOG_COLOR && value instanceof Vector3fc c) out = new Vector3f(c).lerp(s.fog, k);
		else if (attr == EnvironmentAttributes.CLOUD_COLOR && value instanceof Vector4fc c) out = new Vector4f(c).lerp(s.cloud, k);
		else if (attr == EnvironmentAttributes.SUNRISE_SUNSET_COLOR && value instanceof Vector4fc c) out = new Vector4f(c).lerp(s.glow, k);
		else if (attr == EnvironmentAttributes.STAR_BRIGHTNESS && value instanceof Float f) out = f + (Math.max(f, s.stars) - f) * k;
		else if ((attr == EnvironmentAttributes.SUN_ANGLE || attr == EnvironmentAttributes.MOON_ANGLE || attr == EnvironmentAttributes.STAR_ANGLE)
				&& value instanceof Float f && !Float.isNaN(s.sun)) out = lerpAngle(f, s.sun, k);
		return (V) out;
	}

	/**
	 * For shader packs, which draw their own sky: the scene as a soft grade over the screen
	 * ({top argb, bottom argb}), or null when there's nothing to show.
	 */
	static int[] screenGrade() {
		if (!live()) return null;
		float k = strength * strength * (3 - 2 * strength);
		return new int[]{Draw.argb(rgb(scene.sky), 0.24f * k), Draw.argb(rgb(scene.fog), 0.07f * k)};
	}

	private static int rgb(Vector3f c) {
		return (Math.round(c.x * 255) << 16) | (Math.round(c.y * 255) << 8) | Math.round(c.z * 255);
	}

	private static float lerpAngle(float from, float to, float k) {
		float d = ((to - from) % 360f + 540f) % 360f - 180f;
		return from + d * k;
	}
}
