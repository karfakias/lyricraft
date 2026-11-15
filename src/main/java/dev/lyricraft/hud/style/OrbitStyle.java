package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.lyrics.Lyrics;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * ORBIT: the words of the current line float in a slowly spinning ring around you.
 * Each word lights up and pops as it's sung; finished lines get flung outward and fade.
 */
public final class OrbitStyle implements LyricStyle {

	private static final double RADIUS = 3.3;
	private static final float WORD_BLOCKS = 0.34f;

	private final LineClock clock = new LineClock();
	private float ringYaw = Float.NaN;
	private float targetYaw;
	private float prevRingYaw;

	private record Item(String text, float x, float y, float scale, int rgb, float alpha, float glow, float depth) {}

	@Override
	public void render(Frame f) {
		Projector p = f.proj();
		if (!p.valid()) return;

		int slot = f.slot();
		if (clock.update(slot, f.nowNanos())) {
			prevRingYaw = Float.isNaN(ringYaw) ? p.yawDeg() : ringYaw;
			targetYaw = p.yawDeg(); // each new line swings round to face you
			if (Float.isNaN(ringYaw)) ringYaw = targetYaw;
		}
		ringYaw += wrap(targetYaw - ringYaw) * Math.min(1f, f.dt() * 3.5f);
		float spin = f.time() * 5f; // slow constant spin
		float age = clock.age(f.nowNanos());

		List<Item> items = new ArrayList<>();

		// Previous line: flung outward and fading
		Lyrics.Line prev = f.line(clock.previous());
		if (prev != null && age < 0.7f) {
			float t = age / 0.7f;
			addRing(f, items, prev, 1f, prevRingYaw + spin, RADIUS + Draw.easeOutCubic(t) * 3.5, f.vis() * (1f - t), true);
		}

		Lyrics.Line line = f.line(slot);
		if (line != null) {
			boolean inBreak = f.active() < 0;
			float enter = Draw.easeOutCubic(age / 0.5f);
			float alpha = f.vis() * enter * (inBreak ? 0.35f : 1f);
			float progress = inBreak ? 0f : line.progress(f.posMs());
			addRing(f, items, line, progress, ringYaw + spin, RADIUS + (1f - enter) * 1.5, alpha, false);
		}

		// far words first so near ones draw on top
		items.sort((a, b) -> Float.compare(b.depth, a.depth));
		for (Item it : items) {
			Draw.glowText(f.g(), f.font(), it.text, it.x, it.y, it.scale, it.rgb, it.alpha, it.glow);
		}
	}

	private void addRing(Frame f, List<Item> out, Lyrics.Line line, float progress, float centerYaw,
	                     double radius, float alpha, boolean finished) {
		if (alpha < 0.02f) return;
		List<Lyrics.Word> words = line.words();
		int n = words.size();
		if (n == 0) return;
		float spacing = Math.clamp(230f / n, 13f, 30f);
		float t = f.time();

		for (int i = 0; i < n; i++) {
			Lyrics.Word w = words.get(i);
			float angle = centerYaw + (i - (n - 1) / 2f) * spacing;
			Vec3 dir = Projector.yawDir(angle);
			double bob = Math.sin(t * 1.8 + i * 0.7) * 0.12;

			boolean reached = finished || progress >= w.start();
			boolean current = !finished && reached && progress < w.end();
			float wordAgeMs = (progress - w.start()) * line.durationMs();
			float pop = reached && !finished ? Draw.easeOutBack(wordAgeMs / 260f) : 1f;
			double lift = current ? 0.12 : 0;

			Projector.Point pt = f.proj().projectRelative(dir.x * radius, 0.25 + bob + lift, dir.z * radius);
			if (pt == null || pt.pxPerBlock() <= 0) continue;
			float scale = pt.pxPerBlock() * WORD_BLOCKS * f.cfg().scale / f.font().lineHeight;
			if (reached) scale *= 0.85f + 0.15f * pop + (current ? 0.12f : 0f);
			float ww = f.font().width(w.text()) * scale;
			if (!f.proj().onScreen(pt, ww)) continue;

			int rgb = reached ? f.accent() : 0xFFFFFF;
			float a = alpha * (reached ? (current ? 1f : 0.85f) : 0.45f);
			float glow = current ? 1f : (reached ? 0.35f : 0f);
			out.add(new Item(w.text(), pt.x() - ww / 2f, pt.y() - f.font().lineHeight * scale / 2f,
					scale, rgb, a, glow, pt.depth()));
		}
	}

	private static float wrap(float deg) {
		deg %= 360f;
		if (deg > 180f) deg -= 360f;
		if (deg < -180f) deg += 360f;
		return deg;
	}
}
