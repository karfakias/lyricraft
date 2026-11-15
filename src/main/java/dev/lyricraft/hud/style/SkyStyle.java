package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.lyrics.Lyrics;
import net.minecraft.world.phys.Vec3;

/**
 * SKY: giant glowing lyrics written across the sky, far away like the sun or moon.
 * They lazily drift to wherever you're looking, finished lines float up and dissolve,
 * and the next line waits faintly underneath.
 */
public final class SkyStyle implements LyricStyle {

	private static final double DISTANCE = 64;
	private static final double HEIGHT = 24;
	private static final float TEXT_BLOCKS = 5.5f;   // letter height in blocks
	private static final float MAX_WIDTH_BLOCKS = 95f;

	private final LineClock clock = new LineClock();
	private float yaw = Float.NaN;

	@Override
	public void render(Frame f) {
		Projector p = f.proj();
		if (!p.valid()) return;

		// The sky text follows your view, but slowly, like a cloud catching up
		float target = p.yawDeg();
		if (Float.isNaN(yaw)) yaw = target;
		float diff = wrap(target - yaw);
		yaw += diff * Math.min(1f, f.dt() * 0.9f);

		int slot = f.slot();
		clock.update(slot, f.nowNanos());
		float age = clock.age(f.nowNanos());
		Vec3 dir = Projector.yawDir(yaw);

		// Previous line: floats up into the sky and dissolves
		Lyrics.Line prev = f.line(clock.previous());
		if (prev != null && age < 1.6f) {
			float t = age / 1.6f;
			drawSkyLine(f, prev.text(), dir, HEIGHT + 4 + Draw.easeOutCubic(t) * 16, 1f, f.vis() * (1f - t), 1.0f, 0.9f);
		}

		Lyrics.Line line = f.line(slot);
		if (line == null) return;
		boolean inBreak = f.active() < 0;
		float enter = Draw.easeOutCubic(age / 0.6f);
		float alpha = f.vis() * enter;
		if (inBreak) {
			long until = line.startMs() - f.posMs();
			alpha *= 0.2f + 0.45f * (1f - Math.clamp(until / 2500f, 0f, 1f));
		}
		float progress = inBreak ? 0f : line.progress(f.posMs());
		// rises a little into place as it appears
		drawSkyLine(f, line.text(), dir, HEIGHT - (1f - enter) * 6, progress, alpha, 1.0f, 1.0f);

		Lyrics.Line next = f.line(slot + 1);
		if (next != null && f.cfg().showContextLines && !inBreak) {
			drawSkyLine(f, next.text(), dir, HEIGHT - TEXT_BLOCKS * 1.6, 0f, f.vis() * 0.28f * enter, 0.55f, 0f);
		}
	}

	private void drawSkyLine(Frame f, String text, Vec3 dir, double height, float progress, float alpha,
	                         float sizeMul, float glow) {
		if (alpha < 0.02f) return;
		Projector.Point pt = f.proj().projectRelative(dir.x * DISTANCE, height, dir.z * DISTANCE);
		if (pt == null || pt.pxPerBlock() <= 0) return;

		int w = f.font().width(text);
		float blocks = TEXT_BLOCKS * sizeMul * f.cfg().scale;
		float widthBlocks = w * blocks / f.font().lineHeight;
		if (widthBlocks > MAX_WIDTH_BLOCKS) blocks *= MAX_WIDTH_BLOCKS / widthBlocks;
		float scale = pt.pxPerBlock() * blocks / f.font().lineHeight;

		float drawW = w * scale;
		if (!f.proj().onScreen(pt, drawW)) return;
		float x = pt.x() - drawW / 2f;
		float y = pt.y() - f.font().lineHeight * scale / 2f;
		// gentle shimmer while it's being sung
		float shimmer = progress > 0f && progress < 1f ? 0.85f + 0.15f * (float) Math.sin(f.time() * 6) : 1f;
		Draw.karaoke(f.g(), f.font(), text, x, y, scale, progress, alpha * shimmer, f.accent(),
				0xFFFFFF, 0.45f, false, glow);
	}

	private static float wrap(float deg) {
		deg %= 360f;
		if (deg > 180f) deg -= 360f;
		if (deg < -180f) deg += 360f;
		return deg;
	}

	@Override
	public boolean showsNowPlayingCard() {
		return true;
	}
}
