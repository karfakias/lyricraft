package dev.lyricraft.online;

import dev.lyricraft.LyricraftClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.UUID;

/** The little ♫ after the name of players who use Lyricraft (tab list and name tags). */
public final class Badges {
	private Badges() {}

	/** Badge symbol and colour (Spotify green). */
	public static final String SYMBOL = "♫";
	public static final int COLOR = 0x1ED760;

	/** {@code name} with the badge added if this player uses Lyricraft, otherwise unchanged. */
	public static Component decorate(Component name, UUID player) {
		LyricraftClient mod = LyricraftClient.get();
		if (name == null || mod == null || !mod.config.showBadges || !mod.online.usesMod(player)) return name;
		MutableComponent out = name.copy();
		out.append(Component.literal(" " + SYMBOL).withColor(COLOR));
		return out;
	}
}
