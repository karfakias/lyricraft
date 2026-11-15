package dev.lyricraft.hud;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.style.ClassicStyle;
import dev.lyricraft.hud.style.Frame;
import dev.lyricraft.hud.style.EchoesStyle;
import dev.lyricraft.hud.style.NeonStyle;
import dev.lyricraft.hud.style.WaveStyle;
import dev.lyricraft.overlay.Overlays;
import dev.lyricraft.vibe.VibeEngine;
import dev.lyricraft.vibe.VibeRenderer;
import dev.lyricraft.hud.style.LyricStyle;
import dev.lyricraft.hud.style.OrbitStyle;
import dev.lyricraft.hud.style.PopStyle;
import dev.lyricraft.hud.style.SkyStyle;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import dev.lyricraft.media.MediaSnapshot;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;

/** Picks the active lyrics style each frame and draws the shared bits (Now Playing card, toasts). */
public final class LyricsHud implements HudElement {

	private final LyricraftClient mod;
	private final Projector projector = new Projector();
	private final Map<Config.Style, LyricStyle> styles = new EnumMap<>(Config.Style.class);

	private final VibeRenderer vibeRenderer = new VibeRenderer();
	private final Overlays overlays;
	private static LyricsHud instance;
	private long lastPreview = System.nanoTime();

	/** Draws just the Now Playing card: used by the menu on the title screen, where the HUD doesn't run. */
	public static void previewCard(GuiGraphicsExtractor g) {
		LyricsHud h = instance;
		if (h == null || !h.mod.config.showNowPlaying) return;
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - h.lastPreview) / 1e9f);
		h.lastPreview = now;
		MediaSnapshot track = h.mod.tracker.current();
		Config cfg = h.mod.config;
		if (track == null) {
			h.card.render(g, Minecraft.getInstance().font, cfg, DEMO, null, (now / 1_000_000L) % DEMO.durationMs(), true,
					cfg.accentColor & 0xFFFFFF, h.mod.vibe.colorA(), 1f, dt);
		} else {
			h.card.render(g, Minecraft.getInstance().font, cfg, track, h.mod.lyrics(), h.mod.tracker.positionMs(),
					h.mod.tracker.isPlaying(), cfg.accentColor & 0xFFFFFF, h.mod.vibe.colorA(), 1f, dt);
		}
	}

	private final NowPlayingCard card = new NowPlayingCard();
	private final OverheadLyrics overhead = new OverheadLyrics();
	private static final MediaSnapshot DEMO = new MediaSnapshot("Spotify.exe", "Your song here", "Play something on Spotify",
			"", 0, 200_000, true, 0);
	private float visibility = 0f;
	/** The card fades on its own so it can stay visible in Lyricraft's menus even with lyrics switched off. */
	private float cardVisibility = 0f;
	/** True when on-screen lyrics were actually drawn last frame (the layout editor shows a sample otherwise). */
	public static volatile boolean lyricsOnScreen;
	/** Lyrics fade separately so they can disappear while the music is paused. */
	private float lyricsVisibility = 0f;
	/** 0..1 how much of the sky you can see; Sky Writing fades into Classic when it drops. */
	private float skyView = 1f;
	private long lastFrame = System.nanoTime();

	public LyricsHud(LyricraftClient mod) {
		this.mod = mod;
		instance = this;
		this.overlays = new Overlays(mod);
		styles.put(Config.Style.CLASSIC, new ClassicStyle());
		styles.put(Config.Style.SKY, new SkyStyle());
		styles.put(Config.Style.ORBIT, new OrbitStyle());
		styles.put(Config.Style.ECHOES, new EchoesStyle());
		styles.put(Config.Style.POP, new PopStyle());
		styles.put(Config.Style.NEON, new NeonStyle());
		styles.put(Config.Style.WAVE, new WaveStyle());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Config cfg = mod.config;
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		HudSpace.clear();

		String toast = mod.activeToast();
		MediaSnapshot track = mod.tracker.current();
		// our own settings screen keeps everything visible so you get a live preview
		boolean ownScreen = mc.gui.screen() instanceof dev.lyricraft.gui.LyricraftUi;
		// chat counts as playing, not as a menu: everything stays on screen while you type
		boolean chatOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen;
		boolean menuOpen = cfg.hideInMenus && mc.gui.screen() != null && !ownScreen && !chatOpen;
		boolean want = cfg.enabled && track != null && !menuOpen;
		boolean playing = mod.tracker.isPlaying();

		visibility = Draw.approach(visibility, want ? 1f : 0f, dt * 4f);
		// the card has its own switch (Card tab), so turning the lyrics off with J keeps it
		// the card stays up behind menus too (inventory, pause...), only the lyrics step aside
		boolean cardWant = track != null;
		cardVisibility = Draw.approach(cardVisibility, cardWant ? 1f : 0f, dt * 4f);
		boolean drewLyrics = false;
		lyricsVisibility = Draw.approach(lyricsVisibility, want && (playing || !cfg.hideWhenPaused) ? 1f : 0f, dt * 3.5f);
		VibeEngine vibe = mod.vibe;
		Lyrics lyrics = track != null ? mod.lyrics() : null;
		boolean synced = lyrics != null && lyrics.kind == Lyrics.Kind.SYNCED;
		float time = (now % 3_600_000_000_000L) / 1e9f;

		// keep lyrics clear of the hotbar and the toast area
		int gw = g.guiWidth(), gh = g.guiHeight();
		HudSpace.reserve(gw / 2f - 100, gh - 42, gw / 2f + 100, gh);
		HudSpace.reserve(gw / 2f - 90, 0, gw / 2f + 90, 22);

		// mood lighting goes underneath everything else
		vibeRenderer.render(g, vibe, cfg, want && synced && playing, dt, time);
		overlays.renderUnder(g, mc.font, cfg, want, dt, time);

		// nothing playing but the menu is open: show a demo card so you can still style and move it
		if (track == null && ownScreen && cfg.showNowPlaying) {
			card.render(g, mc.font, cfg, DEMO, null, (now / 1_000_000L) % DEMO.durationMs(), true,
					cfg.accentColor & 0xFFFFFF, vibe.colorA(), 1f, dt);
		}

		LyricStyle style = styles.getOrDefault(cfg.style, styles.get(Config.Style.CLASSIC));
		if (track != null && cardVisibility > 0.01f && cfg.showNowPlaying
				// in Lyricraft's menus the card always shows, so it can be styled and dragged
				// (the Vinyl/Cassette/VHS overlays replace it, but only while they're actually showing)
				&& (ownScreen || !want || style.showsNowPlayingCard() && !overlays.replacesNowPlaying(cfg))) {
			CoverArt.Cover cover = CoverArt.get(track.trackKey());
			int accent = cfg.accentFromCover && cover != null ? cover.colorA() : cfg.accentColor & 0xFFFFFF;
			card.render(g, mc.font, cfg, track, lyrics, mod.tracker.positionMs(), playing, accent, vibe.colorA(), cardVisibility, dt);
		}

		if (visibility > 0.01f && track != null) {
			long songPos = mod.tracker.positionMs();
			long pos = songPos - mod.currentOffsetMs();
			CoverArt.Cover cover = CoverArt.get(track.trackKey());
			int accent = cfg.accentFromCover && cover != null ? cover.colorA() : cfg.accentColor & 0xFFFFFF;

			if (synced && lyricsVisibility > 0.01f) {
				drewLyrics = lyricsVisibility > 0.3f && LyricsAnchor.movable(cfg.style);
				projector.update(mc.gameRenderer.mainCamera(), gw, gh);
				float pulse = cfg.vibe == Config.Vibe.OFF ? 0f : vibe.pulse(now);
				float lv = lyricsVisibility;

				if (cfg.style == Config.Style.SKY) {
					// Only write in the sky if you can actually see the sky
					skyView = Draw.approach(skyView, skyVisibility(mc), dt * 1.5f);
					if (skyView > 0.01f) style.render(new Frame(g, mc.font, lyrics, pos, now, dt, lv * skyView, cfg,
							projector, accent, track, songPos, pulse, vibe.colorA(), vibe.colorB()));
					if (skyView < 0.99f) styles.get(Config.Style.CLASSIC).render(new Frame(g, mc.font, lyrics, pos, now, dt,
							lv * (1f - skyView), cfg, projector, accent, track, songPos, pulse, vibe.colorA(), vibe.colorB()));
				} else {
					style.render(new Frame(g, mc.font, lyrics, pos, now, dt, lv, cfg, projector, accent,
							track, songPos, pulse, vibe.colorA(), vibe.colorB()));
				}
			}
		}
		lyricsOnScreen = drewLyrics;
		// other Lyricraft players' current line above their heads
		if (!menuOpen && mc.level != null) {
			projector.update(mc.gameRenderer.mainCamera(), gw, gh);
			overhead.render(g, mc.font, mc, projector, cfg, mod.online, delta.getGameTimeDeltaPartialTick(true),
					cfg.accentColor & 0xFFFFFF);
		}
		overlays.renderOver(g, mc.font, cfg, time);
		if (toast != null && !menuOpen) drawToast(g, mc.font, toast);
	}

	/**
	 * 1 when you're under open sky, 0 in tunnels, caves, buildings, the Nether and the End.
	 * Uses the sky light level at your eyes, so it fades smoothly at cave entrances.
	 */
	private static float skyVisibility(Minecraft mc) {
		if (mc.level == null || mc.player == null) return 0f;
		var dim = mc.level.dimensionType();
		if (!dim.hasSkyLight() || dim.hasCeiling()) return 0f;
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		BlockPos pos = BlockPos.containing(eye);
		int skyLight = mc.level.getBrightness(LightLayer.SKY, pos);
		float fromLight = Math.clamp((skyLight - 9) / 5f, 0f, 1f);
		// light can leak a few blocks into a cave mouth, so also require open sky straight above
		boolean open = mc.level.canSeeSky(pos) || mc.level.canSeeSky(pos.above());
		return open ? fromLight : fromLight * 0.25f;
	}

	private void drawToast(GuiGraphicsExtractor g, Font font, String text) {
		int accent = mod.config.accentColor & 0xFFFFFF;
		int w = font.width(text) + 12;
		int x = g.guiWidth() / 2 - w / 2, y = 6;
		g.fill(x, y, x + w, y + 14, Draw.argb(0x101010, 0.8f));
		g.fill(x, y + 13, x + w, y + 14, Draw.argb(accent, 1f));
		g.text(font, text, x + 6, y + 3, 0xFFFFFFFF, true);
	}
}
