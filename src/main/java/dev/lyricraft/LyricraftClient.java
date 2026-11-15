package dev.lyricraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lyricraft.gui.LyricraftScreen;
import dev.lyricraft.hud.LyricsHud;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.lyrics.LyricsFetcher;
import dev.lyricraft.media.MediaSnapshot;
import dev.lyricraft.media.PlaybackTracker;
import dev.lyricraft.sync.SyncMemory;
import dev.lyricraft.vibe.VibeEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LyricraftClient implements ClientModInitializer {

	public static final String MOD_ID = "lyricraft";
	public static final Logger LOG = LoggerFactory.getLogger("Lyricraft");
	public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

	private static LyricraftClient instance;

	public final Config config = Config.load();
	public final PlaybackTracker tracker = new PlaybackTracker();
	public final SyncMemory sync = new SyncMemory(config);
	public final VibeEngine vibe = new VibeEngine();
	public final dev.lyricraft.online.OnlineClient online = new dev.lyricraft.online.OnlineClient(this);
	private final LyricsFetcher fetcher = new LyricsFetcher(config);

	/** Lyrics for the current track; {@code null} while loading. */
	private volatile Lyrics lyrics;
	private volatile String lyricsFor = "";

	/** Short message shown at the top of the screen (e.g. after changing the style). */
	private volatile String toast;
	private volatile long toastUntilNanos;

	private KeyMapping toggleKey, styleKey, vibeKey, overlayKey, menuKey, earlierKey, laterKey, moveKey;

	public static LyricraftClient get() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		instance = this;

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		toggleKey = register("key.lyricraft.toggle", InputConstants.KEY_J, category);
		styleKey = register("key.lyricraft.style", InputConstants.KEY_K, category);
		vibeKey = register("key.lyricraft.vibe", InputConstants.KEY_V, category);
		overlayKey = register("key.lyricraft.overlay", InputConstants.KEY_H, category);
		menuKey = register("key.lyricraft.menu", InputConstants.KEY_N, category);
		earlierKey = register("key.lyricraft.offset_down", InputConstants.KEY_LBRACKET, category);
		laterKey = register("key.lyricraft.offset_up", InputConstants.KEY_RBRACKET, category);
		moveKey = register("key.lyricraft.cycle_position", InputConstants.UNKNOWN.getValue(), category);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handleKeys();
			online.tick(client);
			tracker.setAutoSync(config.autoSync);
			MediaSnapshot now = tracker.current();
			vibe.tick(client, config, now, lyrics(), tracker.positionMs() - currentOffsetMs(),
					config.enabled && tracker.isPlaying());
		});

		tracker.onTrackChange(this::loadLyrics);
		tracker.start();

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "lyrics"), new LyricsHud(this));

		LOG.info("Lyricraft {} ready - play something on Spotify!", VERSION);
	}

	private static KeyMapping register(String name, int key, KeyMapping.Category category) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYBOARD, key, category));
	}

	/** Look the current song's lyrics up again (after changing lyric settings). */
	public void reloadLyrics() {
		fetcher.clearCache();
		MediaSnapshot now = tracker.current();
		if (now != null) loadLyrics(now);
	}

	private void loadLyrics(MediaSnapshot track) {
		String key = track.trackKey();
		lyricsFor = key;
		lyrics = null;
		fetcher.fetch(track).thenAccept(result -> {
			LOG.debug("Lyricraft: now playing '{}' by '{}' ({}), lyrics: {}", track.title(), track.artist(), track.app(), result.kind);
			if (key.equals(lyricsFor)) lyrics = result;
		});
	}

	private void handleKeys() {
		while (menuKey.consumeClick()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.gui.screen() == null) mc.gui.setScreen(new LyricraftScreen(this, null));
		}
		while (toggleKey.consumeClick()) {
			config.enabled = !config.enabled;
			config.save();
			showToast(config.enabled ? "Lyrics on" : "Lyrics off");
		}
		while (styleKey.consumeClick()) {
			Config.Style[] all = Config.Style.values();
			config.style = all[(config.style.ordinal() + 1) % all.length];
			config.enabled = true;
			config.save();
			showToast("Style: " + styleName(config.style));
		}
		while (vibeKey.consumeClick()) {
			Config.Vibe[] all = Config.Vibe.values();
			config.vibe = all[(config.vibe.ordinal() + 1) % all.length];
			config.save();
			showToast("Vibe: " + switch (config.vibe) {
				case OFF -> "off";
				case SUBTLE -> "subtle";
				case FULL -> "FULL ✨";
			});
		}
		while (overlayKey.consumeClick()) {
			Config.Overlay[] all = Config.Overlay.values();
			config.overlay = all[(config.overlay.ordinal() + 1) % all.length];
			config.save();
			showToast("Overlay: " + overlayName(config.overlay));
		}
		while (earlierKey.consumeClick()) changeOffset(-100);
		while (laterKey.consumeClick()) changeOffset(100);
		while (moveKey.consumeClick()) {
			Config.Position[] all = Config.Position.values();
			config.position = all[(config.position.ordinal() + 1) % all.length];
			config.save();
			showToast("Position: " + config.position.name().toLowerCase().replace('_', ' '));
		}
	}

	public static String overlayName(Config.Overlay overlay) {
		return switch (overlay) {
			case NONE -> "None";
			case VINYL -> "Vinyl";
			case VHS -> "VHS";
			case SYNTHWAVE -> "Synthwave";
			case VISUALIZER -> "Visualizer";
			case CASSETTE -> "Cassette";
			case RAIN -> "Lo-fi Rain";
			case STARS -> "Starry Night";
			case OSCILLOSCOPE -> "Oscilloscope";
			case CONFETTI -> "Confetti";
		};
	}

	public static String styleName(Config.Style style) {
		return switch (style) {
			case CLASSIC -> "Classic";
			case SKY -> "Sky Writing";
			case ORBIT -> "Orbit";
			case ECHOES -> "Echoes";
			case POP -> "Pop";
			case NEON -> "Neon";
			case WAVE -> "Wave";
		};
	}

	/** Timing for the song that's playing: remembered for this song, or your learned default. */
	public int currentOffsetMs() {
		MediaSnapshot now = tracker.current();
		return now == null ? sync.defaultOffset() : sync.offsetFor(now.trackKey());
	}

	private void changeOffset(int delta) {
		MediaSnapshot now = tracker.current();
		if (now == null) {
			showToast("Play a song first");
			return;
		}
		int ms = sync.nudge(now.trackKey(), delta);
		int def = sync.defaultOffset();
		showToast("Lyrics " + (ms > 0 ? "+" : "") + ms + " ms · remembered (default now "
				+ (def > 0 ? "+" : "") + def + (dev.lyricraft.media.AudioOutput.bluetooth() ? " for Bluetooth)" : ")"));
	}

	public void showToast(String message) {
		toast = message;
		toastUntilNanos = System.nanoTime() + 1_800_000_000L;
	}

	public String activeToast() {
		return System.nanoTime() < toastUntilNanos ? toast : null;
	}

	/** Lyrics for the current track, or {@code null} while they are still loading. */
	public Lyrics lyrics() {
		MediaSnapshot now = tracker.current();
		if (now == null || !now.trackKey().equals(lyricsFor)) return null;
		return lyrics;
	}
}
