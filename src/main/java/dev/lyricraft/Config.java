package dev.lyricraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved in config/lyricraft.json. */
public final class Config {

	public enum Position { ABOVE_HOTBAR, TOP_CENTER, LEFT_SIDE, RIGHT_SIDE, CUSTOM }

	/** How the lyrics are shown. */
	public enum Style { CLASSIC, ECHOES, EDIT, SKY, ORBIT, POP, NEON, WAVE }

	/** Screen + world effects that react to the song. */
	public enum Vibe { OFF, SUBTLE, FULL }

	/** Edit style camera angles: none, only on the chorus, or a cut on every line and every chorus word. */
	public enum Cinematic { OFF, CALM, FULL }

	/** Decorative overlay. */
	public enum Overlay { NONE, VINYL, CASSETTE, VHS, SYNTHWAVE, VISUALIZER, RAIN, STARS, OSCILLOSCOPE, CONFETTI }

	public boolean enabled = true;
	public Style style = Style.CLASSIC;
	public Vibe vibe = Vibe.SUBTLE;
	public Overlay overlay = Overlay.NONE;

	/** How the Now Playing card looks. */
	public enum CardStyle { CARD, COMPACT, PILL, COVER, MINIMAL }

	/** Colour theme of the Now Playing card. */
	public enum CardTheme { GLASS, DARK, LIGHT, SPOTIFY, SONG, NEON, TOOLTIP }

	public CardStyle cardStyle = CardStyle.CARD;
	public CardTheme cardTheme = CardTheme.GLASS;
	/** Centre of the lyrics on screen (0..1) when {@link #position} is CUSTOM (set by dragging). */
	public float lyricsX = 0.5f, lyricsY = 0.84f;
	/** 0.5..1.5 size of the Now Playing card. */
	public float cardScale = 1.0f;
	/** 0..1 how solid the card's background is. */
	public float cardOpacity = 0.8f;
	/** Where the card sits: 0..1 across / down the free space on screen (0,0 = top-left). Drag it in the menu. */
	public float cardX = 0f, cardY = 0f;
	public boolean cardCover = true;
	public boolean cardProgress = true;
	public boolean cardDetails = true;
	public boolean cardVinyl = true;
	/** Spawn music particles around the player in Vibe mode. */
	public boolean vibeParticles = true;
	/**
	 * Learned default timing (positive = lyrics appear later). Updated automatically from your
	 * per-song nudges, so e.g. Bluetooth headphone delay gets corrected for every song.
	 */
	public int offsetMs = 0;
	/**
	 * Same, but for when the sound goes to Bluetooth headphones (Windows detects that). They play
	 * audio ~150-250 ms late, so this starts at +200 and is learned separately from offsetMs.
	 */
	public int offsetBluetoothMs = 200;
	/** Combine every synced version of a song from LRCLIB into one better-timed version. */
	public boolean combineVersions = true;
	/** Also ask NetEase Cloud Music for lyrics (an extra opinion on the timing). Off by default. */
	public boolean extraLyricsSource = false;

	// --- online: see other Lyricraft players
	/** Connect to the Lyricraft online service (badges and other players' lyrics). */
	public boolean online = true;
	/** Share the song and line you're hearing with players near you. */
	public boolean shareLyrics = true;
	/** Show the ♫ badge next to players who use Lyricraft. */
	public boolean showBadges = true;
	/** Show other players' current line above their head. */
	public boolean showOthersLyrics = true;
	/** Let nearby Lyricraft players see your Echoes panels in the world (needs Share what I'm listening to). */
	public boolean shareEchoes = true;
	/** Show other players' Echoes panels where they placed them. */
	public boolean showOthersEchoes = true;
	/** Edit style: popular songs hit harder and get their own particles. */
	public boolean editEffects = true;
	/** Edit style: zoom punches and camera shake on the hits. */
	public boolean editCamera = true;
	/** Old on/off switch for the angles (before the Cinematic setting); only read to carry it over. */
	public boolean editAngles = true;
	/** Edit style: cinematic angles around you while you stand still. Null = not picked yet (from editAngles). */
	public Cinematic editCinematic = null;
	/** Edit style: keywords change the sky (night, sunset, storm...), on your screen only. */
	public boolean editScenes = true;
	/** The Cinematic setting (Full unless angles were switched off before it existed). */
	public Cinematic cinematic() {
		return editCinematic != null ? editCinematic : editAngles ? Cinematic.FULL : Cinematic.OFF;
	}

	/** Address of the online service (your own Cloudflare Worker, see server/README.md). */
	public String onlineServer = "https://lyricraft.lyricraft-server.workers.dev";
	/** Keep the playback clock locked to Spotify by measuring and correcting drift. */
	public boolean autoSync = true;
	public Position position = Position.ABOVE_HOTBAR;
	public float scale = 1.0f;
	public boolean showNowPlaying = true;
	public boolean showContextLines = true;
	public boolean hideInMenus = true;
	/** Karaoke fill colour (ARGB). Default: Spotify green. */
	public int accentColor = 0xFF1ED760;
	/** Use the album cover's main colour for the karaoke fill instead of {@link #accentColor}. */
	public boolean accentFromCover = false;
	/** Take the vibe lighting colours from the album cover. */
	public boolean coverColors = true;
	/** Fade the lyrics out while the music is paused. */
	public boolean hideWhenPaused = true;

	// --- screen effects (each one can be switched off on its own)
	/** 0.1..1: how strong all the screen effects are. */
	public float vibeStrength = 0.7f;
	/** Tint / dim the whole screen in the song's colours (Full vibe only). */
	public boolean vibeTint = true;
	/** Coloured glow around the screen edges that breathes with the words. */
	public boolean vibeEdgeGlow = true;
	/** Aurora ribbons across the top of the screen. */
	public boolean vibeAurora = true;
	/** Equalizer bars beside the hotbar (Full vibe only). */
	public boolean vibeEqualizer = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("lyricraft.json");
	}

	public static Config load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Config c = GSON.fromJson(r, Config.class);
				if (c != null) {
					if (c.position == null) c.position = Position.ABOVE_HOTBAR;
					if (c.style == null) c.style = Style.CLASSIC;
					if (c.vibe == null) c.vibe = Vibe.SUBTLE;
					if (c.overlay == null) c.overlay = Overlay.NONE;
					if (c.cardStyle == null) c.cardStyle = CardStyle.CARD;
					if (c.cardTheme == null) c.cardTheme = CardTheme.GLASS;
					c.lyricsX = Math.clamp(c.lyricsX, 0.1f, 0.9f);
					c.lyricsY = Math.clamp(c.lyricsY, 0.05f, 0.95f);
					c.cardScale = Math.clamp(c.cardScale, 0.5f, 1.5f);
					c.cardOpacity = Math.clamp(c.cardOpacity, 0f, 1f);
					c.cardX = Math.clamp(c.cardX, 0f, 1f);
					c.cardY = Math.clamp(c.cardY, 0f, 1f);
					c.scale = Math.clamp(c.scale, 0.5f, 3f);
					return c;
				}
			} catch (Exception e) {
				LyricraftClient.LOG.warn("Lyricraft: couldn't read config, using defaults: {}", e.toString());
			}
		}
		Config c = new Config();
		c.save();
		return c;
	}

	public void save() {
		try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
			GSON.toJson(this, w);
		} catch (Exception e) {
			LyricraftClient.LOG.warn("Lyricraft: couldn't save config: {}", e.toString());
		}
	}
}
