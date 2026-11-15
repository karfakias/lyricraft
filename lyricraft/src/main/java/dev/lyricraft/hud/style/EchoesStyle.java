package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.HudSpace;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.lyrics.Lyrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ECHOES: every new line materialises as a hologram right where you're looking and then
 * <i>stays pinned in the world</i>, projected from a little light beam. Look around and the
 * song's last lines are still floating where you left them, linked by a faint trail.
 *
 * New lines never land on top of older ones (or the Now Playing card, the hotbar or toasts):
 * if the spot in front of you is taken, the line picks the nearest free spot instead.
 */
public final class EchoesStyle implements LyricStyle {

	private static final float DISTANCE = 3.4f;
	private static final float TEXT_BLOCKS = 0.16f;
	private static final int MAX_PANELS = 7;
	private static final float LIFETIME_S = 60f;
	private static final float FADE_OUT_S = 3f;
	private static final double MAX_DIST = 40;
	private static final float PAD = 10f; // text padding inside the panel, in font pixels

	private static final class Panel {
		final int index;
		final Vec3 pos;
		final long born;
		final float wBlocks, hBlocks;
		final float seed;
		long diedAt = -1;
		float coverFade = 1f;

		Panel(int index, Vec3 pos, long born, float wBlocks, float hBlocks) {
			this.index = index;
			this.pos = pos;
			this.born = born;
			this.wBlocks = wBlocks;
			this.hBlocks = hBlocks;
			this.seed = (index * 0.618f) % 1f;
		}

		float age(long now) {
			return (now - born) / 1e9f;
		}

		boolean alive() {
			return diedAt < 0;
		}
	}

	/** Where a panel lands on screen this frame. */
	private record Rect(float x0, float y0, float x1, float y1, float cx, float cy, float ppb) {
		boolean hits(Rect o, float gap) {
			return x0 - gap < o.x1 && x1 + gap > o.x0 && y0 - gap < o.y1 && y1 + gap > o.y0;
		}
	}

	private final List<Panel> panels = new ArrayList<>();
	private Lyrics forLyrics;
	private Object forLevel;
	private int lastSlot = Integer.MIN_VALUE;
	private float glitch;

	@Override
	public void render(Frame f) {
		Projector p = f.proj();
		if (!p.valid()) return;
		long now = f.nowNanos();

		// new song / new world / seek backwards: start fresh
		Object level = Minecraft.getInstance().level;
		int slot = f.slot();
		if (f.lyrics() != forLyrics || level != forLevel || slot < lastSlot - 1) {
			panels.clear();
			forLyrics = f.lyrics();
			forLevel = level;
			lastSlot = Integer.MIN_VALUE;
		}

		if (slot != lastSlot && f.line(slot) != null) {
			lastSlot = slot;
			spawn(f, p, slot, now);
		}

		// expire old panels
		Vec3 cam = p.cameraPos();
		for (Panel pn : panels) {
			if (pn.alive() && (pn.age(now) > LIFETIME_S || pn.pos.distanceTo(cam) > MAX_DIST)) pn.diedAt = now;
		}
		long alive = panels.stream().filter(Panel::alive).count();
		for (Panel pn : panels) {
			if (alive <= MAX_PANELS) break;
			if (pn.alive()) {
				pn.diedAt = now;
				alive--;
			}
		}
		panels.removeIf(pn -> !pn.alive() && (now - pn.diedAt) / 1e9f > FADE_OUT_S);

		if (Math.random() < f.dt() * 0.35) glitch = 1f;
		glitch = Draw.approach(glitch, 0f, f.dt() * 6f);

		Panel newest = null;
		for (Panel pn : panels) if (pn.alive()) newest = pn;

		drawTrail(f, now);

		// far ones first so near ones draw on top
		List<Panel> order = new ArrayList<>(panels);
		order.sort(Comparator.comparingDouble((Panel pn) -> -pn.pos.distanceToSqr(cam)));
		for (Panel pn : order) draw(f, pn, pn == newest, now);
	}

	// ------------------------------------------------------------------ placement

	private void spawn(Frame f, Projector p, int slot, long now) {
		Lyrics.Line line = f.line(slot);
		int textW = Math.max(f.font().width(line.text()), 40);
		float k = TEXT_BLOCKS * f.cfg().scale / f.font().lineHeight;
		float wBlocks = (textW + PAD * 2) * k;
		float hBlocks = f.font().lineHeight * 2.3f * k;

		Vector3f fw = p.forward();
		fw.y = Math.clamp(fw.y, -0.45f, 0.45f); // keep it near eye height so it stays readable
		fw.normalize();
		Vector3f right = p.left().negate();
		Vector3f up = new Vector3f(right).cross(fw).normalize();
		if (up.y < 0) up.negate();
		Vec3 base = p.cameraPos().add(fw.x * DISTANCE, fw.y * DISTANCE - 0.1, fw.z * DISTANCE);

		// candidate spots: straight ahead, then above/below, then to the sides, then diagonals
		float stepY = hBlocks + 0.18f, stepX = wBlocks * 0.5f + 0.4f;
		float[][] offsets = {
				{0, 0}, {0, 1}, {0, -1}, {0, 2}, {0, -2},
				{1, 0}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
				{2, 0}, {-2, 0}, {0, 3}, {2, 1}, {-2, 1}, {2, -1}, {-2, -1},
		};
		List<Rect> taken = new ArrayList<>();
		for (Panel pn : panels) {
			if (!pn.alive()) continue;
			Rect r = rectOf(p, pn.pos, pn.wBlocks, pn.hBlocks);
			if (r != null) taken.add(r);
		}

		Vec3 chosen = null;
		for (float[] o : offsets) {
			Vec3 pos = base.add(right.x * o[0] * stepX + up.x * o[1] * stepY,
					right.y * o[0] * stepX + up.y * o[1] * stepY,
					right.z * o[0] * stepX + up.z * o[1] * stepY);
			Rect r = rectOf(p, pos, wBlocks, hBlocks);
			if (r == null || !p.onScreen(new Projector.Point(r.cx, r.cy, r.ppb, 1), -10)) continue;
			if (HudSpace.overlaps(r.x0, r.y0, r.x1, r.y1)) continue;
			boolean free = true;
			for (Rect t : taken) {
				if (r.hits(t, 4)) {
					free = false;
					break;
				}
			}
			if (free) {
				chosen = pos;
				break;
			}
		}
		if (chosen == null) {
			// everything around you is full: retire the echoes that are in the way and use the middle
			chosen = base;
			Rect r = rectOf(p, base, wBlocks, hBlocks);
			for (Panel pn : panels) {
				if (!pn.alive()) continue;
				Rect t = rectOf(p, pn.pos, pn.wBlocks, pn.hBlocks);
				if (r == null || t == null || r.hits(t, 4)) pn.diedAt = now - 2_000_000_000L; // quick fade
			}
		}
		panels.add(new Panel(slot, chosen, now, wBlocks, hBlocks));
	}

	private static Rect rectOf(Projector p, Vec3 pos, float wBlocks, float hBlocks) {
		Projector.Point pt = p.project(pos);
		if (pt == null || pt.pxPerBlock() <= 0) return null;
		float hw = wBlocks * pt.pxPerBlock() / 2f, hh = hBlocks * pt.pxPerBlock() / 2f;
		return new Rect(pt.x() - hw, pt.y() - hh, pt.x() + hw, pt.y() + hh, pt.x(), pt.y(), pt.pxPerBlock());
	}

	// ------------------------------------------------------------------ drawing

	/** Faint dotted line connecting the echoes in the order they were sung. */
	private void drawTrail(Frame f, long now) {
		Projector.Point prev = null;
		float prevVis = 0;
		for (Panel pn : panels) {
			Projector.Point pt = f.proj().project(pn.pos);
			float vis = fade(pn, now) * f.vis();
			if (pt != null && prev != null && pt.pxPerBlock() > 0) {
				float a = Math.min(vis, prevVis) * 0.35f;
				float dx = pt.x() - prev.x(), dy = pt.y() - prev.y();
				float len = (float) Math.sqrt(dx * dx + dy * dy);
				int dots = Math.min(60, (int) (len / 6));
				float shift = (f.time() * 0.8f) % 1f;
				for (int i = 0; i < dots; i++) {
					float u = (i + shift) / dots;
					float x = prev.x() + dx * u, y = prev.y() + dy * u;
					f.g().fill(Math.round(x), Math.round(y), Math.round(x) + 1, Math.round(y) + 1,
							Draw.argb(Draw.mix(f.colorA(), f.colorB(), u), a));
				}
			}
			prev = pt;
			prevVis = vis;
		}
	}

	private static float fade(Panel pn, long now) {
		float age = pn.age(now);
		float in = Math.clamp(age / 0.25f, 0f, 1f);
		float out = pn.alive() ? 1f : 1f - Math.clamp((now - pn.diedAt) / 1e9f / FADE_OUT_S, 0f, 1f);
		return in * out;
	}

	private void draw(Frame f, Panel pn, boolean current, long now) {
		Projector.Point pt = f.proj().project(pn.pos);
		if (pt == null || pt.pxPerBlock() <= 0 || !f.proj().onScreen(pt, 250)) return;
		Lyrics.Line line = f.line(pn.index);
		if (line == null) return;
		GuiGraphicsExtractor g = f.g();

		float age = pn.age(now);
		float dying = pn.alive() ? 0f : Math.clamp((now - pn.diedAt) / 1e9f / FADE_OUT_S, 0f, 1f);
		float dim = current ? 1f : Math.max(0.28f, 0.66f - age / LIFETIME_S * 0.4f);
		float vis = f.vis() * fade(pn, now) * dim;

		float scale = pt.pxPerBlock() * TEXT_BLOCKS * f.cfg().scale / f.font().lineHeight;
		scale = Math.min(scale, 4f);
		String text = line.text();
		int textW = Math.max(f.font().width(text), 40);
		scale = Draw.fitScale(textW, scale, f.width() * 0.85f);
		if (scale < 0.15f) return;

		// open like an old CRT: a bright line that unfolds vertically; close the same way
		float open = Draw.easeOutBack(Math.clamp(age / 0.4f, 0f, 1f)) * (1f - Draw.easeOutCubic(dying));
		float lh = f.font().lineHeight * scale;
		float panelW = (textW + PAD * 2) * scale * Math.clamp(age / 0.18f, 0.08f, 1f);
		float panelH = lh * 2.3f * Math.max(0.04f, open);
		float bob = (float) Math.sin(f.time() * 1.2f + pn.seed * 6.28f) * 1.6f * scale;
		float x0 = pt.x() - panelW / 2f, y0 = pt.y() - panelH / 2f + bob;
		int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x0 + panelW), iy1 = Math.round(y0 + panelH);

		// echoes that drift behind the Now Playing card / hotbar get see-through instead of covering them
		float covered = HudSpace.covered(ix0, iy0, ix1, iy1);
		pn.coverFade = Draw.approach(pn.coverFade, 1f - covered * 0.8f, f.dt() * 4f);
		vis *= pn.coverFade;
		if (vis < 0.02f) return;

		int acc = current ? f.accent() : Draw.mix(f.accent(), f.colorB(), 0.55f);
		int hi = Draw.mix(acc, 0xFFFFFF, 0.55f);

		// projector beam from the floor up to the panel
		float beamTop = iy1, beamBottom = beamTop + 0.9f * pt.pxPerBlock();
		float beamHalf = Math.max(2f, panelW * 0.18f);
		for (int i = 0; i < 6; i++) {
			float u = i / 6f, w = beamHalf * (1f - u * 0.85f);
			g.fillGradient(Math.round(pt.x() - w), Math.round(beamTop), Math.round(pt.x() + w), Math.round(beamBottom),
					Draw.argb(acc, 0.05f * vis), Draw.argb(acc, 0f));
		}
		Draw.circle(g, pt.x(), beamBottom, Math.max(1.5f, beamHalf * 0.25f), Draw.argb(hi, 0.35f * vis));

		// glass body with a vertical gradient, scanlines and a moving sheen
		g.fillGradient(ix0, iy0, ix1, iy1, Draw.argb(Draw.mix(acc, 0x020806, 0.82f), 0.62f * vis),
				Draw.argb(0x020806, 0.38f * vis));
		int scan = (int) (f.time() * 14) % 3;
		for (int y = iy0 + scan; y < iy1; y += 3) g.fill(ix0, y, ix1, y + 1, Draw.argb(acc, 0.045f * vis));
		float sheenU = ((f.time() * 0.35f + pn.seed) % 1.6f) / 1.6f;
		int sx = Math.round(x0 + panelW * (sheenU * 1.4f - 0.2f));
		g.enableScissor(ix0, iy0, ix1, iy1);
		for (int i = 0; i < 6; i++) g.fill(sx + i * 2 - 6, iy0, sx + i * 2 - 4, iy1, Draw.argb(0xFFFFFF, 0.025f * vis * (6 - i)));
		g.disableScissor();

		// edges: bright top/bottom lines that grow from the centre + corner brackets
		float grow = Draw.easeOutCubic(Math.clamp(age / 0.5f, 0f, 1f));
		int cxi = Math.round(pt.x()), half = Math.round(panelW / 2f * grow);
		g.fill(cxi - half, iy0, cxi + half, iy0 + 1, Draw.argb(hi, 0.85f * vis));
		g.fill(cxi - half, iy1 - 1, cxi + half, iy1, Draw.argb(acc, 0.6f * vis));
		int c = Math.max(3, Math.round(6 * scale));
		bracket(g, ix0, iy0, c, 1, 1, vis);
		bracket(g, ix1, iy0, c, -1, 1, vis);
		bracket(g, ix0, iy1, c, 1, -1, vis);
		bracket(g, ix1, iy1, c, -1, -1, vis);

		// spawn flash
		if (age < 0.5f) {
			float fl = 1f - age / 0.5f;
			g.fill(ix0 - 3, iy0 - 3, ix1 + 3, iy1 + 3, Draw.argb(hi, 0.18f * fl * fl * f.vis()));
		}
		if (open < 0.6f) return;

		// tiny timestamp tag on the top-left edge
		float tagScale = Math.max(0.35f, scale * 0.42f);
		Draw.text(g, f.font(), NowPlayingTime.of(line.startMs()), ix0 + 3 * scale, iy0 + 2 * scale, tagScale,
				Draw.argb(acc, 0.7f * vis), false);

		float tw = f.font().width(text) * scale;
		float tx = pt.x() - tw / 2f, ty = y0 + (panelH - lh) / 2f + scale * 1.5f;
		if (current) {
			boolean waiting = f.active() < 0 && line.startMs() - f.posMs() > 1800;
			if (waiting) {
				BreakDots.draw(f, pt.x(), ty, scale, Draw.Align.CENTER, vis);
			} else {
				// chromatic split that kicks in on each word and the odd glitch
				float split = (f.pulse() * 0.9f + glitch * 1.5f) * scale;
				if (split > 0.2f) {
					Draw.text(g, f.font(), text, tx - split, ty, scale, Draw.argb(0xFF3B6B, 0.28f * vis), false);
					Draw.text(g, f.font(), text, tx + split, ty, scale, Draw.argb(0x3BE8FF, 0.28f * vis), false);
				}
				float progress = f.active() == pn.index ? line.progress(f.posMs()) : 0f;
				Draw.karaoke(g, f.font(), text, tx, ty, scale, progress, vis, acc, 0xE4FFF0, 0.55f, false,
						0.8f + f.pulse() * 0.6f);
			}
		} else {
			Draw.text(g, f.font(), text, tx, ty, scale, Draw.argb(Draw.mix(0xE4FFF0, acc, 0.35f), vis), false);
		}
	}

	private static void bracket(GuiGraphicsExtractor g, int x, int y, int len, int dx, int dy, float vis) {
		int col = Draw.argb(0xFFFFFF, 0.85f * vis);
		int xs = dx > 0 ? x : x - len, ys = dy > 0 ? y : y - 2;
		g.fill(xs, ys, xs + len, ys + 2, col);
		int vx = dx > 0 ? x : x - 2, vy = dy > 0 ? y : y - len;
		g.fill(vx, vy, vx + 2, vy + len, col);
	}

	/** m:ss for the little timestamp tags. */
	private static final class NowPlayingTime {
		static String of(long ms) {
			long s = Math.max(0, ms) / 1000;
			return (s / 60) + ":" + String.format("%02d", s % 60);
		}
	}
}
