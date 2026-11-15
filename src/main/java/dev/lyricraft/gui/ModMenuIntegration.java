package dev.lyricraft.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.lyricraft.LyricraftClient;

/** Adds the "Config" button for Lyricraft in Mod Menu. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new LyricraftScreen(LyricraftClient.get(), parent);
	}
}
