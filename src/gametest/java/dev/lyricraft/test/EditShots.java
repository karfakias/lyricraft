package dev.lyricraft.test;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import dev.lyricraft.media.MediaSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Screenshots of the edit effects, one per style. Only runs with LYRICRAFT_EDITS=1.
 * Uses the made-up demo lyrics under the names of songs on the list (no real lyrics).
 */
public final class EditShots implements FabricClientGameTest {

	private static final String[][] SONGS = {
			{"Believer", "Imagine Dragons", "hype"},
			{"Starboy", "The Weeknd", "dark"},
			{"Another Love", "Tom Odell", "sad"},
			{"Die With A Smile", "Lady Gaga, Bruno Mars", "love"},
			{"Espresso", "Sabrina Carpenter", "pop"},
			{"Blinding Lights", "The Weeknd", "retro"},
	};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"1".equals(System.getenv("LYRICRAFT_EDITS"))) return;
		LyricraftClient mod = LyricraftClient.get();
		Config cfg = mod.config;
		Lyrics lyrics = Lyrics.parseLrc(GalleryShots.LRC, GalleryShots.DURATION);
		ctx.getInput().resizeWindow(1600, 900);
		try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(s -> s.setSeed("lyricraft")).create()) {
			world.getConnection().waitForChunksRender();
			var server = world.getServer();
			server.runCommand("time set 13000");
			server.runCommand("weather clear 100000");
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(3);
				mc.options.bobView().set(false);
				mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
				mc.resizeGui();
				cfg.enabled = true;
				cfg.showNowPlaying = true;
				cfg.cardX = 0f;
				cfg.cardY = 0f;
				cfg.overlay = Config.Overlay.NONE;
				cfg.vibe = Config.Vibe.SUBTLE;
				cfg.online = false;
				cfg.style = Config.Style.ECHOES;
				cfg.editEffects = true;
			});
			for (String[] song : SONGS) {
				CoverArt.showDemo(track(song, 0).trackKey(), GalleryShots.cover());
				// title card at the start
				ctx.runOnClient(mc -> mod.showDemo(track(song, 6500), lyrics));
				ctx.waitTicks(12);
				if (song[2].equals("hype")) ctx.takeScreenshot("edit-intro-" + song[2]);
				// a few lines while turning, then the first chorus (the drop)
				for (int k = 0; k < 5; k++) {
					int kk = k;
					ctx.runOnClient(mc -> {
						mc.player.setYRot(-40 + kk * 16);
						mc.player.setXRot(kk % 2 == 0 ? -6 : 3);
						mod.showDemo(track(song, lyrics.lines.get(kk).startMs() - 100), lyrics);
					});
					ctx.waitTicks(5);
				}
				ctx.runOnClient(mc -> {
					mc.player.setYRot(30);
					mod.showDemo(track(song, lyrics.lines.get(5).startMs() + 250), lyrics);
				});
				ctx.waitTicks(3);
				ctx.takeScreenshot("edit-drop-" + song[2]);
				ctx.waitTicks(30);
				ctx.takeScreenshot("edit-chorus-" + song[2]);
				ctx.runOnClient(mc -> mod.showDemo(null, null));
				ctx.waitTicks(5);
			}
			ctx.runOnClient(mc -> CoverArt.showDemo(null, null));
		}
	}

	private static MediaSnapshot track(String[] song, long posMs) {
		return new MediaSnapshot("Spotify.exe", song[0], song[1], "Demo", posMs, GalleryShots.DURATION, true, System.nanoTime());
	}
}
