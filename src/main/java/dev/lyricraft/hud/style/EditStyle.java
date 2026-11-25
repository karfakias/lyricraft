package dev.lyricraft.hud.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lyricraft.hud.Draw;
import dev.lyricraft.hud.Projector;
import dev.lyricraft.lyrics.Lyrics;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * EDIT: lyrics like a video edit, in the world itself.
 *
 * Each line gets stamped onto whatever you're looking at: the wall, the ground, a tree, or the
 * sky if there's nothing there. It's real 3D text, so blocks, trees and mobs in front of it hide
 * it ("text behind the subject"). The words slam onto the surface one at a time as they're sung
 * and kick up dust from that block. The camera zoom-punches and shakes on the hits, harder on
 * the chorus, and the first chorus is the big drop with a shockwave across the surface.
 *
 * Popular songs (edit_songs.txt) hit harder and get particles that fit them: hearts, rain,
 * soul flames, sparks, notes.
 */
public final class EditStyle implements LyricStyle {

	private static final double REACH = 28, AIR_DIST = 14;
	private static final int FULL_BRIGHT = 0xF000F0;

	/** One line stamped somewhere in the world. */
	private static final class Line {
		final int index;
		final String text;
		final Vec3 anchor;
		final Vector3f right, up, normal;
		final boolean onSurface;
		final BlockState block;
		final float scale;            // blocks per font pixel
		final float[] wordX, wordY, wordW; // per word: centre in font pixels from the line's centre, and width
		final String[] words;
		final float[] landedAt;       // seconds (style clock) each word hit, or -1
		final float width, height;
		final boolean chorus, drop;
		final float born;
		float diedAt = -1;
		float ring = -1;              // when the shockwave started

		Line(int index, String text, Vec3 anchor, Vector3f right, Vector3f up, Vector3f normal, boolean onSurface,
		     BlockState block, float scale, String[] words, float[] wordX, float[] wordY, float[] wordW, float width,
		     float height, boolean chorus, boolean drop, float born) {
			this.index = index;
			this.text = text;
			this.anchor = anchor;
			this.right = right;
			this.up = up;
			this.normal = normal;
			this.onSurface = onSurface;
			this.block = block;
			this.scale = scale;
			this.words = words;
			this.wordX = wordX;
			this.wordY = wordY;
			this.wordW = wordW;
			this.width = width;
			this.height = height;
			this.landedAt = new float[words.length];
			java.util.Arrays.fill(landedAt, -1f);
			this.chorus = chorus;
			this.drop = drop;
			this.born = born;
		}

		/** World position of a point on the line (font pixels from its centre, y down). */
		Vec3 at(float x, float y) {
			float lx = x * scale, ly = -y * scale;
			return anchor.add(right.x * lx + up.x * ly, right.y * lx + up.y * ly, right.z * lx + up.z * ly);
		}
	}

	private static EditStyle instance;

	private final List<Line> lines = new ArrayList<>();
	private final Random rng = new Random();
	private Lyrics forLyrics;
	private Object forLevel;
	private int lastSlot = Integer.MIN_VALUE;
	private boolean[] chorus = new boolean[0];
	private int firstChorus = -1;
	private float clock;
	private float flash;
	private int accent = 0xFFFFFF;
	private EditSongs.Style songStyle;
	private String songKey = "";
	private boolean introShown;
	private float vis;

	public EditStyle() {
		instance = this;
	}

	@Override
	public boolean showsNowPlayingCard() {
		return true;
	}

	// ------------------------------------------------------------------ per frame (HUD pass)

	@Override
	public void render(Frame f) {
		Minecraft mc = Minecraft.getInstance();
		Projector p = f.proj();
		vis = f.vis();
		if (!p.valid() || mc.level == null) return;
		float dt = Math.min(f.dt(), 0.1f);
		clock += dt;
		accent = f.accent();

		// new song / world / seek back: start over
		Object level = mc.level;
		int slot = f.slot();
		// (careful: lastSlot starts at MIN_VALUE, and MIN_VALUE - 1 wraps around)
		if (f.lyrics() != forLyrics || level != forLevel || lastSlot != Integer.MIN_VALUE && slot < lastSlot - 1) {
			lines.clear();
			forLyrics = f.lyrics();
			forLevel = level;
			lastSlot = Integer.MIN_VALUE;
			chorus = findChorus(f.lyrics());
			firstChorus = -1;
			for (int i = 0; i < chorus.length; i++) if (chorus[i]) { firstChorus = i; break; }
			introShown = false;
		}
		var track = f.track();
		String key = track == null ? "" : track.title() + "\u0000" + track.artist();
		if (!key.equals(songKey)) {
			songKey = key;
			songStyle = f.cfg().editEffects && track != null ? EditSongs.find(track.title(), track.artist()) : null;
		}

		// the song's name, stamped where you look, while waiting for the first line
		Lyrics.Line first = f.line(0);
		if (!introShown && track != null && first != null && first.startMs() - f.posMs() > 2500 && slot <= 0) {
			introShown = true;
			Line intro = place(f, -1, track.title().toUpperCase(Locale.ROOT), false, false);
			if (intro != null) {
				for (int i = 0; i < intro.words.length; i++) intro.landedAt[i] = clock + i * 0.12f;
				lines.add(intro);
				EditCamera.hit(0.6f, 0.3f);
			}
		}

		// a line gets stamped just before it's sung (not during the break before it)
		Lyrics.Line next = f.line(slot);
		boolean due = next != null && (f.active() == slot || next.startMs() - f.posMs() < 350);
		if (slot != lastSlot && due) {
			lastSlot = slot;
			boolean ch = slot < chorus.length && chorus[slot];
			Line l = place(f, slot, f.line(slot).text(), ch, slot == firstChorus);
			if (l != null) {
				// older lines dissolve; quickly if the new one lands on top of them
				for (Line o : lines) {
					if (o.diedAt >= 0) continue;
					boolean near = o.anchor.distanceTo(l.anchor) < Math.max(o.width * o.scale, l.width * l.scale) * 0.6;
					o.diedAt = clock - (near ? 1.2f : 0f);
				}
				lines.add(l);
				float boost = songStyle != null ? 1f : 0.7f;
				if (l.drop) {
					l.ring = clock;
					EditCamera.hit(1f * boost, 1f * boost);
					flash = 1f;
				} else if (ch) {
					l.ring = clock;
					EditCamera.hit(0.7f * boost, 0.55f * boost);
					flash = Math.max(flash, 0.35f);
				} else {
					EditCamera.hit(0.35f * boost, 0.12f * boost);
				}
			}
		}

		// words land as they're sung
		int active = f.active();
		for (Line l : lines) {
			if (l.index < 0 || l.diedAt >= 0) continue;
			Lyrics.Line line = f.line(l.index);
			if (line == null || f.posMs() < line.startMs()) continue;
			float progress = active == l.index ? line.progress(f.posMs()) : (f.posMs() >= line.endMs() ? 1f : 0f);
			List<Lyrics.Word> ws = line.words();
			for (int i = 0; i < Math.min(ws.size(), l.words.length); i++) {
				if (l.landedAt[i] >= 0 || progress < ws.get(i).start()) continue;
				l.landedAt[i] = clock;
				land(mc, l, i);
			}
		}

		// expire
		lines.removeIf(l -> l.diedAt >= 0 && clock - l.diedAt > 1.6f || clock - l.born > 40f);
		while (lines.size() > 4) lines.removeFirst();

		EditCamera.tick(dt, f.cfg().editCamera && vis > 0.3f);
		flash = Draw.approach(flash, 0f, dt * 3.5f);
		if (flash > 0.01f) f.g().fill(0, 0, f.width(), f.height(), Draw.argb(0xFFFFFF, 0.28f * flash * flash * vis));
	}

	/** Raycast from the camera and stamp the line on what it hits (or in the air). */
	private Line place(Frame f, int index, String text, boolean ch, boolean drop) {
		Minecraft mc = Minecraft.getInstance();
		Projector p = f.proj();
		Vec3 cam = p.cameraPos();
		Vector3f fw = p.forward();
		Vector3f camRight = p.left().negate();
		Vector3f camUp = new Vector3f(camRight).cross(fw).normalize();
		if (camUp.y < 0) camUp.negate();

		Vec3 to = cam.add(fw.x * REACH, fw.y * REACH, fw.z * REACH);
		BlockHitResult hit = mc.level.clip(new ClipContext(cam, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		Vec3 anchor;
		Vector3f normal, right, up;
		boolean surface = hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(cam) > 1.8;
		BlockState block = null;
		double dist;
		if (surface) {
			Direction d = hit.getDirection();
			normal = new Vector3f(d.getStepX(), d.getStepY(), d.getStepZ());
			anchor = hit.getLocation().add(normal.x * 0.03, normal.y * 0.03, normal.z * 0.03);
			block = mc.level.getBlockState(hit.getBlockPos());
			dist = hit.getLocation().distanceTo(cam);
			// readable from where you stand: right = camera right flattened onto the surface
			right = new Vector3f(camRight).sub(new Vector3f(normal).mul(camRight.dot(normal)));
			if (right.lengthSquared() < 1e-4) right = new Vector3f(fw).cross(normal);
			right.normalize();
			up = new Vector3f(normal).cross(right).normalize();
			// "up" on the text is whichever way is up on your screen (on the floor: away from you)
			if (up.dot(camUp) + up.dot(fw) * (d == Direction.UP ? 1 : 0) < 0) up.negate();
			// depth axis from right x up, so the text always faces you and isn't mirrored
			normal = new Vector3f(right).cross(up).normalize();
		} else {
			dist = AIR_DIST;
			anchor = cam.add(fw.x * dist, fw.y * dist, fw.z * dist);
			normal = new Vector3f(fw).negate();
			right = new Vector3f(camRight);
			up = new Vector3f(camUp);
		}

		// rows of a few words each, like captions in an edit
		Font font = f.font();
		String[] words = text.trim().split("\\s+");
		float space = font.width(" "), rowMax = 64f, rowH = 11f;
		int[] row = new int[words.length];
		float[] ww = new float[words.length], left = new float[words.length];
		List<Float> rowW = new ArrayList<>();
		float x = 0;
		int r = 0, inRow = 0;
		for (int i = 0; i < words.length; i++) {
			ww[i] = font.width(words[i]);
			if (inRow > 0 && (x + space + ww[i] > rowMax || inRow >= 3)) {
				rowW.add(x);
				r++;
				x = 0;
				inRow = 0;
			}
			if (inRow > 0) x += space;
			left[i] = x;
			row[i] = r;
			x += ww[i];
			inRow++;
		}
		rowW.add(x);
		int rows = rowW.size();
		float[] wx = new float[words.length], wy = new float[words.length];
		float width = 1f;
		for (float w : rowW) width = Math.max(width, w);
		for (int i = 0; i < words.length; i++) {
			wx[i] = left[i] - rowW.get(row[i]) / 2f + ww[i] / 2f;
			wy[i] = (row[i] - (rows - 1) / 2f) * rowH;
		}
		float height = rows * rowH;
		// size: big, growing with distance, but never wider than about what you can see there
		float scale = (float) Math.max(0.02, dist * 0.022) * (ch ? 1.2f : 1f) * (drop ? 1.15f : 1f);
		float maxW = (float) Math.max(2.5, dist * (surface ? 1.05 : 0.7));
		scale = Math.min(scale, maxW / width);
		return new Line(index, text, anchor, right, up, normal, surface, block, scale, words, wx, wy, ww, width, height, ch, drop, clock);
	}

	/** A word hits: dust off the block, the song's particles, and a little camera kick on the chorus. */
	private void land(Minecraft mc, Line l, int i) {
		int dust = l.onSurface && l.block != null && !l.block.isAir() ? 4 : 0;
		for (int k = 0; k < dust; k++) {
			// from the edges of the word, flying outwards, so it doesn't sit on the letters
			float side = rng.nextBoolean() ? 1f : -1f;
			Vec3 at = l.at(l.wordX[i] + side * (l.wordW[i] / 2f + 2f), l.wordY[i] + (rng.nextFloat() - 0.5f) * 8f);
			mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, l.block), at.x, at.y, at.z,
					l.normal.x * 0.15 + (rng.nextDouble() - 0.5) * 0.1, l.normal.y * 0.15 + 0.05, l.normal.z * 0.15 + (rng.nextDouble() - 0.5) * 0.1);
		}
		ParticleOptions extra = switch (songStyle == null ? EditSongs.Style.POP : songStyle) {
			case LOVE -> ParticleTypes.HEART;
			case SAD -> ParticleTypes.FALLING_WATER;
			case DARK -> ParticleTypes.SOUL_FIRE_FLAME;
			case HYPE -> ParticleTypes.CRIT;
			case RETRO -> ParticleTypes.ELECTRIC_SPARK;
			case POP -> songStyle == null ? ParticleTypes.END_ROD : ParticleTypes.NOTE;
		};
		int n = extra == ParticleTypes.HEART ? (l.chorus ? 1 : 0) : songStyle == null ? 2 : l.chorus ? 5 : 3;
		for (int k = 0; k < n; k++) {
			// beside the word, not on top of the letters
			float side = rng.nextBoolean() ? 1f : -1f;
			Vec3 at = l.at(l.wordX[i] + side * (l.wordW[i] / 2f + 3f + rng.nextFloat() * 4f), l.wordY[i] + (rng.nextFloat() - 0.5f) * 6f);
			double up = extra == ParticleTypes.FALLING_WATER ? 0 : 0.04;
			mc.level.addParticle(extra, at.x + l.normal.x * 0.1, at.y + l.normal.y * 0.1, at.z + l.normal.z * 0.1,
					extra == ParticleTypes.NOTE ? rng.nextDouble() : l.normal.x * 0.05, up + l.normal.y * 0.05, l.normal.z * 0.05);
		}
		if (l.chorus) EditCamera.hit(songStyle != null ? 0.28f : 0.18f, songStyle == EditSongs.Style.SAD ? 0f : 0.22f);
	}

	// ------------------------------------------------------------------ in the world (render pass)

	/** Called from the level render event: submits the stamped lines as 3D text. */
	public static void renderWorld(LevelRenderContext ctx) {
		EditStyle s = instance;
		Minecraft mc = Minecraft.getInstance();
		if (s == null || s.lines.isEmpty() || mc.level == null || s.vis < 0.02f) return;
		if (dev.lyricraft.LyricraftClient.get() == null || dev.lyricraft.LyricraftClient.get().config.style != dev.lyricraft.Config.Style.EDIT) return;
		s.submit(ctx, mc);
	}

	private void submit(LevelRenderContext ctx, Minecraft mc) {
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		PoseStack pose = ctx.poseStack();
		var out = ctx.submitNodeCollector();
		Font font = mc.font;
		int glowCol = songStyle == null ? accent : switch (songStyle) {
			case DARK -> 0xFF2B3A;
			case SAD -> 0x8FB6FF;
			case LOVE -> 0xFF7AB8;
			case RETRO -> 0xFFC85A;
			case POP -> Draw.hsv(clock * 60f, 0.6f, 1f);
			case HYPE -> 0xFFFFFF;
		};

		for (Line l : lines) {
			float fade = l.diedAt < 0 ? 1f : 1f - Math.clamp((clock - l.diedAt) / 1.6f, 0f, 1f);
			float a = fade * vis;
			if (a < 0.02f) continue;
			pose.pushPose();
			pose.translate(l.anchor.x - cam.x, l.anchor.y - cam.y, l.anchor.z - cam.z);
			Matrix4f basis = new Matrix4f(
					l.right.x, l.right.y, l.right.z, 0,
					l.up.x, l.up.y, l.up.z, 0,
					l.normal.x, l.normal.y, l.normal.z, 0,
					0, 0, 0, 1);
			pose.last().pose().mul(basis);

			// shockwave across the surface on the chorus hits
			if (l.ring >= 0 && clock - l.ring < 0.6f) {
				float k = (clock - l.ring) / 0.6f;
				float r = (0.2f + 0.8f * Draw.easeOutCubic(k)) * Math.max(1.5f, l.width * l.scale * (l.drop ? 0.6f : 0.45f));
				ring(out, pose, r, r * 0.8f, glowCol, 0.8f * (1f - k) * vis);
				ring(out, pose, r * 0.72f, r * 0.62f, 0xFFFFFF, 0.45f * (1f - k) * vis);
			}

			pose.scale(l.scale, -l.scale, l.scale);
			var mode = l.onSurface ? Font.DisplayMode.POLYGON_OFFSET : Font.DisplayMode.NORMAL;
			boolean current = l.diedAt < 0;
			for (int i = 0; i < l.words.length; i++) {
				if (l.landedAt[i] < 0) continue;
				float age = clock - l.landedAt[i];
				if (age < 0) continue;
				float k = Math.clamp(age / 0.13f, 0f, 1f);
				float slam = 1f + 1.1f * (1f - Draw.easeOutCubic(k));
				float wa = a * Math.min(1f, 0.25f + k);
				float cx = l.wordX[i], cy = l.wordY[i];
				boolean newest = current && (i == l.words.length - 1 || l.landedAt[i + 1] < 0);

				// glow behind the word that just hit
				if (newest || age < 0.4f) {
					float g = (newest ? 0.35f : 0f) + 0.5f * Math.max(0f, 1f - age / 0.4f);
					glow(out, pose, cx, cy, l.wordW[i] / 2f + 3, 5.5f, glowCol, g * a);
				}
				pose.pushPose();
				pose.translate(cx, cy, 0);
				pose.scale(slam, slam, 1f);
				int col = newest ? Draw.argb(Draw.mix(0xFFFFFF, glowCol, 0.35f), wa) : Draw.argb(current ? 0xFFFFFF : 0xD8DCE8, wa);
				out.submitText(pose, -l.wordW[i] / 2f, -4.5f, FormattedCharSequence.forward(l.words[i], Style.EMPTY), true,
						mode, FULL_BRIGHT, col, 0, 0);
				pose.popPose();
			}
			pose.popPose();
		}
	}

	/** Soft additive glow around a box: bright inside, fading to nothing over a border. */
	private static void glow(net.minecraft.client.renderer.SubmitNodeCollector out, PoseStack pose, float cx, float cy, float hw, float hh,
	                         int rgb, float alpha) {
		if (alpha < 0.02f) return;
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, ai = Math.clamp(Math.round(alpha * 0.55f * 255), 0, 255);
		float e = hh * 1.6f;
		float x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh, z = 0.02f;
		out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, vc) -> {
			// middle
			grad(vc, p, x0, y0, x1, y1, z, r, g, b, ai, ai, ai, ai);
			// sides: bright edge on the box, clear on the outside
			grad(vc, p, x0, y0 - e, x1, y0, z, r, g, b, 0, ai, ai, 0);
			grad(vc, p, x0, y1, x1, y1 + e, z, r, g, b, ai, 0, 0, ai);
			grad(vc, p, x0 - e, y0, x0, y1, z, r, g, b, 0, 0, ai, ai);
			grad(vc, p, x1, y0, x1 + e, y1, z, r, g, b, ai, ai, 0, 0);
			// corners
			grad(vc, p, x0 - e, y0 - e, x0, y0, z, r, g, b, 0, 0, ai, 0);
			grad(vc, p, x1, y0 - e, x1 + e, y0, z, r, g, b, 0, ai, 0, 0);
			grad(vc, p, x0 - e, y1, x0, y1 + e, z, r, g, b, 0, 0, 0, ai);
			grad(vc, p, x1, y1, x1 + e, y1 + e, z, r, g, b, ai, 0, 0, 0);
		});
	}

	/** A quad with its own alpha at each corner: (x0,y0) (x0,y1) (x1,y1) (x1,y0). */
	private static void grad(VertexConsumer vc, PoseStack.Pose p, float x0, float y0, float x1, float y1, float z,
	                         int r, int g, int b, int a00, int a01, int a11, int a10) {
		vc.addVertex(p, x0, y0, z).setColor(r, g, b, a00);
		vc.addVertex(p, x0, y1, z).setColor(r, g, b, a01);
		vc.addVertex(p, x1, y1, z).setColor(r, g, b, a11);
		vc.addVertex(p, x1, y0, z).setColor(r, g, b, a10);
	}

	/** A flat ring in the surface plane (additive): bright in the middle of the band, soft on both sides. */
	private static void ring(net.minecraft.client.renderer.SubmitNodeCollector out, PoseStack pose, float outer, float inner, int rgb, float alpha) {
		if (alpha < 0.02f) return;
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, ai = Math.clamp(Math.round(alpha * 255), 0, 255);
		float mid = (outer + inner) / 2f;
		out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, vc) -> {
			int seg = 48;
			for (int i = 0; i < seg; i++) {
				double t0 = i * Math.PI * 2 / seg, t1 = (i + 1) * Math.PI * 2 / seg;
				float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
				band(vc, p, c0, s0, c1, s1, inner, mid, r, g, b, 0, ai);
				band(vc, p, c0, s0, c1, s1, mid, outer, r, g, b, ai, 0);
			}
		});
	}

	private static void band(VertexConsumer vc, PoseStack.Pose p, float c0, float s0, float c1, float s1, float rin, float rout,
	                         int r, int g, int b, int ain, int aout) {
		// both windings, so it shows whichever way the surface faces
		vc.addVertex(p, c0 * rin, s0 * rin, 0.02f).setColor(r, g, b, ain);
		vc.addVertex(p, c1 * rin, s1 * rin, 0.02f).setColor(r, g, b, ain);
		vc.addVertex(p, c1 * rout, s1 * rout, 0.02f).setColor(r, g, b, aout);
		vc.addVertex(p, c0 * rout, s0 * rout, 0.02f).setColor(r, g, b, aout);
		vc.addVertex(p, c0 * rin, s0 * rin, 0.02f).setColor(r, g, b, ain);
		vc.addVertex(p, c0 * rout, s0 * rout, 0.02f).setColor(r, g, b, aout);
		vc.addVertex(p, c1 * rout, s1 * rout, 0.02f).setColor(r, g, b, aout);
		vc.addVertex(p, c1 * rin, s1 * rin, 0.02f).setColor(r, g, b, ain);
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose p, float x0, float y0, float x1, float y1, float z, int r, int g, int b, int a) {
		vc.addVertex(p, x0, y0, z).setColor(r, g, b, a);
		vc.addVertex(p, x0, y1, z).setColor(r, g, b, a);
		vc.addVertex(p, x1, y1, z).setColor(r, g, b, a);
		vc.addVertex(p, x1, y0, z).setColor(r, g, b, a);
	}

	// ------------------------------------------------------------------ helpers

	private static boolean[] findChorus(Lyrics lyrics) {
		if (lyrics == null) return new boolean[0];
		int n = lyrics.lines.size();
		String[] keys = new String[n];
		Map<String, Integer> count = new HashMap<>();
		for (int i = 0; i < n; i++) {
			String k = lyrics.lines.get(i).text().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
			keys[i] = k;
			if (k.length() >= 6) count.merge(k, 1, Integer::sum);
		}
		boolean[] out = new boolean[n];
		for (int i = 0; i < n; i++) out[i] = keys[i].length() >= 6 && count.getOrDefault(keys[i], 0) >= 2;
		return out;
	}
}
