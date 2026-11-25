package dev.lyricraft.gui;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.LyricsAnchor;
import dev.lyricraft.hud.LyricsHud;
import dev.lyricraft.hud.NowPlayingCard;
import dev.lyricraft.media.MediaSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Lyricraft's settings: a side panel with a tab rail. The game keeps running next to it, so every
 * change shows up live, and the card and lyrics can be dragged right there on the game side.
 * The panel sits on the opposite side of the screen from the card so it never hides it.
 * Opens with N or from Mod Menu.
 */
public final class LyricraftScreen extends Screen implements LyricraftUi {

	private enum Tab { STYLE, CARD, LYRICS, VIBE, OVERLAY, SYNC, ONLINE }

	private static Tab lastTab = Tab.STYLE;
	private static int lastScroll;

	private final LyricraftClient mod;
	private final Screen parent;
	private final LayoutDrag drag;
	private Tab tab = lastTab;
	private int px, panelW, railW = 66, cx0, cx1, top, bottom;
	private boolean dockRight;
	private int scroll = lastScroll, contentH;
	private final List<AbstractWidget> content = new ArrayList<>();
	private final List<Integer> contentBaseY = new ArrayList<>();
	private int infoBaseY;
	private String info = "";

	private static final int[] COLOURS = {0x1ED760, 0xFF5FA2, 0x3BE8FF, 0xB46CFF, 0xFFC93B, 0xFF6B3B, 0xFFFFFF};
	private static final String[] COLOUR_NAMES = {"Spotify green", "Pink", "Cyan", "Purple", "Gold", "Sunset", "White"};

	public LyricraftScreen(LyricraftClient mod, Screen parent) {
		super(Component.literal("Lyricraft"));
		this.mod = mod;
		this.parent = parent;
		this.drag = new LayoutDrag(mod.config);
	}

	private Config cfg() {
		return mod.config;
	}

	private int accent() {
		return cfg().accentFromCover ? mod.vibe.colorA() : cfg().accentColor & 0xFFFFFF;
	}

	@Override
	protected void init() {
		Widgets.accent = this::accent;
		panelW = width < 320 ? width : Math.clamp(width / 2, 270, 340);
		// keep the panel away from the card so you can see (and drag) it
		float[] card = NowPlayingCard.place(cfg(), width, height);
		dockRight = panelW < width && card[0] + card[2] / 2f < width / 2f;
		px = dockRight ? width - panelW : 0;
		top = 56;
		bottom = height - 34;
		cx0 = px + railW + 6;
		cx1 = px + panelW - 8;
		content.clear();
		contentBaseY.clear();
		labels.clear();
		labelText.clear();

		// tab rail
		String[] icons = {"✦", "▣", "♫", "☀", "◈", "↻", "◉"};
		String[] names = {"Style", "Card", "Lyrics", "Vibe", "Overlay", "Sync", "Online"};
		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			Tab t = tabs[i];
			addRenderableWidget(new Widgets.Tab(px, top + i * 22, railW, 20, icons[i], names[i], () -> tab == t, () -> {
				if (tab != t) {
					tab = t;
					lastTab = t;
					scroll = 0;
					rebuildWidgets();
				}
			}));
		}

		int y = top;
		switch (tab) {
			case STYLE -> y = styleTab(y);
			case CARD -> y = cardTab(y);
			case LYRICS -> y = lyricsTab(y);
			case VIBE -> y = vibeTab(y);
			case OVERLAY -> y = overlayTab(y);
			case SYNC -> y = syncTab(y);
			case ONLINE -> y = onlineTab(y);
		}
		infoBaseY = y + 4;
		String longest = info.isEmpty() ? longestInfo() : info;
		int infoLines = longest.isEmpty() ? 0 : font.split(Component.literal(longest), cx1 - cx0).size();
		contentH = infoBaseY + infoLines * 9 - top + 6;
		scroll = Math.clamp(scroll, 0, Math.max(0, contentH - (bottom - top)));
		lastScroll = scroll;
		applyScroll();

		addRenderableWidget(new Widgets.Action(px + 8, height - 27, panelW - 16, 20, "Done", true, this::onClose, null));
	}

	/** Longest description a hovered tile can show, so the scroll range always fits it. */
	private String longestInfo() {
		String best = "";
		List<String> all = new ArrayList<>();
		if (tab == Tab.STYLE) for (Config.Style s : Config.Style.values()) all.add(styleInfo(s));
		if (tab == Tab.OVERLAY) for (Config.Overlay o : Config.Overlay.values()) all.add(overlayInfo(o) + "  Key: H");
		if (tab == Tab.CARD) {
			for (Config.CardStyle s : Config.CardStyle.values()) all.add(cardInfo(s));
			for (Config.CardTheme t : Config.CardTheme.values()) all.add(themeInfo(t));
		}
		for (String s : all) if (s.length() > best.length()) best = s;
		return best;
	}

	private <T extends AbstractWidget> T add(T w) {
		content.add(w);
		contentBaseY.add(w.getY());
		return addRenderableWidget(w);
	}

	private void applyScroll() {
		for (int i = 0; i < content.size(); i++) {
			AbstractWidget w = content.get(i);
			int y = contentBaseY.get(i) - scroll;
			w.setY(y);
			w.visible = y >= top - 1 && y + w.getHeight() <= bottom + 1;
		}
	}

	// ------------------------------------------------------------------ tabs

	private int styleTab(int y) {
		int cw = cx1 - cx0, half = (cw - 4) / 2;
		// Classic first (the default), then Echoes in its own "recommended" box, then the rest
		add(styleChip(Config.Style.CLASSIC, cx0, y, cw));
		y += 24;
		add(new Widgets.Featured(cx0, y, cw, 32, LyricraftClient.styleName(Config.Style.ECHOES),
				"Lyrics stay where you looked",
				() -> cfg().style == Config.Style.ECHOES, () -> pickStyle(Config.Style.ECHOES), styleInfo(Config.Style.ECHOES)));
		y += 38;
		int i = 0;
		for (Config.Style s : Config.Style.values()) {
			if (s == Config.Style.CLASSIC || s == Config.Style.ECHOES) continue;
			add(styleChip(s, cx0 + (i % 2) * (half + 4), y + (i / 2) * 22, half));
			i++;
		}
		y += ((i + 1) / 2) * 22 + 4;
		toggle(cx0, y, cw, "Edit: camera moves", () -> cfg().editCamera, v -> cfg().editCamera = v,
				"In the Edit style, the camera zoom-punches and shakes a little on the beat. Turn it off if it makes you dizzy.");
		y += 22;
		toggle(cx0, y, cw, "Edit: cinematic angles", () -> cfg().editAngles, v -> cfg().editAngles = v,
				"In the Edit style, the view cuts to angles around you on the chorus (over the shoulder, side, wide, from above). Only while you stand still; move and it cuts straight back.");
		y += 22;
		toggle(cx0, y, cw, "Edit: popular songs hit harder", () -> cfg().editEffects, v -> cfg().editEffects = v,
				"In the Edit style, " + dev.lyricraft.hud.style.EditSongs.size() + " popular songs get stronger hits and particles that fit them: hearts, rain, sparks, soul flames, notes.");
		info = "";
		return y + 22;
	}

	private Widgets.Chip styleChip(Config.Style s, int x, int y, int w) {
		return new Widgets.Chip(x, y, w, 20, LyricraftClient.styleName(s), () -> cfg().style == s, () -> pickStyle(s), styleInfo(s));
	}

	private void pickStyle(Config.Style s) {
		cfg().style = s;
		cfg().enabled = true;
		cfg().save();
	}

	private int cardTab(int y) {
		int cw = cx1 - cx0, half = (cw - 4) / 2;
		toggle(cx0, y, cw, "Show the card", () -> cfg().showNowPlaying, v -> cfg().showNowPlaying = v, "The Now Playing card with the song, artist and cover.");
		y += 24;
		label(y, "Look");
		y += 10;
		Config.CardStyle[] looks = Config.CardStyle.values();
		for (int i = 0; i < looks.length; i++) {
			Config.CardStyle s = looks[i];
			add(new Widgets.Chip(cx0 + (i % 2) * (half + 4), y + (i / 2) * 22, half, 20, cardName(s),
					() -> cfg().cardStyle == s, () -> {
				cfg().cardStyle = s;
				cfg().showNowPlaying = true;
				cfg().save();
			}, cardInfo(s)));
		}
		y += ((looks.length + 1) / 2) * 22 + 2;
		label(y, "Theme");
		y += 10;
		Config.CardTheme[] themes = Config.CardTheme.values();
		for (int i = 0; i < themes.length; i++) {
			Config.CardTheme t = themes[i];
			add(new Widgets.Chip(cx0 + (i % 2) * (half + 4), y + (i / 2) * 22, half, 20, themeName(t),
					() -> cfg().cardTheme == t, () -> {
				cfg().cardTheme = t;
				cfg().showNowPlaying = true;
				cfg().save();
			}, themeInfo(t)));
		}
		y += ((themes.length + 1) / 2) * 22 + 4;
		add(new Widgets.Slider(cx0, y, cw, "Size", (cfg().cardScale - 0.5) / 1.0,
				v -> Math.round((0.5 + v) * 100) + "%", v -> cfg().cardScale = (float) (0.5 + v), "Tip: you can also scroll over the card."));
		y += 22;
		add(new Widgets.Slider(cx0, y, cw, "Background", cfg().cardOpacity,
				v -> Math.round(v * 100) + "%", v -> cfg().cardOpacity = v.floatValue(), "0% = just the text and cover, no box"));
		y += 22;
		toggle(cx0, y, half, "Cover", () -> cfg().cardCover, v -> cfg().cardCover = v, "Show the album cover.");
		toggle(cx0 + half + 4, y, half, "Vinyl", () -> cfg().cardVinyl, v -> cfg().cardVinyl = v, "A record slides out from behind the cover while it plays (Card look).");
		y += 22;
		toggle(cx0, y, half, "Progress", () -> cfg().cardProgress, v -> cfg().cardProgress = v, "How far into the song you are.");
		toggle(cx0 + half + 4, y, half, "Details", () -> cfg().cardDetails, v -> cfg().cardDetails = v, "Where it's playing from, lyrics status and times.");
		y += 22;
		add(new Widgets.Action(cx0, y, cw, 20, "✥  Edit layout (full screen)", true,
				() -> minecraft.gui.setScreen(new LayoutScreen(mod, this)), "Drag the card and the lyrics with nothing in the way."));
		y += 22;
		add(new Widgets.Action(cx0, y, cw, 20, "Reset card", false, () -> {
			cfg().cardStyle = Config.CardStyle.CARD;
			cfg().cardTheme = Config.CardTheme.GLASS;
			cfg().cardScale = 1f;
			cfg().cardOpacity = 0.8f;
			cfg().cardX = cfg().cardY = 0f;
			cfg().cardCover = cfg().cardVinyl = cfg().cardProgress = cfg().cardDetails = true;
			cfg().save();
			rebuildWidgets();
		}, "Back to the default look in the top-left corner."));
		y += 22;
		info = "";
		return y;
	}

	private int lyricsTab(int y) {
		int cw = cx1 - cx0;
		toggle(cx0, y, cw, "Lyrics", () -> cfg().enabled, v -> cfg().enabled = v, "Show lyrics at all. The Now Playing card stays (it has its own switch in the Card tab). Key: J");
		y += 22;
		toggle(cx0, y, cw, "Hide when paused", () -> cfg().hideWhenPaused, v -> cfg().hideWhenPaused = v,
				"Lyrics fade out while the music is paused and come back when it plays.");
		y += 22;
		toggle(cx0, y, cw, "Show next line", () -> cfg().showContextLines, v -> cfg().showContextLines = v,
				"Show the upcoming line under the current one.");
		y += 22;
		toggle(cx0, y, cw, "Hide in menus", () -> cfg().hideInMenus, v -> cfg().hideInMenus = v,
				"Hide the lyrics while the inventory or pause menu is open. The Now Playing card stays, and chat never hides anything.");
		y += 22;
		add(new Widgets.Slider(cx0, y, cw, "Text size", (cfg().scale - 0.5) / 2.5,
				v -> Math.round((0.5 + v * 2.5) * 100) + "%", v -> cfg().scale = (float) (0.5 + v * 2.5), "Tip: you can also scroll over the lyrics."));
		y += 22;
		add(new Widgets.Choice(cx0, y, cw, "Colour", this::colourName, this::colourSwatch, d -> {
			int n = COLOURS.length + 1;
			int cur = cfg().accentFromCover ? COLOURS.length : Math.max(0, indexOfColour(cfg().accentColor & 0xFFFFFF));
			int next = Math.floorMod(cur + d, n);
			if (next == COLOURS.length) cfg().accentFromCover = true;
			else {
				cfg().accentFromCover = false;
				cfg().accentColor = 0xFF000000 | COLOURS[next];
			}
			cfg().save();
		}, "The karaoke fill colour. \"Album cover\" uses each song's cover colour."));
		y += 22;
		Config.Position[] all = Config.Position.values();
		add(new Widgets.Choice(cx0, y, cw, "Position", () -> positionName(cfg().position), () -> 0xFFFFFF, d -> {
			cfg().position = all[Math.floorMod(cfg().position.ordinal() + d, all.length)];
			cfg().save();
		}, "Where Classic, Pop, Neon and Wave sit. Or just drag the lyrics on screen."));
		y += 22;
		add(new Widgets.Action(cx0, y, cw, 20, "✥  Edit layout (full screen)", true,
				() -> minecraft.gui.setScreen(new LayoutScreen(mod, this)), "Drag the card and the lyrics with nothing in the way."));
		y += 22;
		info = "Tip: J turns lyrics on and off, K switches style, [ and ] fix the timing.";
		return y;
	}

	private int vibeTab(int y) {
		int cw = cx1 - cx0;
		Config.Vibe[] modes = Config.Vibe.values();
		add(new Widgets.Choice(cx0, y, cw, "Vibe", () -> switch (cfg().vibe) {
			case OFF -> "Off";
			case SUBTLE -> "Subtle";
			case FULL -> "Full";
		}, () -> cfg().vibe == Config.Vibe.OFF ? 0x8A8A96 : 0xD08CFF, d -> {
			cfg().vibe = modes[Math.floorMod(cfg().vibe.ordinal() + d, modes.length)];
			cfg().save();
		}, "Off: clean screen. Subtle: light glow on the words. Full: club lighting. Key: V"));
		y += 22;
		add(new Widgets.Slider(cx0, y, cw, "Strength", (cfg().vibeStrength - 0.1) / 0.9,
				v -> Math.round((0.1 + v * 0.9) * 100) + "%", v -> cfg().vibeStrength = (float) (0.1 + v * 0.9), null));
		y += 22;
		toggle(cx0, y, cw, "Edge glow", () -> cfg().vibeEdgeGlow, v -> cfg().vibeEdgeGlow = v, "Coloured glow around the screen edges that flashes with the words.");
		y += 22;
		toggle(cx0, y, cw, "Aurora", () -> cfg().vibeAurora, v -> cfg().vibeAurora = v, "Northern-lights ribbons across the top.");
		y += 22;
		toggle(cx0, y, cw, "Screen tint (Full)", () -> cfg().vibeTint, v -> cfg().vibeTint = v, "Washes the whole screen in the song's colours.");
		y += 22;
		toggle(cx0, y, cw, "Equalizer (Full)", () -> cfg().vibeEqualizer, v -> cfg().vibeEqualizer = v, "Bouncing bars next to the hotbar.");
		y += 22;
		toggle(cx0, y, cw, "Particles", () -> cfg().vibeParticles, v -> cfg().vibeParticles = v, "Music notes and sparkles around you on the beat.");
		y += 22;
		toggle(cx0, y, cw, "Colours from album cover", () -> cfg().coverColors, v -> cfg().coverColors = v, "Lighting uses the cover's colours instead of a random palette.");
		y += 22;
		info = "Effects only run while a song with synced lyrics is playing.";
		return y;
	}

	private int overlayTab(int y) {
		Config.Overlay[] all = Config.Overlay.values();
		int cw = cx1 - cx0, half = (cw - 4) / 2;
		for (int i = 0; i < all.length; i++) {
			Config.Overlay o = all[i];
			add(new Widgets.Chip(cx0 + (i % 2) * (half + 4), y + (i / 2) * 22, half, 20, LyricraftClient.overlayName(o),
					() -> cfg().overlay == o, () -> {
				cfg().overlay = o;
				cfg().save();
			}, overlayInfo(o)));
		}
		info = "";
		return y + ((all.length + 1) / 2) * 22;
	}

	private int syncNumbersY;

	private int syncTab(int y) {
		int cw = cx1 - cx0, half = (cw - 4) / 2;
		toggle(cx0, y, cw, "Auto-sync", () -> cfg().autoSync, v -> cfg().autoSync = v,
				"Follows Spotify's own position reports and keeps the lyrics clock locked to them.");
		y += 22;
		add(new Widgets.Action(cx0, y, half, 20, "« Earlier", false, () -> nudge(-100), "Lyrics 0.1 s sooner. Key: ["));
		add(new Widgets.Action(cx0 + half + 4, y, half, 20, "Later »", false, () -> nudge(100), "Lyrics 0.1 s later. Key: ]"));
		y += 22;
		add(new Widgets.Action(cx0, y, cw, 20, "Reset this song", false, () -> {
			MediaSnapshot now = mod.tracker.current();
			if (now != null) mod.sync.reset(now.trackKey());
		}, "Forget the timing you set for this song."));
		y += 22;
		toggle(cx0, y, cw, "Combine lyric versions", () -> cfg().combineVersions, v -> {
			cfg().combineVersions = v;
			mod.reloadLyrics();
		}, "Most songs have several synced versions made by different people. Lyricraft takes the middle time of every line across them, so one person's mistakes don't count.");
		y += 22;
		toggle(cx0, y, cw, "Extra source: NetEase", () -> cfg().extraLyricsSource, v -> {
			cfg().extraLyricsSource = v;
			mod.reloadLyrics();
		}, "Also asks NetEase Cloud Music (a Chinese music service) for the lyrics, as one more opinion on the timing. Sends the song title and artist to them. Off by default.");
		y += 22;
		Widgets.Action share = add(new Widgets.Action(cx0, y, cw, 20, "Share this song's fix", false, this::confirmShare,
				"Uploads this song's lyrics with your timing fix to LRCLIB, so everyone gets them right. Asks first."));
		share.active = canShare();
		y += 26;
		syncNumbersY = y;
		y += 6 * 12 + 4; // room for the live numbers
		info = "Lyricraft keeps itself in time with Spotify on its own. If a song is still early or late, fix it once: "
				+ "it's remembered for that song, and teaches the default for every other song (separately for speakers and Bluetooth).";
		return y;
	}

	private int onlineNumbersY;

	private int onlineTab(int y) {
		int cw = cx1 - cx0;
		toggle(cx0, y, cw, "Online features", () -> cfg().online, v -> {
			cfg().online = v;
			mod.online.refresh();
		}, "See which players use Lyricraft too, and what they're listening to. Connects to the Lyricraft online service.");
		y += 22;
		toggle(cx0, y, cw, "Share what I'm listening to", () -> cfg().shareLyrics, v -> {
			cfg().shareLyrics = v;
			mod.online.refresh();
		}, "Players near you who use Lyricraft see your song and the line you're hearing above your head. Turn off to only show the badge.");
		y += 22;
		toggle(cx0, y, cw, "Show ♫ badges", () -> cfg().showBadges, v -> cfg().showBadges = v,
				"A green ♫ after the names of Lyricraft players, in the tab list and above their heads.");
		y += 22;
		toggle(cx0, y, cw, "Show other players' lyrics", () -> cfg().showOthersLyrics, v -> cfg().showOthersLyrics = v,
				"Their current line and song, floating above their head.");
		y += 22;
		toggle(cx0, y, cw, "Share my Echoes", () -> cfg().shareEchoes, v -> cfg().shareEchoes = v,
				"When you use the Echoes style, nearby Lyricraft players see your panels in the same spots. Needs \"Share what I'm listening to\".");
		y += 22;
		toggle(cx0, y, cw, "Show other players' Echoes", () -> cfg().showOthersEchoes, v -> cfg().showOthersEchoes = v,
				"See the Echoes panels of Lyricraft players near you, with their name. They never cover your own.");
		y += 26;
		onlineNumbersY = y;
		y += 2 * 12 + 4;
		info = "Works on any server, no server mod needed, with official and offline accounts. Your login never leaves the game: "
				+ "official accounts are checked with the key Minecraft already uses to sign chat. "
				+ "Only your song, artist, current line and Echoes are shared, only with players on your server, and only while you play.";
		return y;
	}

	private void onlineNumbers(GuiGraphicsExtractor g, int y, int acc) {
		dev.lyricraft.online.OnlineClient.Status st = mod.online.status();
		String status = switch (st) {
			case OFF -> "Off";
			case NOT_SET_UP -> "Not set up yet (no service address)";
			case CONNECTING -> "Connecting...";
			case ONLINE -> mod.online.verified() ? "Online (official account)" : "Online (offline account)";
			case OFFLINE -> minecraft.level == null ? "Join a world to connect" : "Can't reach the service, retrying";
			case NOT_VERIFIED -> "The service didn't accept your name";
		};
		row(g, "Status", status, y, st == dev.lyricraft.online.OnlineClient.Status.ONLINE ? acc : 0xB8B8C0);
		row(g, "Players here", st == dev.lyricraft.online.OnlineClient.Status.ONLINE ? mod.online.othersOnline() + " use Lyricraft" : "-", y + 12, acc);
	}

	/** How much of this song's timing is about the song itself (not your headphones). */
	private int songFix() {
		MediaSnapshot now = mod.tracker.current();
		return now == null ? 0 : mod.sync.songOnlyOffset(now.trackKey());
	}

	private boolean canShare() {
		dev.lyricraft.lyrics.Lyrics l = mod.lyrics();
		return l != null && l.kind == dev.lyricraft.lyrics.Lyrics.Kind.SYNCED && Math.abs(songFix()) >= 50;
	}

	private void confirmShare() {
		MediaSnapshot now = mod.tracker.current();
		dev.lyricraft.lyrics.Lyrics lyrics = mod.lyrics();
		if (now == null || lyrics == null || !canShare()) return;
		int shift = songFix();
		minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
			if (yes) {
				mod.showToast("Sharing... solving LRCLIB's anti-spam puzzle");
				dev.lyricraft.lyrics.LrclibPublisher.publish(now, lyrics, shift)
						.thenAccept(msg -> minecraft.execute(() -> mod.showToast(msg)));
			}
			minecraft.gui.setScreen(this);
		}, Component.literal("Share this fix with everyone?"),
				Component.literal("This uploads the lyrics of \"" + now.title() + "\" by " + now.artist()
						+ " with your timing fix (" + signed(shift) + ") to lrclib.net, so the next person gets them in time."
						+ " It's public and can't be undone. Your headphone delay isn't included, and nothing else about you is sent.")));
	}

	/** Small grey heading drawn above a group of tiles. */
	private final List<int[]> labels = new ArrayList<>();
	private final List<String> labelText = new ArrayList<>();

	private void label(int y, String text) {
		labels.add(new int[]{y});
		labelText.add(text);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		if (minecraft.level == null) {
			super.extractBackground(g, mouseX, mouseY, partial); // title screen: panorama behind us
			LyricsHud.previewCard(g);
		}
		// drag handles on the game side
		if (panelW < width) drag.draw(g, font, width, height, inPanel(mouseX) ? -1000 : mouseX, mouseY, false);

		int acc = accent();
		int a = mod.vibe.colorA(), b = mod.vibe.colorB();
		int x1 = px + panelW;
		g.fill(px, 0, x1, height, Draw.argb(0x0A0A0E, 0.9f));
		g.fillGradient(px, 0, x1, height / 2, Draw.argb(a, 0.18f), Draw.argb(a, 0f));
		g.fillGradient(px, height / 2, x1, height, Draw.argb(b, 0f), Draw.argb(b, 0.10f));
		g.fill(px + railW, top, px + railW + 1, bottom, Draw.argb(0xFFFFFF, 0.06f));
		if (panelW < width) {
			int edge = dockRight ? px - 1 : x1;
			g.fillGradient(edge, 0, edge + 1, height, Draw.argb(a, 0.9f), Draw.argb(b, 0.9f));
			if (!dockRight) g.fillGradient(x1 + 1, 0, x1 + 10, height, Draw.argb(0x000000, 0.25f), Draw.argb(0x000000, 0f));
		}

		// header
		float t = System.nanoTime() / 1e9f;
		Draw.glowText(g, font, "♫", px + 10, 9 + (float) Math.sin(t * 2.4) * 1.2f, 2f, acc, 1f, 0.9f);
		Draw.glowText(g, font, "Lyricraft", px + 30, 9, 2f, 0xFFFFFF, 1f, 0.35f);
		Draw.text(g, font, "for Spotify  ·  v" + LyricraftClient.VERSION, px + 31, 28, 0.75f, Draw.argb(0x9A9AA6, 1f), false);
		MediaSnapshot now = mod.tracker.current();
		String np = now == null ? "Nothing playing: start a song on Spotify"
				: (mod.tracker.isPlaying() ? "▶ " : "❚❚ ") + now.title() + "  ·  " + now.artist();
		Draw.text(g, font, Draw.ellipsize(font, np, Math.round((panelW - 20) / 0.75f)), px + 10, 40, 0.75f,
				Draw.argb(now == null ? 0x8A8A96 : acc, 0.95f), false);
		g.fill(px + 8, 50, x1 - 8, 51, Draw.argb(0xFFFFFF, 0.07f));
		String key = "N opens this menu";
		Draw.text(g, font, key, x1 - 8 - font.width(key) * 0.7f, 28, 0.7f, Draw.argb(0x6A6A76, 1f), false);

		// content text (clipped to the scroll area)
		g.enableScissor(cx0, top, cx1, bottom);
		for (int i = 0; i < labels.size(); i++) {
			Draw.text(g, font, labelText.get(i), cx0 + 1, labels.get(i)[0] - scroll, 0.75f, Draw.argb(0x8A8A96, 1f), false);
		}
		int iy = infoBaseY - scroll;
		if (tab == Tab.SYNC) syncNumbers(g, syncNumbersY - scroll, acc);
		if (tab == Tab.ONLINE) onlineNumbers(g, onlineNumbersY - scroll, acc);
		String text = info;
		for (GuiEventListener l : children()) {
			if (l instanceof Widgets.Chip chip && chip.visible && chip.isHovered()) text = chip.info;
			if (l instanceof Widgets.Featured f && f.visible && f.isHovered()) text = f.info;
		}
		if (text.isEmpty()) {
			text = switch (tab) {
				case STYLE -> styleInfo(cfg().style);
				case OVERLAY -> overlayInfo(cfg().overlay) + "  Key: H";
				case CARD -> cardInfo(cfg().cardStyle) + " " + themeInfo(cfg().cardTheme);
				default -> "";
			};
		}
		if (!text.isEmpty()) g.textWithWordWrap(font, Component.literal(text), cx0, iy, cx1 - cx0, Draw.argb(0xB8B8C0, 1f), false);
		g.disableScissor();

		// scroll bar
		int view = bottom - top;
		if (contentH > view) {
			int barH = Math.max(16, view * view / contentH);
			int barY = top + (view - barH) * scroll / Math.max(1, contentH - view);
			g.fill(x1 - 4, top, x1 - 2, bottom, Draw.argb(0xFFFFFF, 0.05f));
			g.fill(x1 - 4, barY, x1 - 2, barY + barH, Draw.argb(acc, 0.7f));
		}

		// hint on the game side
		int gameX0 = dockRight ? 0 : x1, gameX1 = dockRight ? px : width;
		if (gameX1 - gameX0 > 150 && !drag.isDragging()) {
			String hint = "Live preview  ·  drag the card or lyrics to move them";
			float sc = Math.min(1f, (gameX1 - gameX0 - 16f) / font.width(hint));
			float hw = font.width(hint) * sc;
			float hx = (gameX0 + gameX1) / 2f - hw / 2f;
			int hy = height - 20;
			Widgets.panel(g, Math.round(hx - 6), hy - 3, Math.round(hx + hw + 6), hy + 10, Draw.argb(0x000000, 0.5f));
			Draw.text(g, font, hint, hx, hy, sc, Draw.argb(0xFFFFFF, 0.8f), false);
		}
	}

	private boolean inPanel(double mx) {
		return mx >= px && mx < px + panelW;
	}

	private void syncNumbers(GuiGraphicsExtractor g, int y, int acc) {
		MediaSnapshot now = mod.tracker.current();
		boolean bt = dev.lyricraft.media.AudioOutput.bluetooth();
		String song = now == null ? "-" : signed(mod.sync.offsetFor(now.trackKey()))
				+ (mod.sync.hasOwnOffset(now.trackKey()) ? "  (yours)" : "  (default)");
		row(g, "This song", song, y, acc);
		row(g, "Default", signed(cfg().offsetMs) + " speakers  ·  " + signed(cfg().offsetBluetoothMs) + " Bluetooth", y + 12, acc);
		String out = dev.lyricraft.media.AudioOutput.name();
		row(g, "Sound out", (out.isEmpty() ? (bt ? "Bluetooth" : "-") : Draw.ellipsize(font, out, cx1 - cx0 - 96)) + (bt ? "  (Bluetooth)" : ""), y + 24, acc);
		dev.lyricraft.lyrics.Lyrics l = mod.lyrics();
		String lyr = l == null ? "-" : l.kind != dev.lyricraft.lyrics.Lyrics.Kind.SYNCED ? "not synced"
				: (l.versions > 1 ? l.versions + " versions combined" : "1 version") + (l.source.isEmpty() ? "" : "  ·  " + l.source);
		row(g, "Lyrics", lyr, y + 36, acc);
		double rate = mod.tracker.rate();
		row(g, "Clock speed", String.format("%.3f×", rate) + (Math.abs(rate - 1) < 0.0005 ? "  perfect" : "  correcting"), y + 48, acc);
		row(g, "Last drift", mod.tracker.lastDriftMs() + " ms", y + 60, acc);
	}

	private void row(GuiGraphicsExtractor g, String label, String value, int y, int acc) {
		g.text(font, label, cx0, y, Draw.argb(0x9A9AA6, 1f), false);
		g.text(font, value, cx0 + 86, y, Draw.argb(acc, 1f), false);
	}

	// ------------------------------------------------------------------ mouse

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) return true;
		return !inPanel(e.x()) && drag.press(e, width, height);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (drag.drag(e, width, height)) return true;
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (drag.release()) {
			// the card may have moved to the other half: move the panel out of its way
			float[] card = NowPlayingCard.place(cfg(), width, height);
			boolean wantRight = panelW < width && card[0] + card[2] / 2f < width / 2f;
			if (wantRight != dockRight) rebuildWidgets();
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (inPanel(mx)) {
			if (my >= top && my <= bottom && contentH > bottom - top) {
				scroll = Math.clamp(scroll - (int) Math.round(sy * 18), 0, contentH - (bottom - top));
				lastScroll = scroll;
				applyScroll();
				return true;
			}
			return super.mouseScrolled(mx, my, sx, sy);
		}
		if (drag.scroll(mx, my, sy, width, height)) return true;
		return super.mouseScrolled(mx, my, sx, sy);
	}

	// ------------------------------------------------------------------ helpers

	private void toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set, String help) {
		add(new Widgets.Toggle(x, y, w, label, get, v -> {
			set.accept(v);
			cfg().save();
		}, help));
	}

	private void nudge(int ms) {
		MediaSnapshot now = mod.tracker.current();
		if (now != null) mod.sync.nudge(now.trackKey(), ms);
	}

	private String colourName() {
		if (cfg().accentFromCover) return "Album cover";
		int i = indexOfColour(cfg().accentColor & 0xFFFFFF);
		return i < 0 ? "Custom" : COLOUR_NAMES[i];
	}

	private int colourSwatch() {
		return cfg().accentFromCover ? mod.vibe.colorA() : cfg().accentColor & 0xFFFFFF;
	}

	@Override
	public void onClose() {
		drag.release();
		cfg().save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static int indexOfColour(int rgb) {
		for (int i = 0; i < COLOURS.length; i++) if (COLOURS[i] == rgb) return i;
		return -1;
	}

	private static String signed(int ms) {
		return (ms > 0 ? "+" : "") + ms + " ms";
	}

	private static String positionName(Config.Position p) {
		return switch (p) {
			case ABOVE_HOTBAR -> "Bottom";
			case TOP_CENTER -> "Top";
			case LEFT_SIDE -> "Left";
			case RIGHT_SIDE -> "Right";
			case CUSTOM -> "Custom (dragged)";
		};
	}

	static String cardName(Config.CardStyle s) {
		return switch (s) {
			case CARD -> "Card";
			case COMPACT -> "Compact";
			case PILL -> "Pill";
			case COVER -> "Cover";
			case MINIMAL -> "Minimal";
		};
	}

	static String cardInfo(Config.CardStyle s) {
		return switch (s) {
			case CARD -> "Cover, vinyl, title, artist, details and progress.";
			case COMPACT -> "A small strip: little cover, title, artist and a thin progress line.";
			case PILL -> "One slim line with the cover spinning like a record.";
			case COVER -> "Just the album cover with the title underneath.";
			case MINIMAL -> "No box at all: just the song and artist with a shadow.";
		};
	}

	static String themeName(Config.CardTheme t) {
		return switch (t) {
			case GLASS -> "Glass";
			case DARK -> "Dark";
			case LIGHT -> "Light";
			case SPOTIFY -> "Spotify";
			case SONG -> "Song colours";
			case NEON -> "Neon";
			case TOOLTIP -> "Minecraft";
		};
	}

	static String themeInfo(Config.CardTheme t) {
		return switch (t) {
			case GLASS -> "Glass: dark see-through glass tinted by the album cover.";
			case DARK -> "Dark: solid dark grey like the Spotify app.";
			case LIGHT -> "Light: white card with dark text.";
			case SPOTIFY -> "Spotify: bright Spotify-green gradient.";
			case SONG -> "Song colours: a bold gradient made from the album cover.";
			case NEON -> "Neon: black with a glowing border in your lyric colour.";
			case TOOLTIP -> "Minecraft: the classic purple item-tooltip look.";
		};
	}

	static String styleInfo(Config.Style s) {
		return switch (s) {
			case CLASSIC -> "The default. Clean karaoke lines with a soft glow. Works everywhere. Can be dragged.";
			case SKY -> "Giant lyrics written across the sky. Switches to Classic in caves and buildings.";
			case ORBIT -> "Words spin in a ring around you and pop as they're sung.";
			case ECHOES -> "Every line appears where you look and stays there in the world, leaving a trail of lyrics. Lines never stack on top of each other.";
			case EDIT -> "Like a video edit: every line is stamped onto whatever you look at, the words slam onto the wall or ground one by one and kick up its dust, and the camera punches in on the beat. Trees and mobs can pass in front of the words.";
			case POP -> "Words appear one by one with a bouncy pop. Can be dragged.";
			case NEON -> "Flickering neon-sign lyrics. Can be dragged.";
			case WAVE -> "Letters ride a wave that moves with the song. Can be dragged.";
		};
	}

	static String overlayInfo(Config.Overlay o) {
		return switch (o) {
			case NONE -> "No overlay.";
			case VINYL -> "A spinning record player in the corner with the song on its label (replaces the card in-game).";
			case CASSETTE -> "A walkman tape whose reels wind as the song plays (replaces the card in-game).";
			case VHS -> "Retro VHS tape look: tracking lines, REC timer and colour bleed (replaces the card in-game).";
			case SYNTHWAVE -> "80s sunset with a neon grid floor.";
			case VISUALIZER -> "Spectrum bars along the bottom that dance with the words.";
			case RAIN -> "Lo-fi rainy window with droplets and city lights.";
			case STARS -> "Twinkling night sky with a shooting star on every new line.";
			case OSCILLOSCOPE -> "A glowing waveform across the top of the screen.";
			case CONFETTI -> "Confetti bursts from the corners on every new line.";
		};
	}
}
