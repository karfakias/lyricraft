package dev.lyricraft.test;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import dev.lyricraft.media.MediaSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/**
 * Screenshots of the Edit style. Only runs with LYRICRAFT_EDITS=1.
 * Builds a wall with a log pillar in front of it, then plays the made-up demo lyrics under the
 * names of songs on the popular list (no real lyrics anywhere).
 */
public final class EditShots implements FabricClientGameTest {

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
			TestServerContext server = world.getServer();
			server.runCommand("time set 13200");
			server.runCommand("weather clear 100000");
			server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> p.setGameMode(net.minecraft.world.level.GameType.CREATIVE)));
			int[] at = ctx.computeOnClient(mc -> new int[]{mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()});
			int x = at[0], y = at[1], z = at[2];
			// a little stage: flat floor, clear air, a wall ahead and a log pillar in front of it
			server.runCommand(String.format("fill %d %d %d %d %d %d air", x - 10, y, z - 14, x + 10, y + 10, z + 4));
			server.runCommand(String.format("fill %d %d %d %d %d %d grass_block", x - 10, y - 1, z - 14, x + 10, y - 1, z + 4));
			server.runCommand(String.format("fill %d %d %d %d %d %d stone_bricks", x - 9, y, z - 10, x + 9, y + 8, z - 10));
			server.runCommand(String.format("fill %d %d %d %d %d %d oak_log", x + 2, y, z - 6, x + 2, y + 5, z - 6));
			server.runCommand(String.format("tp @a %d.5 %d %d.5 180 0", x, y, z));
			ctx.waitTicks(20);
			world.getConnection().waitForChunksRender();

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
				cfg.vibe = Config.Vibe.OFF;
				cfg.online = false;
				cfg.style = Config.Style.EDIT;
				cfg.editEffects = true;
				cfg.editCamera = true;
			});

			// 1) song title stamped on the wall before the first line, then the drop on the wall
			String[] hype = {"Believer", "Imagine Dragons"};
			CoverArt.showDemo(track(hype, 0).trackKey(), GalleryShots.cover());
			look(ctx, 180, 0);
			ctx.runOnClient(mc -> mod.showDemo(track(hype, 3000), lyrics));
			ctx.waitTicks(45);
			ctx.takeScreenshot("edit-wall-intro");
			for (int k = 0; k < 3; k++) {
				int kk = k;
				ctx.runOnClient(mc -> mod.showDemo(track(hype, lyrics.lines.get(kk).startMs() - 100), lyrics));
				ctx.waitTicks(4);
			}
			// keywords: "star" and "flame" in the same line
			int starLine = 0;
			while (!lyrics.lines.get(starLine).text().contains("star")) starLine++;
			int sl = starLine;
			ctx.runOnClient(mc -> mod.showDemo(track(hype, lyrics.lines.get(sl).startMs() - 60), lyrics));
			ctx.waitTicks(48);
			ctx.takeScreenshot("edit-keywords");
			look(ctx, 180, -4);
			ctx.waitTicks(30); // stand still a moment, so the chorus can cut to a cinematic angle
			// the first chorus line (it comes back at the end of the song): the drop
			int drop = 0;
			while (!lyrics.lines.get(drop).text().startsWith("Hold on")) drop++;
			int dropLine = drop;
			ctx.runOnClient(mc -> mod.showDemo(track(hype, lyrics.lines.get(dropLine).startMs() - 60), lyrics));
			ctx.waitTicks(5);
			ctx.takeScreenshot("edit-wall-drop");
			ctx.waitTicks(40);
			ctx.takeScreenshot("edit-wall-chorus");
			ctx.waitTicks(30);
			ctx.takeScreenshot("edit-angle-later");

			// 2) a love song, looking at the ground
			String[] love = {"Die With A Smile", "Lady Gaga, Bruno Mars"};
			CoverArt.showDemo(track(love, 0).trackKey(), GalleryShots.cover());
			look(ctx, 150, 48);
			ctx.runOnClient(mc -> mod.showDemo(track(love, lyrics.lines.get(6).startMs() - 60), lyrics));
			ctx.waitTicks(50);
			ctx.takeScreenshot("edit-ground-love");

			// 3) a song that isn't on the list, up in the sky
			String[] plain = {GalleryShots.TITLE, GalleryShots.ARTIST};
			CoverArt.showDemo(track(plain, 0).trackKey(), GalleryShots.cover());
			look(ctx, 0, -35);
			ctx.runOnClient(mc -> mod.showDemo(track(plain, lyrics.lines.get(8).startMs() - 60), lyrics));
			ctx.waitTicks(50);
			ctx.takeScreenshot("edit-sky-plain");

			ctx.runOnClient(mc -> {
				mod.showDemo(null, null);
				CoverArt.showDemo(null, null);
			});
		}
	}

	private static void look(ClientGameTestContext ctx, float yaw, float pitch) {
		ctx.runOnClient(mc -> {
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			mc.player.yRotO = yaw;
			mc.player.xRotO = pitch;
		});
		ctx.waitTicks(2);
	}

	private static MediaSnapshot track(String[] song, long posMs) {
		return new MediaSnapshot("Spotify.exe", song[0], song[1], "Demo", posMs, GalleryShots.DURATION, true, System.nanoTime());
	}
}
