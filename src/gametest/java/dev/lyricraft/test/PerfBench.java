package dev.lyricraft.test;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.hud.LyricsHud;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Frame-time benchmark. Only runs with LYRICRAFT_PERF=1. Plays the demo song through every
 * style, vibe level and overlay and logs the FPS and how long the HUD takes to build per frame.
 */
public final class PerfBench implements FabricClientGameTest {

	private static final int TICKS = 140;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"1".equals(System.getenv("LYRICRAFT_PERF"))) return;
		LyricraftClient mod = LyricraftClient.get();
		Config cfg = mod.config;
		Lyrics lyrics = Lyrics.parseLrc(GalleryShots.LRC, GalleryShots.DURATION);

		ctx.getInput().resizeWindow(1920, 1080);
		try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(s -> s.setSeed("lyricraft")).create()) {
			world.getConnection().waitForChunksRender();
			var server = world.getServer();
			server.runCommand("time set 6000");
			server.runCommand("weather clear 100000");
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(3);
				mc.options.framerateLimit().set(260); // unlimited
				mc.options.enableVsync().set(false);
				mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED); // don't drop to 30 when "idle"
				mc.resizeGui();
			});
			CoverArt.showDemo(track(0).trackKey(), GalleryShots.cover());

			List<String> report = new ArrayList<>();
			Consumer<Config> base = c -> {
				c.enabled = true;
				c.showNowPlaying = true;
				c.overlay = Config.Overlay.NONE;
				c.vibe = Config.Vibe.OFF;
				c.online = false;
				c.style = Config.Style.CLASSIC;
			};

			run(ctx, mod, lyrics, report, "hud off", c -> {
				base.accept(c);
				c.enabled = false;
				c.showNowPlaying = false;
			}, false);
			run(ctx, mod, lyrics, report, "card only", c -> {
				base.accept(c);
				c.enabled = false;
			}, false);
			for (Config.Style st : Config.Style.values()) {
				run(ctx, mod, lyrics, report, "style " + st, c -> {
					base.accept(c);
					c.style = st;
				}, st == Config.Style.ECHOES);
			}
			run(ctx, mod, lyrics, report, "echoes + vibe full", c -> {
				base.accept(c);
				c.style = Config.Style.ECHOES;
				c.vibe = Config.Vibe.FULL;
			}, true);
			run(ctx, mod, lyrics, report, "echoes + 12 friends", c -> {
				base.accept(c);
				c.style = Config.Style.ECHOES;
				c.showOthersEchoes = true;
			}, true);
			run(ctx, mod, lyrics, report, "vibe subtle", c -> {
				base.accept(c);
				c.vibe = Config.Vibe.SUBTLE;
			}, false);
			run(ctx, mod, lyrics, report, "vibe full", c -> {
				base.accept(c);
				c.vibe = Config.Vibe.FULL;
			}, false);
			for (Config.Overlay o : Config.Overlay.values()) {
				if (o == Config.Overlay.NONE) continue;
				run(ctx, mod, lyrics, report, "overlay " + o, c -> {
					base.accept(c);
					c.overlay = o;
				}, false);
			}
			log("================ RESULTS ================");
			report.forEach(PerfBench::log);
			ctx.runOnClient(mc -> {
				mod.showDemo(null, null);
				CoverArt.showDemo(null, null);
			});
		}
	}

	private static void run(ClientGameTestContext ctx, LyricraftClient mod, Lyrics lyrics, List<String> report,
	                        String name, Consumer<Config> setup, boolean echoes) {
		ctx.runOnClient(mc -> setup.accept(mod.config));
		boolean friends = name.contains("friends");
		if (echoes) {
			// walk through a few lines while turning so there's a full set of panels
			for (int k = 0; k < 7; k++) {
				int kk = k;
				ctx.runOnClient(mc -> {
					mc.player.setYRot(-50 + kk * 16);
					mc.player.setXRot(kk % 2 == 0 ? -6 : 4);
					mod.showDemo(track(lyrics.lines.get(kk).startMs() - 100), lyrics);
				});
				ctx.waitTicks(6);
			}
			ctx.runOnClient(mc -> mc.player.setYRot(0));
		}
		if (friends) {
			ctx.runOnClient(mc -> {
				var on = mod.online;
				var look = mc.player.getLookAngle();
				String dim = mc.level.dimension().identifier().toString();
				for (int f = 0; f < 4; f++)
					for (int i = 0; i < 3; i++)
						on.addDemoEcho(new java.util.UUID(0xbe, f * 10 + i), mc.player.getX() + look.x * 6 + (f - 1.5) * 2.5,
								mc.player.getEyeY() + (i - 1) * 0.8, mc.player.getZ() + look.z * 6 + i, dim,
								"friends are singing along here", 4000, 500L * i, 3f, 0.4f);
			});
		}
		// the current line half way through, so words are popping
		ctx.runOnClient(mc -> mod.showDemo(track(lyrics.lines.get(echoes ? 6 : 1).startMs() + 900), lyrics));
		ctx.waitTicks(20);
		long[] start = ctx.computeOnClient(mc -> new long[]{LyricsHud.PERF[0], LyricsHud.PERF[1]});
		int samples = 0;
		long fpsSum = 0;
		for (int t = 0; t < TICKS; t += 20) {
			ctx.waitTicks(20);
			fpsSum += ctx.computeOnClient(mc -> mc.getFps());
			samples++;
		}
		long[] end = ctx.computeOnClient(mc -> new long[]{LyricsHud.PERF[0], LyricsHud.PERF[1]});
		long frames = Math.max(1, end[1] - start[1]);
		double hudMs = (end[0] - start[0]) / 1e6 / frames;
		String line = String.format(java.util.Locale.ROOT, "%-26s fps %5d   hud %.3f ms/frame", name, fpsSum / samples, hudMs);
		report.add(line);
		log(line);
	}

	private static dev.lyricraft.media.MediaSnapshot track(long posMs) {
		return new dev.lyricraft.media.MediaSnapshot("Spotify.exe", GalleryShots.TITLE, GalleryShots.ARTIST, "Harbor Lights",
				posMs, GalleryShots.DURATION, true, System.nanoTime());
	}

	private static void log(String s) {
		LyricraftClient.LOG.info("[lyricraft-perf] {}", s);
	}
}
