package dev.lyricraft.test;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.gui.LayoutScreen;
import dev.lyricraft.gui.LyricraftScreen;
import dev.lyricraft.hud.LyricsAnchor;
import dev.lyricraft.hud.NowPlayingCard;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/**
 * Real in-game checks: drags the Now Playing card and the lyrics with the mouse (in the layout
 * editor and in the main menu), checks the config actually changed, and screenshots every card
 * look and theme. Run with ./gradlew runClientGameTest.
 */
public final class LyricraftClientGameTest implements FabricClientGameTest {

	/** The real mouse buttons (since 26.x, left is 1 and right is 3). */
	private static final int LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
	private static final int RIGHT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if ("1".equals(System.getenv("LYRICRAFT_GALLERY")) || "1".equals(System.getenv("LYRICRAFT_PERF"))) return; // gallery/benchmark run only
		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			Config cfg = LyricraftClient.get().config;
			ctx.runOnClient(mc -> {
				cfg.enabled = true;
				cfg.showNowPlaying = true;
				cfg.style = Config.Style.CLASSIC;
				cfg.overlay = Config.Overlay.NONE;
				cfg.position = Config.Position.ABOVE_HOTBAR;
				cfg.cardStyle = Config.CardStyle.CARD;
				cfg.cardTheme = Config.CardTheme.GLASS;
				cfg.cardX = 0f;
				cfg.cardY = 0f;
				cfg.cardScale = 1f;
			});

			// ---------------------------------------------------------- layout editor
			ctx.setScreen(() -> new LayoutScreen(LyricraftClient.get(), null));
			ctx.waitTicks(10);
			ctx.takeScreenshot("lyricraft-01-layout-editor");

			float[] card = ctx.computeOnClient(mc -> NowPlayingCard.lastRect.clone());
			log("card drawn at " + card[0] + "," + card[1] + " size " + card[2] + "x" + card[3]);
			check(card[2] > 10 && card[3] > 10, "card should be drawn in the layout editor");

			dragGui(ctx, card[0] + card[2] / 2, card[1] + card[3] / 2, 200, 120);
			float cx = ctx.computeOnClient(mc -> cfg.cardX), cy = ctx.computeOnClient(mc -> cfg.cardY);
			log("after dragging the card in the editor: cardX=" + cx + " cardY=" + cy);
			check(cx > 0.05f && cy > 0.05f, "card should move when dragged in the layout editor");
			ctx.takeScreenshot("lyricraft-02-card-dragged");

			// lyrics (move the card out of the way first: it sits on top and would be grabbed instead)
			ctx.runOnClient(mc -> {
				cfg.cardX = 0f;
				cfg.cardY = 0f;
			});
			ctx.waitTicks(3);
			float[] lc = ctx.computeOnClient(mc -> LyricsAnchor.center(cfg, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
			dragGui(ctx, lc[0], lc[1], -60, -80);
			Config.Position pos = ctx.computeOnClient(mc -> cfg.position);
			float ly = ctx.computeOnClient(mc -> cfg.lyricsY);
			log("after dragging the lyrics: position=" + pos + " lyricsX=" + ctx.computeOnClient(mc -> cfg.lyricsX) + " lyricsY=" + ly);
			check(pos == Config.Position.CUSTOM && ly < 0.8f, "lyrics should move when dragged");
			ctx.takeScreenshot("lyricraft-03-lyrics-dragged");

			// scroll over the card resizes it
			float[] card2 = ctx.computeOnClient(mc -> NowPlayingCard.lastRect.clone());
			moveTo(ctx, card2[0] + card2[2] / 2, card2[1] + card2[3] / 2);
			ctx.getInput().scroll(-2);
			ctx.waitTicks(2);
			float scale = ctx.computeOnClient(mc -> cfg.cardScale);
			log("after scrolling over the card: cardScale=" + scale);
			check(scale < 1f, "scrolling over the card should resize it");

			// right-click on the card puts it back in the corner
			ctx.runOnClient(mc -> {
				cfg.cardX = 0.5f;
				cfg.cardY = 0.5f;
			});
			ctx.waitTicks(3);
			float[] card4 = ctx.computeOnClient(mc -> NowPlayingCard.lastRect.clone());
			moveTo(ctx, card4[0] + card4[2] / 2, card4[1] + card4[3] / 2);
			ctx.getInput().pressMouse(RIGHT);
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> cfg.cardX) == 0f && ctx.computeOnClient(mc -> cfg.cardY) == 0f,
					"right-clicking the card resets its position");

			// the ‹ › pickers: left click = next, right click = previous
			Config.CardTheme t0 = ctx.computeOnClient(mc -> cfg.cardTheme);
			float[] themeBtn = widgetCenter(ctx, "Theme");
			moveTo(ctx, themeBtn[0], themeBtn[1]);
			ctx.getInput().pressMouse(LEFT);
			ctx.waitTicks(2);
			Config.CardTheme t1 = ctx.computeOnClient(mc -> cfg.cardTheme);
			check(t1.ordinal() == (t0.ordinal() + 1) % Config.CardTheme.values().length, "left-click on a picker goes forward");
			ctx.getInput().pressMouse(RIGHT);
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> cfg.cardTheme) == t0, "right-click on a picker goes back");

			// ---------------------------------------------------------- main menu: drag on the game side
			ctx.runOnClient(mc -> {
				cfg.cardX = 0f;
				cfg.cardY = 0f;
				cfg.cardScale = 1f;
			});
			ctx.setScreen(() -> new LyricraftScreen(LyricraftClient.get(), null));
			ctx.waitTicks(10);
			ctx.takeScreenshot("lyricraft-04-menu");
			float[] card3 = ctx.computeOnClient(mc -> NowPlayingCard.lastRect.clone());
			dragGui(ctx, card3[0] + card3[2] / 2, card3[1] + card3[3] / 2, 40, 150);
			float my = ctx.computeOnClient(mc -> cfg.cardY);
			log("after dragging the card in the main menu: cardY=" + my);
			check(my > 0.05f, "card should move when dragged in the main menu");
			ctx.waitTicks(5);
			ctx.takeScreenshot("lyricraft-05-menu-after-drag");

			// every tab really switches (each has a widget only it shows)
			String[][] tabs = {{"Card", "Show the card"}, {"Lyrics", "Hide when paused"}, {"Vibe", "Edge glow"},
					{"Overlay", "Confetti"}, {"Sync", "Auto-sync"}, {"Style", "Echoes"}};
			for (String[] pair : tabs) {
				String tab = pair[0];
				clickWidget(ctx, tab);
				ctx.waitTicks(3);
				check(hasWidget(ctx, pair[1]), "clicking the " + tab + " tab opens it");
				if (tab.equals("Lyrics")) {
					boolean before = ctx.computeOnClient(mc -> cfg.hideWhenPaused);
					clickWidget(ctx, "Hide when paused");
					boolean after = ctx.computeOnClient(mc -> cfg.hideWhenPaused);
					check(before != after, "clicking a switch flips the setting");
					clickWidget(ctx, "Hide when paused");
				}
				if (tab.equals("Overlay")) {
					clickWidget(ctx, "Confetti");
					check(ctx.computeOnClient(mc -> cfg.overlay) == Config.Overlay.CONFETTI, "clicking an overlay tile picks it");
					clickWidget(ctx, "None");
				}
				// also scroll the panel to the bottom and back so scrolling gets exercised
				float[] mid = ctx.computeOnClient(mc -> {
					var s = mc.gui.screen();
					return new float[]{s.width > 340 ? (tab.isEmpty() ? 0 : panelCenterX(mc)) : s.width / 2f, s.height / 2f};
				});
				moveTo(ctx, mid[0], mid[1]);
				ctx.getInput().scroll(-20);
				ctx.waitTick();
				ctx.getInput().scroll(20);
				ctx.waitTick();
				ctx.takeScreenshot("lyricraft-06-tab-" + tab.toLowerCase());
			}

			// ---------------------------------------------------------- chat + lyrics off keep the card
			ctx.setScreen(() -> null);
			boolean playing = ctx.computeOnClient(mc -> LyricraftClient.get().tracker.current() != null);
			if (playing) {
				ctx.runOnClient(mc -> cfg.hideInMenus = true);
				ctx.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
				ctx.waitTicks(20);
				check(ctx.computeOnClient(mc -> NowPlayingCard.drawnRecently()), "the card stays visible while chat is open");
				ctx.takeScreenshot("lyricraft-06b-chat-open");
				ctx.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc().player));
				ctx.waitTicks(20);
				check(ctx.computeOnClient(mc -> NowPlayingCard.drawnRecently()), "the card stays visible behind the inventory");
				ctx.takeScreenshot("lyricraft-06c-inventory-open");
				ctx.setScreen(() -> null);
				ctx.runOnClient(mc -> cfg.enabled = false);
				ctx.waitTicks(20);
				check(ctx.computeOnClient(mc -> NowPlayingCard.drawnRecently()), "the card stays when lyrics are turned off");
				ctx.runOnClient(mc -> cfg.enabled = true);
			} else {
				log("nothing playing on Spotify: skipped the chat / lyrics-off card checks");
			}

			// ---------------------------------------------------------- sync
			if (playing) {
				// wait for lyrics to arrive (network), then check the combined result and the clock
				ctx.waitFor(mc -> LyricraftClient.get().lyrics() != null, 400);
				var lyr = ctx.computeOnClient(mc -> LyricraftClient.get().lyrics());
				log("lyrics: " + lyr.kind + ", " + lyr.versions + " version(s) combined, source '" + lyr.source + "'");
				String out = ctx.computeOnClient(mc -> dev.lyricraft.media.AudioOutput.name());
				log("sound output: '" + out + "' bluetooth=" + dev.lyricraft.media.AudioOutput.bluetooth());
				check(!out.isEmpty(), "the sound output device is detected");
				// let the clock settle over a few real Spotify reports, then compare drift
				ctx.waitTicks(20 * 14);
				long drift = ctx.computeOnClient(mc -> LyricraftClient.get().tracker.lastDriftMs());
				double rate = ctx.computeOnClient(mc -> LyricraftClient.get().tracker.rate());
				log("after 14 s: last drift " + drift + " ms, clock speed " + rate);
				check(Math.abs(drift) < 120, "the lyrics clock stays within 120 ms of Spotify");
				ctx.setScreen(() -> new LyricraftScreen(LyricraftClient.get(), null));
				ctx.waitTicks(5);
				clickWidget(ctx, "Sync");
				ctx.waitTicks(5);
				ctx.takeScreenshot("lyricraft-09-sync-tab");
				ctx.setScreen(() -> null);
			}

			// ---------------------------------------------------------- online (needs a local service: cd server && npm run dev)
			String local = System.getProperty("lyricraft.testServer", "http://127.0.0.1:8787");
			boolean serviceUp;
			try (var s = new java.net.Socket()) {
				s.connect(new java.net.InetSocketAddress("127.0.0.1", 8787), 500);
				serviceUp = true;
			} catch (Exception e) {
				serviceUp = false;
			}
			if (serviceUp) {
				ctx.runOnClient(mc -> {
					cfg.onlineServer = local;
					cfg.online = true;
					cfg.shareLyrics = true;
					cfg.showBadges = true;
					cfg.showOthersLyrics = true;
				});
				ctx.waitFor(mc -> LyricraftClient.get().online.status() == dev.lyricraft.online.OnlineClient.Status.ONLINE, 300);
				ctx.waitTicks(60); // watch list + our own state come back
				java.util.UUID me = ctx.computeOnClient(mc -> mc.player.getUUID());
				check(ctx.computeOnClient(mc -> LyricraftClient.get().online.usesMod(me)), "connected and we show up as a Lyricraft player");
				String tabName = ctx.computeOnClient(mc -> mc.gui.hud.getTabList()
						.getNameForDisplay(mc.getConnection().getPlayerInfo(me)).getString());
				log("tab list name: " + tabName);
				check(tabName.endsWith("♫"), "the tab list shows the ♫ badge");
				String tag = ctx.computeOnClient(mc -> {
					try {
						var renderer = mc.getEntityRenderDispatcher().getRenderer(mc.player);
						var m = net.minecraft.client.renderer.entity.EntityRenderer.class.getDeclaredMethod("getNameTag", net.minecraft.world.entity.Entity.class);
						m.setAccessible(true);
						Object c = m.invoke(renderer, mc.player);
						return c == null ? "" : ((net.minecraft.network.chat.Component) c).getString();
					} catch (ReflectiveOperationException e) {
						return "error: " + e;
					}
				});
				log("name tag: " + tag);
				check(tag.endsWith("♫"), "the name tag shows the ♫ badge");
				if (ctx.computeOnClient(mc -> LyricraftClient.get().tracker.isPlaying())) { // shared only while playing
					ctx.waitFor(mc -> {
						var r = LyricraftClient.get().online.remote(me);
						return r != null && r.song() != null;
					}, 200);
					var r = ctx.computeOnClient(mc -> LyricraftClient.get().online.remote(me));
					log("shared state came back: song='" + r.song() + "' line='" + r.line() + "'");
					check(r.song() != null, "our song comes back from the service");
				}
				ctx.getInput().holdKey(o -> o.keyPlayerList);
				ctx.waitTicks(5);
				ctx.takeScreenshot("lyricraft-10-tab-badge");
				ctx.getInput().releaseKey(o -> o.keyPlayerList);
				ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
				ctx.waitTicks(40);
				ctx.takeScreenshot("lyricraft-11-lyrics-above-head");
				ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
				ctx.setScreen(() -> new LyricraftScreen(LyricraftClient.get(), null));
				ctx.waitTicks(5);
				clickWidget(ctx, "Online");
				ctx.waitTicks(5);
				ctx.takeScreenshot("lyricraft-12-online-tab");
				ctx.setScreen(() -> null);
			} else {
				log("no local online service on :8787: skipped the online checks");
			}

			// ---------------------------------------------------------- friends' Echoes panels
			// (demo panels: this switches the online client into demo mode, so it runs after the online checks)
			ctx.runOnClient(mc -> {
				cfg.showOthersEchoes = true;
				var on = LyricraftClient.get().online;
				var look = mc.player.getLookAngle();
				double bx = mc.player.getX() + look.x * 5, by = mc.player.getEyeY(), bz = mc.player.getZ() + look.z * 5;
				String dim = mc.level.dimension().identifier().toString();
				String[] lines = {"we were singing in the rain", "under the glass harbor lights", "hold on to the night", "and the city hums along"};
				// five friends all dropping panels on almost the same spot: 20 sent, the caps keep 16, at most 12 drawn
				for (int f = 0; f < 5; f++) {
					java.util.UUID id = new java.util.UUID(0x5eed, f + 1);
					for (int i = 0; i < 4; i++)
						on.addDemoEcho(id, bx + f * 0.05, by + i * 0.05, bz, dim, lines[(f + i) % lines.length], 4000, 500L * i, 2.5f, 0.4f);
				}
				// one in another dimension, which must never show
				on.addDemoEcho(new java.util.UUID(0x5eed, 99), bx, by, bz, "minecraft:the_nether", "wrong world", 4000, 0, 2.5f, 0.4f);
			});
			ctx.waitTicks(60);
			int stored = ctx.computeOnClient(mc -> LyricraftClient.get().online.echoes().size());
			check(stored <= dev.lyricraft.online.OnlineClient.ECHOES_TOTAL, "friends' panels are capped (" + stored + " kept)");
			var placed = ctx.computeOnClient(mc -> dev.lyricraft.hud.RemoteEchoes.placedLastFrame());
			log("friends' panels drawn: " + placed.size());
			check(!placed.isEmpty() && placed.size() <= 12, "some friends' panels are drawn, at most 12");
			boolean overlap = false;
			for (int i = 0; i < placed.size(); i++)
				for (int j = i + 1; j < placed.size(); j++) {
					float[] p = placed.get(i), q = placed.get(j);
					if (p[0] < q[2] && q[0] < p[2] && p[1] < q[3] && q[1] < p[3]) overlap = true;
				}
			check(!overlap, "friends' panels never overlap each other");
			ctx.takeScreenshot("lyricraft-13-friends-echoes");

			// ---------------------------------------------------------- every look and theme
			ctx.runOnClient(mc -> {
				cfg.cardX = 0f;
				cfg.cardY = 0f;
			});
			for (Config.CardTheme theme : Config.CardTheme.values()) {
				for (Config.CardStyle look : Config.CardStyle.values()) {
					ctx.runOnClient(mc -> {
						cfg.cardTheme = theme;
						cfg.cardStyle = look;
					});
					ctx.waitTicks(3);
				}
				ctx.runOnClient(mc -> cfg.cardStyle = Config.CardStyle.CARD);
				ctx.waitTicks(3);
				ctx.takeScreenshot("lyricraft-07-theme-" + theme.name().toLowerCase());
			}
			ctx.runOnClient(mc -> cfg.cardTheme = Config.CardTheme.GLASS);
			for (Config.CardStyle look : Config.CardStyle.values()) {
				ctx.runOnClient(mc -> cfg.cardStyle = look);
				ctx.waitTicks(3);
				ctx.takeScreenshot("lyricraft-08-look-" + look.name().toLowerCase());
			}
			log("ALL CHECKS PASSED");
		}
	}

	private static float[] widgetCenter(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> {
			for (var l : mc.gui.screen().children()) {
				if (l instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible
						&& w.getMessage().getString().equals(label)) {
					return new float[]{w.getX() + w.getWidth() / 2f, w.getY() + w.getHeight() / 2f};
				}
			}
			return null;
		});
	}

	private static boolean hasWidget(ClientGameTestContext ctx, String label) {
		return widgetCenter(ctx, label) != null;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** Clicks one of our (custom) widgets by its label, with the real mouse. */
	static void clickWidget(ClientGameTestContext ctx, String label) {
		float[] at = widgetCenter(ctx, label);
		check(at != null, "widget '" + label + "' exists");
		moveTo(ctx, at[0], at[1]);
		ctx.getInput().pressMouse(LEFT);
		ctx.waitTicks(2);
	}

	/** Middle of the settings panel (found from its Done button). */
	private static float panelCenterX(Minecraft mc) {
		for (var l : mc.gui.screen().children()) {
			if (l instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals("Done")) {
				return w.getX() + w.getWidth() / 2f;
			}
		}
		return mc.gui.screen().width / 2f;
	}

	/** Press at a GUI point, move by (dx, dy) GUI pixels in small steps, release. */
	private static void dragGui(ClientGameTestContext ctx, float gx, float gy, float dx, float dy) {
		TestInput in = ctx.getInput();
		moveTo(ctx, gx, gy);
		in.holdMouse(LEFT);
		ctx.waitTick();
		int steps = 10;
		for (int i = 1; i <= steps; i++) {
			moveTo(ctx, gx + dx * i / steps, gy + dy * i / steps);
			ctx.waitTick();
		}
		in.releaseMouse(LEFT);
		ctx.waitTicks(2);
	}

	private static void moveTo(ClientGameTestContext ctx, float gx, float gy) {
		double[] raw = ctx.computeOnClient(mc -> toRaw(mc, gx, gy));
		ctx.getInput().setCursorPos(raw[0], raw[1]);
	}

	private static double[] toRaw(Minecraft mc, float gx, float gy) {
		var w = mc.getWindow();
		return new double[]{gx * (double) w.getScreenWidth() / w.getGuiScaledWidth(),
				gy * (double) w.getScreenHeight() / w.getGuiScaledHeight()};
	}

	private static void check(boolean ok, String what) {
		if (!ok) throw new AssertionError("FAILED: " + what);
		log("ok: " + what);
	}

	private static void log(String s) {
		LyricraftClient.LOG.info("[lyricraft-test] {}", s);
	}
}
