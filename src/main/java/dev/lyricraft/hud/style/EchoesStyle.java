package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.GlassPanel;
import dev.lyricraft.hud.HudSpace;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.hud.Sprites;
import dev.lyricraft.lyrics.Lyrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * ECHOES: every new line opens as a frosted glass panel right where you're looking and then
 * <i>stays pinned in the world</i>. The words pop in one by one as they're sung, the panel
 * glows on every word and sheds little sparks, and once the next line arrives it dims and
 * slowly drifts upward. A glowing thread links the panels in the order they were sung.
 *
 * New lines never land on top of older ones (or the Now Playing card, the hotbar or toasts):
 * if the spot in front of you is taken, the line picks the nearest free spot instead.
 */
public final class EchoesStyle implements LyricStyle {

	private static final float DISTANCE = 3.4f;
	private static final float TEXT_BLOCKS = 0.16f;
	private static final int MAX_PANELS = 7;
	private static final float LIFETIME_S = 60f;
	private static final float FADE_OUT_S = 2.2f;
	private static final double MAX_DIST = 40;
	private static final float PAD = 10f; // text padding inside the panel, in font pixels
	/** How far an old panel floats up (blocks), and how long that takes. */
	private static final float DRIFT_BLOCKS = 0.3f, DRIFT_S = 6f;
	private static final int MAX_SPARKS = 48;

	private static final class Panel {
		final int index;
		final Vec3 pos;
		final long born;
		final float wBlocks, hBlocks;
		final float seed;
		long diedAt = -1;
		long retiredAt = -1; // when a newer line took over
		boolean finished;    // the line has been sung through
		long doneAt;         // when that happened (only set if we saw it happen)
		boolean sawDone;
		int lastWord = -1;
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

		/** Where the panel is now: old panels float up a little, dying ones a bit more. */
		Vec3 posNow(long now) {
			float lift = 0f;
			if (retiredAt >= 0) lift += DRIFT_BLOCKS * Draw.easeOutCubic(Math.clamp((now - retiredAt) / 1e9f / DRIFT_S, 0f, 1f));
			if (diedAt >= 0) lift += 0.35f * Math.clamp((now - diedAt) / 1e9f / FADE_OUT_S, 0f, 1f);
			return lift == 0f ? pos : pos.add(0, lift, 0);
		}
	}

	/** A little spark or note that drifts off the current panel. */
	private static final class Spark {
		Vec3 pos;
		final Vec3 vel;
		final float life;
		final boolean note;
		final float wobble;
		final boolean gold;
		float age;

		Spark(Vec3 pos, Vec3 vel, float life, boolean note, float wobble, boolean gold) {
			this.gold = gold;
			this.pos = pos;
			this.vel = vel;
			this.life = life;
			this.note = note;
			this.wobble = wobble;
		}
	}

	/** Where a panel lands on screen this frame. */
	private record Rect(float x0, float y0, float x1, float y1, float cx, float cy, float ppb) {
		boolean hits(Rect o, float gap) {
			return x0 - gap < o.x1 && x1 + gap > o.x0 && y0 - gap < o.y1 && y1 + gap > o.y0;
		}
	}

	private final List<Panel> panels = new ArrayList<>();
	private final List<Spark> sparks = new ArrayList<>();
	private final Random rng = new Random();
	private Lyrics forLyrics;
	/** Lines that come back more than once in the song (the chorus, mostly). */
	private boolean[] chorus = new boolean[0];
	private static final int GOLD = 0xFFD37A;
	private Object forLevel;
	private int lastSlot = Integer.MIN_VALUE;
	private float sparkDebt;
	private final List<float[]> drawnRects = new ArrayList<>();
	/** TikTok-edit effects for popular songs. */
	private final EditFx fx = new EditFx();
	/** First chorus line (the "drop") and the line that repeats most (the hook). */
	private int firstChorus = -1;
	private boolean[] hook = new boolean[0];

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
			sparks.clear();
			forLyrics = f.lyrics();
			chorus = findChorus(f.lyrics());
			hook = findHook(f.lyrics());
			firstChorus = -1;
			for (int i = 0; i < chorus.length; i++) if (chorus[i]) { firstChorus = i; break; }
			forLevel = level;
			lastSlot = Integer.MIN_VALUE;
		}

		var track = f.track();
		fx.song(track == null ? null : track.title(), track == null ? null : track.artist(), f.cfg().editEffects);
		if (slot != lastSlot && f.line(slot) != null) {
			lastSlot = slot;
			spawn(f, p, slot, now);
			fx.onLine(isChorus(slot), slot == firstChorus, slot < hook.length && hook[slot], f.width(), f.height());
		}
		int active = f.active();
		fx.update(f.dt(), active >= 0 && isChorus(active), f.width(), f.height());

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

		Panel newest = null;
		for (Panel pn : panels) if (pn.alive()) newest = pn;

		fx.drawBehind(f.g(), f.vis());
		drawThread(f, now);

		// far ones first so near ones draw on top
		List<Panel> order = new ArrayList<>(panels);
		order.sort(Comparator.comparingDouble((Panel pn) -> -pn.posNow(now).distanceToSqr(cam)));
		drawnRects.clear();
		for (Panel pn : order) draw(f, pn, pn == newest, now);

		updateSparks(f, newest, now);
		drawSparks(f);
		fx.drawFront(f.g(), f.font(), f.vis(), f.time());

		// mark our panels as taken so friends' panels keep clear of them
		for (float[] r : drawnRects) HudSpace.reserve(r[0], r[1], r[2], r[3]);
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
			// old panels drift up, so count the spot they'll end up in too
			Rect r = rectOf(p, pn.posNow(now), pn.wBlocks, pn.hBlocks);
			if (r != null) taken.add(r);
			Rect later = rectOf(p, pn.pos.add(0, DRIFT_BLOCKS, 0), pn.wBlocks, pn.hBlocks);
			if (later != null) taken.add(later);
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
				Rect t = rectOf(p, pn.posNow(now), pn.wBlocks, pn.hBlocks);
				if (r == null || t == null || r.hits(t, 4)) pn.diedAt = now - 1_200_000_000L; // quick fade
			}
		}
		for (Panel pn : panels) if (pn.retiredAt < 0) pn.retiredAt = now;
		panels.add(new Panel(slot, chosen, now, wBlocks, hBlocks));
		// let nearby Lyricraft friends see this panel in the same spot (if you share your Echoes)
		var mc = Minecraft.getInstance();
		var mod = dev.lyricraft.LyricraftClient.get();
		if (mod != null && mc.level != null) {
			mod.online.sendEcho(chosen.x, chosen.y, chosen.z, mc.level.dimension().identifier().toString(),
					line.text(), line.durationMs(), f.posMs() - line.startMs(), wBlocks, hBlocks);
		}
	}

	private static Rect rectOf(Projector p, Vec3 pos, float wBlocks, float hBlocks) {
		Projector.Point pt = p.project(pos);
		if (pt == null || pt.pxPerBlock() <= 0) return null;
		float hw = wBlocks * pt.pxPerBlock() / 2f, hh = hBlocks * pt.pxPerBlock() / 2f;
		return new Rect(pt.x() - hw, pt.y() - hh, pt.x() + hw, pt.y() + hh, pt.x(), pt.y(), pt.pxPerBlock());
	}

	// ------------------------------------------------------------------ the thread between panels

	/** A soft glowing thread from each panel to the next (never across the glass), with a light running along it. */
	private void drawThread(Frame f, long now) {
		GuiGraphicsExtractor g = f.g();
		Projector.Point prev = null;
		Rect prevRect = null;
		float prevVis = 0;
		int n = 0;
		int c0 = Draw.mix(f.accent(), 0xFFFFFF, 0.25f), c1 = Draw.mix(f.colorB(), f.accent(), 0.4f);
		for (Panel pn : panels) {
			Vec3 at = pn.posNow(now);
			Projector.Point pt = f.proj().project(at);
			Rect rect = pt == null || pt.pxPerBlock() <= 0 ? null : rectAt(pt, pn.wBlocks, pn.hBlocks);
			float vis = fade(pn, now) * f.vis();
			if (rect != null && prevRect != null && Math.min(vis, prevVis) > 0.02f) {
				float a = Math.min(vis, prevVis);
				float dx = pt.x() - prev.x(), dy = pt.y() - prev.y();
				// only the stretch between the two panels: leave the first, enter the second
				float t0 = exit(prevRect, prev.x(), prev.y(), dx, dy);
				float t1 = 1f - exit(rect, pt.x(), pt.y(), -dx, -dy);
				if (t1 > t0) {
					for (int i = 0; i < 3; i++) {
						float u0 = t0 + (t1 - t0) * i / 3f, u1 = t0 + (t1 - t0) * (i + 1) / 3f;
						int col = Draw.mix(c0, c1, (u0 + u1) / 2f);
						float x0 = prev.x() + dx * u0, y0 = prev.y() + dy * u0, x1 = prev.x() + dx * u1, y1 = prev.y() + dy * u1;
						Draw.line(g, x0, y0, x1, y1, 3f, Draw.argb(col, 0.06f * a));
						Draw.line(g, x0, y0, x1, y1, 1f, Draw.argb(col, 0.26f * a));
					}
					// a little light travelling from the older panel to the newer one
					float u = t0 + (t1 - t0) * ((f.time() * 0.45f + n * 0.37f) % 1f);
					float bright = (float) Math.sin((u - t0) / (t1 - t0) * Math.PI);
					float sx = prev.x() + dx * u, sy = prev.y() + dy * u;
					Sprites.glow(g, sx - 4, sy - 4, 8, 8, Draw.argb(c0, 0.5f * a * bright));
					Sprites.circle(g, sx, sy, 1f, Draw.argb(0xFFFFFF, 0.7f * a * bright));
				}
				n++;
			}
			prev = pt;
			prevRect = rect;
			prevVis = vis;
		}
	}

	private static Rect rectAt(Projector.Point pt, float wBlocks, float hBlocks) {
		float hw = wBlocks * pt.pxPerBlock() / 2f + 3, hh = hBlocks * pt.pxPerBlock() / 2f + 3;
		return new Rect(pt.x() - hw, pt.y() - hh, pt.x() + hw, pt.y() + hh, pt.x(), pt.y(), pt.pxPerBlock());
	}

	/** How far along (x, y) + t * (dx, dy) you leave the box, starting inside it (0..1, clamped). */
	private static float exit(Rect r, float x, float y, float dx, float dy) {
		float t = 1f;
		if (dx > 0) t = Math.min(t, (r.x1 - x) / dx);
		else if (dx < 0) t = Math.min(t, (r.x0 - x) / dx);
		if (dy > 0) t = Math.min(t, (r.y1 - y) / dy);
		else if (dy < 0) t = Math.min(t, (r.y0 - y) / dy);
		return Math.clamp(t, 0f, 1f);
	}

	private static float fade(Panel pn, long now) {
		float age = pn.age(now);
		float in = Math.clamp(age / 0.2f, 0f, 1f);
		float out = pn.alive() ? 1f : 1f - Math.clamp((now - pn.diedAt) / 1e9f / FADE_OUT_S, 0f, 1f);
		return in * out;
	}

	// ------------------------------------------------------------------ panels

	private void draw(Frame f, Panel pn, boolean current, long now) {
		Projector.Point pt = f.proj().project(pn.posNow(now));
		if (pt == null || pt.pxPerBlock() <= 0 || !f.proj().onScreen(pt, 250)) return;
		Lyrics.Line line = f.line(pn.index);
		if (line == null) return;
		GuiGraphicsExtractor g = f.g();

		float age = pn.age(now);
		float retired = pn.retiredAt < 0 ? 0f : Math.clamp((now - pn.retiredAt) / 1e9f / 0.8f, 0f, 1f);
		float dim = current ? 1f : Math.max(0.3f, 0.62f - age / LIFETIME_S * 0.35f);
		dim = 1f + (dim - 1f) * (current ? 0f : retired); // dims smoothly instead of snapping
		float vis = f.vis() * fade(pn, now) * dim;
		if (!current) {
			// older lines further away fade into the background a bit
			double d = pn.posNow(now).distanceTo(f.proj().cameraPos());
			vis *= (float) Math.clamp(1.2 - d / 25.0, 0.45, 1.0);
		}

		float scale = pt.pxPerBlock() * TEXT_BLOCKS * f.cfg().scale / f.font().lineHeight;
		scale = Math.min(scale, 4f);
		String text = line.text();
		int textW = Math.max(f.font().width(text), 40);
		scale = Draw.fitScale(textW, scale, f.width() * 0.85f);
		if (scale < 0.15f) return;

		// word timing (needed for the beat glow before drawing the glass)
		float progress = f.active() == pn.index ? line.progress(f.posMs()) : (f.posMs() >= line.endMs() ? 1f : 0f);
		List<Lyrics.Word> words = line.words();
		float beat = 0f;
		int sungWord = -1;
		for (int i = 0; i < words.size(); i++) {
			float since = (progress - words.get(i).start()) * line.durationMs() / 1000f;
			if (since >= 0) sungWord = i;
			if (since >= 0 && since < 0.3f) beat = Math.max(beat, 1f - since / 0.3f);
		}
		if (!current || progress >= 1f) beat = 0f;
		// mark the moment the line finishes (only right at the end, not when we skip past it)
		long over = f.posMs() - line.endMs();
		if (!pn.finished && over >= 0) {
			pn.finished = true;
			pn.sawDone = over < 600;
			pn.doneAt = now;
		}
		if (current && sungWord > pn.lastWord) {
			if (pn.lastWord >= 0 || sungWord == 0) {
				burst(f, pn, now, 3);
				fx.onWord(isChorus(pn.index), f.width(), f.height());
			}
			pn.lastWord = sungWord;
		}

		// opening: the panel springs open from 85% size; old panels shrink a touch
		float open = Draw.easeOutBack(Math.clamp(age / 0.35f, 0f, 1f));
		// new lines shoot out from the crosshair to their spot
		float fly = Draw.easeOutCubic(Math.clamp(age / 0.32f, 0f, 1f));
		// edit effects: everything shakes a little on the hits, the current line zoom-punches
		float px = f.width() / 2f + (pt.x() - f.width() / 2f) * fly + fx.shakeX(f.time());
		float py = f.height() / 2f + (pt.y() - f.height() / 2f) * fly + fx.shakeY(f.time());
		float size = (0.85f + 0.15f * open) * (0.45f + 0.55f * fly) * (1f - 0.08f * retired) * (1f + 0.025f * beat)
				* (current ? fx.punchScale() : 1f);
		float lh = f.font().lineHeight * scale;
		float panelW = (textW + PAD * 2) * scale * size;
		float panelH = lh * 2.3f * size;
		float bob = (float) Math.sin(f.time() * 1.1f + pn.seed * 6.28f) * 1.4f * scale;
		float x0 = px - panelW / 2f, y0 = py - panelH / 2f + bob;
		int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x0 + panelW), iy1 = Math.round(y0 + panelH);

		// echoes that drift behind the Now Playing card / hotbar get see-through instead of covering them
		float covered = HudSpace.covered(ix0, iy0, ix1, iy1);
		if (vis > 0.15f) drawnRects.add(new float[]{ix0 - 2, iy0 - 2, ix1 + 2, iy1 + 2});
		pn.coverFade = Draw.approach(pn.coverFade, 1f - covered * 0.8f, f.dt() * 4f);
		vis *= pn.coverFade;
		if (vis < 0.02f) return;

		int acc = current ? f.accent() : Draw.mix(f.accent(), f.colorB(), 0.5f);
		float glow = current ? 0.7f + beat * 1.3f + f.pulse() * 0.4f : 0.25f;
		boolean ch = isChorus(pn.index);
		if (ch) {
			// chorus lines get a warm gold edge and shine a bit more
			acc = Draw.mix(acc, GOLD, current ? 0.55f : 0.35f);
			glow *= 1.3f;
		}
		// soft shadow underneath so it looks like it's floating
		int sw = ix1 - ix0, step = Math.max(1, Math.round(scale * 1.2f));
		for (int i = 0; i < 4; i++) {
			int in = Math.round(sw * (0.08f + i * 0.07f));
			int y = iy1 + step * (1 + i);
			g.fill(ix0 + in, y, ix1 - in, y + step, Draw.argb(0x000000, 0.13f * (1f - i / 4f) * vis));
		}
		GlassPanel.draw(g, ix0, iy0, ix1, iy1, f.colorA(), acc, vis * Math.min(1f, open + 0.3f), glow, pn.seed, f.time());

		// the light streak it leaves on the way out
		if (fly < 1f) {
			float a = (1f - fly) * f.vis();
			float tailU = Math.max(0f, fly - 0.35f);
			float sx = f.width() / 2f + (pt.x() - f.width() / 2f) * tailU, sy = f.height() / 2f + (pt.y() - f.height() / 2f) * tailU;
			Draw.line(g, sx, sy, px, py, 4f, Draw.argb(acc, 0.12f * a));
			Draw.line(g, sx, sy, px, py, 1.5f, Draw.argb(Draw.mix(acc, 0xFFFFFF, 0.6f), 0.55f * a));
		}

		// a soft flash when it opens
		if (age < 0.45f) {
			float fl = 1f - age / 0.45f;
			g.fill(ix0, iy0, ix1, iy1, Draw.argb(Draw.mix(acc, 0xFFFFFF, 0.6f), 0.22f * fl * fl * f.vis()));
		}

		// a ripple off the edge when the line finishes
		float done = pn.sawDone ? (now - pn.doneAt) / 1e9f : -1f;
		if (done >= 0 && done < 0.7f) {
			float k = done / 0.7f;
			int grow = Math.round(Draw.easeOutCubic(k) * 10f * Math.max(0.6f, scale));
			int col = Draw.argb(Draw.mix(acc, 0xFFFFFF, 0.4f), 0.45f * (1f - k) * vis);
			frame(g, ix0 - grow, iy0 - grow, ix1 + grow, iy1 + grow, col);
		}

		// tiny timestamp tag on the top-left edge
		float tagScale = Math.max(0.35f, scale * 0.42f);
		Draw.text(g, f.font(), NowPlayingTime.of(line.startMs()) + (ch ? "  ♫ chorus" : ""), ix0 + 4 * scale, iy0 + 2 * scale, tagScale,
				Draw.argb(Draw.mix(acc, 0xFFFFFF, 0.3f), 0.6f * vis), false);

		float s = scale * size;
		float tw = f.font().width(text) * s;
		float tx = px - tw / 2f, ty = y0 + (panelH - lh * size) / 2f + s * 1.5f;

		if (!current) {
			Draw.text(g, f.font(), text, tx, ty, s, Draw.argb(Draw.mix(0xEEF2FF, acc, 0.3f), 0.95f * vis), false);
			shine(g, f, text, tx, ty, s, done, vis);
			return;
		}
		boolean waiting = f.active() < 0 && line.startMs() - f.posMs() > 1800;
		if (waiting) {
			BreakDots.draw(f, px, ty, s, Draw.Align.CENTER, vis);
			return;
		}

		// words pop in one by one as they're sung: they rise, grow and light up
		int cursor = 0;
		for (int i = 0; i < words.size(); i++) {
			Lyrics.Word w = words.get(i);
			int at = text.indexOf(w.text(), cursor);
			if (at < 0) at = cursor;
			cursor = at + w.text().length();
			float wx = tx + f.font().width(text.substring(0, at)) * s;
			float ww = f.font().width(w.text()) * s;
			float since = (progress - w.start()) * line.durationMs() / 1000f;
			if (since < 0) {
				// not sung yet: a faint ghost so you can read ahead
				Draw.text(g, f.font(), w.text(), wx, ty, s, Draw.argb(0xEEF2FF, 0.16f * vis), false);
				continue;
			}
			float t = Math.clamp(since / 0.22f, 0f, 1f);
			float pop = 0.55f + 0.45f * Draw.easeOutBack(t);
			// on an edit hit the words slam in from big instead
			if (fx.slam() > 0.05f && ch) pop = 1f + 0.7f * (1f - Draw.easeOutCubic(Math.clamp(since / 0.16f, 0f, 1f)));
			float rise = (1f - Draw.easeOutCubic(t)) * 5f * s;
			boolean singing = i == sungWord && progress < 1f;
			int col = singing ? Draw.mix(acc, 0xFFFFFF, 0.45f) : acc;
			float cx = wx + ww / 2f, cy = ty + lh * size / 2f;
			float wx0 = cx - ww * pop / 2f, wy0 = cy - lh * size * pop / 2f + rise;
			float split = fx.chroma() * 2.2f * s;
			if (split > 0.3f && singing) {
				Draw.text(g, f.font(), w.text(), wx0 - split, wy0, s * pop, Draw.argb(0xFF3B6B, 0.4f * vis), false);
				Draw.text(g, f.font(), w.text(), wx0 + split, wy0, s * pop, Draw.argb(0x3BE8FF, 0.4f * vis), false);
			}
			Draw.glowText(g, f.font(), w.text(), wx0, wy0, s * pop, col,
					vis * Math.min(1f, t * 1.6f), singing ? 1f + beat : 0.45f);
		}
		shine(g, f, text, tx, ty, s, done, vis);
	}

	/** A bright band that sweeps across the text once, right after the line is sung. */
	private static void shine(GuiGraphicsExtractor g, Frame f, String text, float tx, float ty, float s, float done, float vis) {
		if (done < 0 || done > 0.55f) return;
		float k = done / 0.55f;
		float tw = f.font().width(text) * s;
		float band = Math.max(6f, 14f * s);
		float bx = tx - band + (tw + band * 2) * k;
		int top = Math.round(ty - 2 * s), bottom = Math.round(ty + (f.font().lineHeight + 2) * s);
		g.enableScissor(Math.round(bx), top, Math.round(bx + band), bottom);
		Draw.text(g, f.font(), text, tx, ty, s, Draw.argb(0xFFFFFF, 0.85f * vis * (float) Math.sin(k * Math.PI)), false);
		g.disableScissor();
	}

	private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int col) {
		g.fill(x0, y0, x1, y0 + 1, col);
		g.fill(x0, y1 - 1, x1, y1, col);
		g.fill(x0, y0 + 1, x0 + 1, y1 - 1, col);
		g.fill(x1 - 1, y0 + 1, x1, y1 - 1, col);
	}

	// ------------------------------------------------------------------ sparks

	private void burst(Frame f, Panel pn, long now, int count) {
		for (int i = 0; i < count; i++) addSpark(f, pn, now, true);
	}

	private void addSpark(Frame f, Panel pn, long now, boolean fromWord) {
		if (sparks.size() >= MAX_SPARKS) sparks.removeFirst();
		Vector3f right = f.proj().left().negate();
		Vec3 base = pn.posNow(now);
		float along = (rng.nextFloat() - 0.5f) * pn.wBlocks * 0.9f;
		float upOff = (rng.nextFloat() - 0.3f) * pn.hBlocks * 0.6f;
		Vec3 pos = base.add(right.x * along, upOff, right.z * along);
		Vec3 vel = new Vec3((rng.nextFloat() - 0.5f) * 0.12f, 0.22f + rng.nextFloat() * 0.25f, (rng.nextFloat() - 0.5f) * 0.12f);
		boolean note = fromWord ? rng.nextFloat() < 0.5f : rng.nextFloat() < 0.2f;
		sparks.add(new Spark(pos, vel, 1.2f + rng.nextFloat() * 0.9f, note, rng.nextFloat() * 6.28f, isChorus(pn.index)));
	}

	private void updateSparks(Frame f, Panel current, long now) {
		float dt = Math.min(f.dt(), 0.1f);
		// a gentle trickle while a line is being sung, on top of the bursts on each word
		if (current != null && f.active() == current.index && f.vis() > 0.1f) {
			sparkDebt += dt * (isChorus(current.index) ? 7f : 4f);
			while (sparkDebt >= 1f) {
				sparkDebt -= 1f;
				addSpark(f, current, now, false);
			}
		} else {
			sparkDebt = 0f;
		}
		for (Spark sp : sparks) {
			sp.age += dt;
			sp.pos = sp.pos.add(sp.vel.x * dt, sp.vel.y * dt, sp.vel.z * dt);
		}
		sparks.removeIf(sp -> sp.age >= sp.life);
	}

	private void drawSparks(Frame f) {
		GuiGraphicsExtractor g = f.g();
		for (Spark sp : sparks) {
			Projector.Point pt = f.proj().project(sp.pos);
			if (pt == null || pt.pxPerBlock() <= 0 || !f.proj().onScreen(pt, 20)) continue;
			float u = sp.age / sp.life;
			float a = f.vis() * Math.min(1f, sp.age / 0.15f) * (1f - u) * (1f - u);
			if (a < 0.02f) continue;
			float wob = (float) Math.sin(sp.age * 5f + sp.wobble) * 0.04f * pt.pxPerBlock();
			float x = pt.x() + wob, y = pt.y();
			int col = Draw.mix(sp.gold ? GOLD : f.accent(), f.colorB(), u * (sp.gold ? 0.4f : 1f));
			if (sp.note) {
				float s = Math.clamp(pt.pxPerBlock() * 0.1f / f.font().lineHeight, 0.3f, 1.4f);
				Draw.text(g, f.font(), "♪", x - 2.5f * s, y - 4 * s, s, Draw.argb(col, 0.85f * a), false);
			} else {
				float r = Math.clamp(pt.pxPerBlock() * 0.012f, 0.6f, 2f);
				Sprites.glow(g, x - r * 3f, y - r * 3f, r * 6f, r * 6f, Draw.argb(col, 0.45f * a));
				Sprites.circle(g, x, y, r, Draw.argb(Draw.mix(col, 0xFFFFFF, 0.6f), 0.9f * a));
			}
		}
	}

	private boolean isChorus(int index) {
		return index >= 0 && index < chorus.length && chorus[index];
	}

	/** The line(s) that repeat the most (at least 3 times): the hook. */
	private static boolean[] findHook(Lyrics lyrics) {
		if (lyrics == null) return new boolean[0];
		int n = lyrics.lines.size();
		String[] keys = new String[n];
		java.util.Map<String, Integer> count = new java.util.HashMap<>();
		int best = 0;
		for (int i = 0; i < n; i++) {
			keys[i] = lyrics.lines.get(i).text().toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
			if (keys[i].length() >= 6) best = Math.max(best, count.merge(keys[i], 1, Integer::sum));
		}
		boolean[] out = new boolean[n];
		if (best < 3) return out;
		for (int i = 0; i < n; i++) out[i] = count.getOrDefault(keys[i], 0) == best;
		return out;
	}

	private static boolean[] findChorus(Lyrics lyrics) {
		if (lyrics == null) return new boolean[0];
		int n = lyrics.lines.size();
		String[] keys = new String[n];
		java.util.Map<String, Integer> count = new java.util.HashMap<>();
		for (int i = 0; i < n; i++) {
			String k = lyrics.lines.get(i).text().toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
			keys[i] = k;
			if (k.length() >= 6) count.merge(k, 1, Integer::sum);
		}
		boolean[] out = new boolean[n];
		for (int i = 0; i < n; i++) out[i] = keys[i].length() >= 6 && count.getOrDefault(keys[i], 0) >= 2;
		return out;
	}

	/** m:ss for the little timestamp tags. */
	private static final class NowPlayingTime {
		static String of(long ms) {
			long s = Math.max(0, ms) / 1000;
			return (s / 60) + ":" + String.format("%02d", s % 60);
		}
	}
}
