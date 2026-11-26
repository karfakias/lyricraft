package dev.lyricraft.test;

import dev.lyricraft.Config;
import dev.lyricraft.LyricraftClient;
import dev.lyricraft.lyrics.Lyrics;
import dev.lyricraft.media.CoverArt;
import dev.lyricraft.media.MediaSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Lyrics in other languages, to check the letters come out right: Greek, Korean, Japanese,
 * Chinese, Arabic, Hebrew, Hindi, Thai and Russian, in the Edit style (with real fonts) and
 * Arabic / Hebrew in the classic style too. Only runs with LYRICRAFT_LANGS=1.
 * The lines are made up for the test (no real lyrics).
 */
public final class LangShots implements FabricClientGameTest {
	private static final long DURATION = 60_000;

	/** name, a made-up line in that language. */
	private static final String[][] LINES = {
			{"greek", "Η νύχτα λάμπει πάνω στη θάλασσα"},
			{"korean", "오늘 밤 별빛이 우리를 비춰"},
			{"japanese", "夜空に光る星をずっと見ていた"},
			{"chinese", "我们在雨中一起唱着这首歌"},
			{"arabic", "نحن نغني تحت ضوء القمر"},
			{"hebrew", "אנחנו שרים מתחת לירח"},
			{"hindi", "हम चाँद के नीचे गाते हैं"},
			{"thai", "เราร้องเพลงใต้แสงจันทร์ด้วยกัน"},
			{"russian", "Мы поём под светом луны"},
	};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!"1".equals(System.getenv("LYRICRAFT_LANGS"))) return;
		LyricraftClient mod = LyricraftClient.get();
		Config cfg = mod.config;
		ctx.getInput().resizeWindow(1600, 900);
		try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(s -> s.setSeed("lyricraft")).create()) {
			world.getConnection().waitForChunksRender();
			TestServerContext server = world.getServer();
			server.runCommand("time set 6000");
			server.runCommand("weather clear 100000");
			int[] at = ctx.computeOnClient(mc -> new int[]{mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()});
			int x = at[0], y = at[1], z = at[2];
			server.runCommand(String.format("fill %d %d %d %d %d %d air", x - 10, y, z - 14, x + 10, y + 10, z + 4));
			server.runCommand(String.format("fill %d %d %d %d %d %d grass_block", x - 10, y - 1, z - 14, x + 10, y - 1, z + 4));
			server.runCommand(String.format("fill %d %d %d %d %d %d stone_bricks", x - 9, y, z - 10, x + 9, y + 8, z - 10));
			server.runCommand(String.format("tp @a %d.5 %d %d.5 180 -6", x, y, z));
			ctx.waitTicks(20);
			world.getConnection().waitForChunksRender();
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(3);
				mc.options.bobView().set(false);
				mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
				mc.resizeGui();
				cfg.enabled = true;
				cfg.showNowPlaying = true;
				cfg.overlay = Config.Overlay.NONE;
				cfg.vibe = Config.Vibe.OFF;
				cfg.online = false;
				cfg.editEffects = true;
				cfg.editCamera = false;
				cfg.editAngles = false;
			});

			for (String[] l : LINES) {
				Lyrics lyrics = Lyrics.parseLrc("[00:02.00]" + l[1] + "\n[00:06.00]" + l[1] + "\n[00:10.00]", DURATION);
				// a popular song, so its words get a real font (Dynamite: the heavy pop one)
				MediaSnapshot song = track("Dynamite", "BTS", 2000 + 2600);
				CoverArt.showDemo(song.trackKey(), GalleryShots.cover());
				ctx.runOnClient(mc -> {
					cfg.style = Config.Style.EDIT;
					mod.showDemo(null, null);
				});
				ctx.waitTicks(2);
				ctx.runOnClient(mc -> mod.showDemo(track("Dynamite", "BTS", 1950), lyrics));
				ctx.waitTicks(70);
				ctx.takeScreenshot("lang-edit-" + l[0]);
				if (l[0].equals("arabic") || l[0].equals("hebrew") || l[0].equals("greek")) {
					ctx.runOnClient(mc -> {
						cfg.style = Config.Style.CLASSIC;
						mod.showDemo(track("Some Song", "Someone", 3500), lyrics);
					});
					ctx.waitTicks(20);
					ctx.takeScreenshot("lang-classic-" + l[0]);
				}
			}
			ctx.runOnClient(mc -> {
				mod.showDemo(null, null);
				CoverArt.showDemo(null, null);
			});
		}
	}

	private static MediaSnapshot track(String title, String artist, long posMs) {
		return new MediaSnapshot("Spotify.exe", title, artist, "Demo", posMs, DURATION, true, System.nanoTime());
	}
}
