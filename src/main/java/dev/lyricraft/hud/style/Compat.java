package dev.lyricraft.hud.style;

import dev.lyricraft.LyricraftClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/**
 * Other mods the Edit style has to get along with. Iris with a shader pack draws its own sky, so
 * the sky colour changes barely show there; when that's the case the scene is laid over the
 * screen as a soft colour grade instead. Looked up by reflection, so Iris is never needed.
 */
public final class Compat {
	private Compat() {}

	private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
	private static Object irisApi;
	private static Method inUse;
	private static boolean broken;
	private static long checkedAt;
	private static boolean shaders;

	/** True while an Iris shader pack is on (checked a few times a second, not every frame). */
	public static boolean shaders() {
		if (!IRIS || broken) return false;
		long now = System.nanoTime();
		if (now - checkedAt < 250_000_000L) return shaders;
		checkedAt = now;
		try {
			if (inUse == null) {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisApi = api.getMethod("getInstance").invoke(null);
				inUse = api.getMethod("isShaderPackInUse");
			}
			shaders = (Boolean) inUse.invoke(irisApi);
		} catch (Throwable t) {
			LyricraftClient.LOG.info("Lyricraft: couldn't ask Iris about shaders ({}), assuming none", t.toString());
			broken = true;
			shaders = false;
		}
		return shaders;
	}
}
