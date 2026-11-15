package dev.lyricraft.test;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.gui.LyricraftScreen;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import dev.lyricraft.media.MediaSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Takes the screenshots for the Modrinth gallery. Only runs when LYRICRAFT_GALLERY=1 is set.
 * Uses a made-up demo song (own lyrics, generated cover) so no real artist's work is shown.
 */
public final class GalleryShots implements FabricClientGameTest {

	static final String TITLE = "Glass Harbor", ARTIST = "The Late Signals";
	static final long DURATION = 200_000;
	static final String LRC = String.join("\n",
			"[00:08.00]We left the lights on in the harbor",
			"[00:12.20]So the night would know our names",
			"[00:16.40]Every wave a slow goodbye",
			"[00:20.30]Every star a borrowed flame",
			"[00:24.50]",
			"[00:26.00]Hold on to the glass and the sea",
			"[00:30.10]Sing it back, sing it back to me",
			"[00:34.20]Paper boats and northern weather",
			"[00:38.40]Drifting out beyond the frame",
			"[00:42.60]If the morning finds us later",
			"[00:46.70]Let it find us all the same",
			"[00:50.80]",
			"[00:53.00]We were young and the world was loud",
			"[00:57.20]Now it's only you and the sound",
			"[01:01.40]Of the tide coming in, coming in",
			"[01:05.60]Hold on to the glass and the sea");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"1".equals(System.getenv("LYRICRAFT_GALLERY"))) return;
		LyricraftClient mod = LyricraftClient.get();
		Config cfg = mod.config;
		Lyrics lyrics = Lyrics.parseLrc(LRC, DURATION);

		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			mc.options.bobView().set(false);
		});
		ctx.getInput().resizeWindow(1920, 1080);
		ctx.runOnClient(mc -> mc.resizeGui());

		try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(s -> s.setSeed("lyricraft")).create()) {
			world.getConnection().waitForChunksRender();
			var server = world.getServer();
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(4);
				mc.options.bobView().set(false);
				mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
				mc.resizeGui();
			});
			mode(server, net.minecraft.world.level.GameType.SPECTATOR);
			server.runCommand("weather clear 100000");

			// find a pretty spot: a cherry grove, else a meadow, else stay at spawn
			int[] spot = server.computeOnServer(s -> {
				var level = s.overworld();
				BlockPos from = BlockPos.ZERO;
				var found = level.findClosestBiome3d(h -> h.is(Biomes.CHERRY_GROVE), from, 3200, 32, 64);
				if (found == null) found = level.findClosestBiome3d(h -> h.is(Biomes.MEADOW), from, 3200, 32, 64);
				int bx = found == null ? 0 : found.getFirst().getX(), bz = found == null ? 0 : found.getFirst().getZ();
				// look around for an open clearing (sky above us and around us) so shots aren't dark
				for (int r = 0; r <= 40; r += 4) {
					for (int dx = -r; dx <= r; dx += 4) {
						for (int dz = -r; dz <= r; dz += 4) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
							int x = bx + dx, z = bz + dz;
							level.getChunk(x >> 4, z >> 4);
							int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
							boolean open = true;
							for (int ox = -3; ox <= 3 && open; ox += 3) {
								for (int oz = -3; oz <= 3 && open; oz += 3) {
									int yy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + ox, z + oz);
									if (yy > y + 2) open = false; // a tree or wall right next to us
								}
							}
							if (open && level.canSeeSky(new BlockPos(x, y + 1, z))) return new int[]{x, y, z};
						}
					}
				}
				level.getChunk(bx >> 4, bz >> 4);
				return new int[]{bx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz};
			});
			log("photo spot " + spot[0] + " " + spot[1] + " " + spot[2]);
			String at = spot[0] + ".5 " + (spot[1] + 1) + " " + spot[2] + ".5";
			server.runCommand("tp @a " + at + " 0 0");
			ctx.waitTicks(40);
			world.getConnection().waitForChunksRender();

			MediaSnapshot base = track(0);
			CoverArt.showDemo(base.trackKey(), cover());
			ctx.runOnClient(mc -> {
				cfg.enabled = true;
				cfg.showNowPlaying = true;
				cfg.cardStyle = Config.CardStyle.CARD;
				cfg.cardTheme = Config.CardTheme.GLASS;
				cfg.cardScale = 1f;
				cfg.cardX = 0f;
				cfg.cardY = 0f;
				cfg.overlay = Config.Overlay.NONE;
				cfg.vibe = Config.Vibe.SUBTLE;
				cfg.position = Config.Position.ABOVE_HOTBAR;
				cfg.online = false;
				cfg.style = Config.Style.ECHOES;
			});

			// 1) Echoes: turn a little between lines so they leave a trail
			server.runCommand("time set 11900");
			float yaw0 = -40;
			for (int k = 0; k < 6; k++) {
				Lyrics.Line line = lyrics.lines.get(k);
				server.runCommand("tp @a " + at + " " + (yaw0 + k * 15) + " " + (k % 2 == 0 ? -7 : 3));
				ctx.waitTicks(4);
				play(ctx, mod, lyrics, line.startMs() - 150);
				ctx.waitTicks(22);
			}
			server.runCommand("tp @a " + at + " " + (yaw0 + 40) + " -2");
			play(ctx, mod, lyrics, lyrics.lines.get(5).startMs() + 1700);
			ctx.waitTicks(25);
			shot(ctx, "01-echoes");

			// 2) Sky Writing at dusk
			ctx.runOnClient(mc -> cfg.style = Config.Style.SKY);
			server.runCommand("time set 12900");
			server.runCommand("tp @a " + at + " 40 -18");
			play(ctx, mod, lyrics, lyrics.lines.get(6).startMs() + 900);
			ctx.waitTicks(70);
			shot(ctx, "02-sky-writing");

			// 3) Classic + card, daytime (normal survival-style screen with the hotbar)
			mode(server, net.minecraft.world.level.GameType.SURVIVAL);
			ctx.runOnClient(mc -> cfg.style = Config.Style.CLASSIC);
			server.runCommand("time set 4000");
			server.runCommand("tp @a " + at + " 150 4");
			play(ctx, mod, lyrics, lyrics.lines.get(9).startMs() + 1600);
			ctx.waitTicks(40);
			shot(ctx, "03-classic");

			// 4) the menu (Style tab, Echoes recommended)
			ctx.setScreen(() -> new LyricraftScreen(mod, null));
			ctx.waitTicks(10);
			shot(ctx, "04-menu-style");

			// 5) card looks and themes
			ctx.runOnClient(mc -> cfg.cardTheme = Config.CardTheme.NEON);
			LyricraftClientGameTest.clickWidget(ctx, "Card");
			ctx.waitTicks(10);
			shot(ctx, "05-menu-card");
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> cfg.cardTheme = Config.CardTheme.GLASS);

			// 6) a friend's line above their head (third person, demo player = you)
			ctx.runOnClient(mc -> {
				cfg.enabled = false; // only the card and the above-head line
				cfg.online = true;
				cfg.showOthersLyrics = true;
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				Lyrics.Line l = lyrics.lines.get(13);
				mod.online.showDemo(mc.player.getUUID(), TITLE, ARTIST, l.text(), l.durationMs(), 1500);
			});
			server.runCommand("time set 11500");
			mode(server, net.minecraft.world.level.GameType.CREATIVE);
			server.runCommand("tp @a " + at + " 200 6");
			play(ctx, mod, lyrics, lyrics.lines.get(13).startMs() + 1500);
			ctx.waitTicks(30);
			shot(ctx, "06-above-head");
			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				cfg.enabled = true;
			});

			// 7) Neon + Synthwave at night, full vibe
			mode(server, net.minecraft.world.level.GameType.SPECTATOR);
			ctx.runOnClient(mc -> {
				cfg.style = Config.Style.NEON;
				cfg.overlay = Config.Overlay.SYNTHWAVE;
				cfg.vibe = Config.Vibe.FULL;
			});
			server.runCommand("time set 18000");
			server.runCommand("tp @a " + at + " 90 0");
			play(ctx, mod, lyrics, lyrics.lines.get(7).startMs() + 1800);
			ctx.waitTicks(40);
			shot(ctx, "07-neon-synthwave");

			// 8) Wave + Vinyl overlay, evening
			ctx.runOnClient(mc -> {
				cfg.style = Config.Style.WAVE;
				cfg.overlay = Config.Overlay.VINYL;
				cfg.vibe = Config.Vibe.SUBTLE;
			});
			server.runCommand("time set 12000");
			server.runCommand("tp @a " + at + " -120 -3");
			play(ctx, mod, lyrics, lyrics.lines.get(11).startMs() + 1500);
			ctx.waitTicks(40);
			shot(ctx, "08-wave-vinyl");

			// 9) Pop + lo-fi rain at night
			ctx.runOnClient(mc -> {
				cfg.style = Config.Style.POP;
				cfg.overlay = Config.Overlay.RAIN;
			});
			server.runCommand("time set 12600");
			server.runCommand("tp @a " + at + " 10 0");
			play(ctx, mod, lyrics, lyrics.lines.get(2).startMs() + 1800);
			ctx.waitTicks(40);
			shot(ctx, "09-pop-rain");

			ctx.runOnClient(mc -> {
				mod.showDemo(null, null);
				CoverArt.showDemo(null, null);
				cfg.overlay = Config.Overlay.NONE;
				cfg.style = Config.Style.CLASSIC;
				cfg.vibe = Config.Vibe.SUBTLE;
			});
			log("GALLERY DONE");
		}
	}

	private static void mode(net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server, net.minecraft.world.level.GameType type) {
		server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> p.setGameMode(type)));
	}

	private static MediaSnapshot track(long posMs) {
		return new MediaSnapshot("Spotify.exe", TITLE, ARTIST, "Harbor Lights", posMs, DURATION, true, System.nanoTime());
	}

	private static void play(ClientGameTestContext ctx, LyricraftClient mod, Lyrics lyrics, long posMs) {
		ctx.runOnClient(mc -> mod.showDemo(track(posMs), lyrics));
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot("gallery-" + name);
		log("shot " + name);
	}

	/** A simple generated cover: dusk sky, sun, sea. */
	static BufferedImage cover() {
		int n = 300;
		BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(new GradientPaint(0, 0, new Color(0x2B1B5A), 0, n * 0.62f, new Color(0xF2836B)));
		g.fillRect(0, 0, n, n);
		g.setColor(new Color(0xFFD08A));
		g.fillOval(n / 2 - 52, (int) (n * 0.62f) - 52, 104, 104);
		g.setPaint(new GradientPaint(0, n * 0.62f, new Color(0x1E3F66), 0, n, new Color(0x0B1A30)));
		g.fillRect(0, (int) (n * 0.62f), n, n);
		g.setColor(new Color(255, 214, 160, 140));
		g.setStroke(new BasicStroke(3));
		for (int i = 0; i < 7; i++) {
			int y = (int) (n * 0.66f) + i * 14, w = 120 - i * 12;
			g.drawLine(n / 2 - w / 2, y, n / 2 + w / 2, y);
		}
		g.dispose();
		return img;
	}

	private static void log(String s) {
		LyricraftClient.LOG.info("[lyricraft-gallery] {}", s);
	}
}
