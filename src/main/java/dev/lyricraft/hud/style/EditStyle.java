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
import net.minecraft.core.BlockPos;
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
	private static final float FADE_S = 1.1f;
	/** How thick the block letters are (font pixels) and how many slices make up the sides. */
	private static final float DEPTH = 3.2f;
	private static final int DEPTH_LAYERS = 6;
	/** Height of a real-font word, in font pixels (the pixel font is 9). */
	private static final float ART_H = 13f;
	private static final String GLITCH = "#$%&*?!<>/\\=+@";

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
		int surfaceColor = -1;        // map colour of the block it's on, or -1
		int key = -1;                 // the word that gets the coloured bar (the longest word on a chorus line)
		WorldKeywords.Kind[] kinds;   // words that do something to the world (fire, rain, storm...)
		WordArt.Glyph[] art;          // words drawn with a real font (null = the pixel font)
		float[] at;                   // when each word lands, as a fraction of the line
		Line parent;                  // a chorus word that flew to where you were looking: its line
		boolean crumbled;             // already burst into dust

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
	/** The song's own look (slide name, colour, font, chorus sky, chorus weather), or null. */
	private EditSongs.Profile profile;
	private String songKey = "";
	private boolean introShown;
	private float vis;
	private int flashColor = 0xFFFFFF;
	private boolean sceneOn = true;
	/** Last time each keyword went off (so a chorus full of "love" doesn't spam), and weather that's still going. */
	private final Map<WorldKeywords.Kind, Float> lastKind = new java.util.EnumMap<>(WorldKeywords.Kind.class);
	private final Map<WorldKeywords.Kind, Float> weather = new java.util.EnumMap<>(WorldKeywords.Kind.class);
	/** Lightning bolts: {x, y, z, start time, seed}. */
	private final List<float[]> bolts = new ArrayList<>();
	/** The "look" slide at the start of a song. */
	private float slideAt = -99f;
	private String slideTitle, slideArtist;

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
			profile = f.cfg().editEffects && track != null ? EditSongs.profile(track.title(), track.artist()) : null;
			songStyle = profile == null ? null : profile.style();
			if (track != null) {
				slideAt = clock;
				slideTitle = track.title();
				slideArtist = track.artist();
			}
			weather.clear();
			bolts.clear();
			SceneFx.clear();
		}

		// the song's name, stamped where you look, while waiting for the first line
		Lyrics.Line first = f.line(0);
		if (!introShown && track != null && first != null && first.startMs() - f.posMs() > 2500 && slot <= 0) {
			introShown = true;
			Line intro = place(f, -1, WordArt.upper(track.title()), false, false);
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
				// every line cuts to a new angle (the chorus then cuts again on each word)
				CinematicShots.cut(l.drop);
				// the song's own chorus: its sky takes over and its weather falls for the whole line
				if (ch && profile != null) {
					float dur = f.line(slot).durationMs() / 1000f;
					if (profile.sky() != null && sceneOn) SceneFx.trigger(profile.sky(), dur + 2f);
					if (profile.weather() != null) weather.put(profile.weather(), clock + dur + 1f);
				}
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
		List<Line> flown = new ArrayList<>(); // added after the loop (adding while going through the list crashed)
		for (Line l : lines) {
			if (l.index < 0 || l.diedAt >= 0) continue;
			Lyrics.Line line = f.line(l.index);
			if (l.parent != null) continue;
			if (line == null || f.posMs() < line.startMs()) continue;
			float progress = active == l.index ? line.progress(f.posMs()) : (f.posMs() >= line.endMs() ? 1f : 0f);
			List<Lyrics.Word> ws = line.words();
			for (int i = 0; i < l.words.length; i++) {
				float start = l.at != null ? l.at[i] : i < ws.size() ? ws.get(i).start() : 1f;
				if (l.landedAt[i] != -1f || progress < start) continue;
				// on the chorus, if you've looked somewhere else, the word lands where you're looking now
				if (l.chorus && i > 0 && lookedAway(mc, l)) {
					Line w = place(f, -2, l.words[i], true, false);
					if (w != null) {
						w.parent = l;
						w.landedAt[0] = clock;
						l.landedAt[i] = -2f; // drawn over there instead
						flown.add(w);
						land(mc, w, 0);
						EditCamera.hit(0.45f, 0.3f);
						continue;
					}
				}
				l.landedAt[i] = clock;
				land(mc, l, i);
				if (l.chorus && i > 0) CinematicShots.quickCut(); // cut on the beat, word by word
			}
		}
		lines.addAll(flown);

		// words that flew off go when their line goes; going lines crumble into dust in the text's colour
		for (Line l : lines) {
			if (l.parent != null && l.diedAt < 0 && l.parent.diedAt >= 0) l.diedAt = l.parent.diedAt;
			if (l.diedAt >= 0 && !l.crumbled && clock - l.diedAt >= 0) {
				l.crumbled = true;
				crumble(mc, l);
			}
		}
		lines.removeIf(l -> l.diedAt >= 0 && clock - l.diedAt > FADE_S || clock - l.born > 40f);
		while (lines.size() > 14) lines.removeFirst();

		// right before the drop the camera dives into the words, then the drop snaps it back with a flash
		Lyrics.Line dropLine = firstChorus >= 0 ? f.line(firstChorus) : null;
		long toDrop = dropLine == null ? Long.MAX_VALUE : dropLine.startMs() - f.posMs();
		EditCamera.dive(toDrop > 0 && toDrop < 700 ? 1f - toDrop / 700f : 0f);
		// and the shot keeps moving a little on the chorus
		EditCamera.sway(active >= 0 && active < chorus.length && chorus[active] && songStyle != EditSongs.Style.SAD ? 1f : 0f);

		// slow push-in while a line is sung (the punch on the next line snaps it back)
		Lyrics.Line cur = active >= 0 ? f.line(active) : null;
		EditCamera.drift(cur == null ? 0f : cur.progress(f.posMs()) * (songStyle == EditSongs.Style.SAD ? 0.05f : 0.035f));

		EditCamera.tick(dt, f.cfg().editCamera && vis > 0.3f);
		CinematicShots.tick(mc, dt, f.cfg().editAngles && vis > 0.3f);
		sceneOn = f.cfg().editScenes;
		SceneFx.tick(dt, sceneOn && vis > 0.3f);
		if (f.active() < 0 && CinematicShots.active() && (next == null || next.startMs() - f.posMs() > 1500)) CinematicShots.end();

		// weather from keywords (rain, snow, petals...) for a few seconds
		weather.entrySet().removeIf(w -> w.getValue() < clock);
		for (var w : weather.keySet()) WorldKeywords.ambient(mc, w, dt, rng);
		bolts.removeIf(b -> clock - b[3] > 0.6f);

		// shader packs draw their own sky, so there the scene goes over the screen instead
		int[] grade = Compat.shaders() ? SceneFx.screenGrade() : null;
		if (grade != null) f.g().fillGradient(0, 0, f.width(), f.height(), grade[0], grade[1]);

		flash = Draw.approach(flash, 0f, dt * 3.5f);
		if (flash < 0.02f) flashColor = 0xFFFFFF;
		if (flash > 0.01f) f.g().fill(0, 0, f.width(), f.height(), Draw.argb(flashColor, 0.28f * flash * flash * vis));
		drawSlide(f);
		drawCredits(f);
	}

	/** Raycast from the camera and stamp the line on what it hits (or in the air). */
	private Line place(Frame f, int index, String text, boolean ch, boolean drop) {
		Minecraft mc = Minecraft.getInstance();
		// from your own eyes, not the camera (which may be off on a cinematic angle)
		Vec3 cam = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1f);
		Vector3f fw = new Vector3f((float) look.x, (float) look.y, (float) look.z).normalize();
		double yr = Math.toRadians(mc.player.getYRot());
		Vector3f camRight = new Vector3f((float) -Math.cos(yr), 0f, (float) -Math.sin(yr));
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
		// the chorus is shouted: capitals, like the lyric edits do
		String shown = ch && index >= 0 ? WordArt.upper(text) : text;
		String[] words = splitWords(shown);
		// fonts, like the edits: one look per kind of song; other songs keep the pixel font but
		// the key word of a chorus line (the longest) gets an elegant serif, mixing fonts like they do
		int keyWord = -1;
		if (ch && index >= 0 && words.length > 1) {
			keyWord = 0;
			for (int i = 1; i < words.length; i++) if (words[i].length() > words[keyWord].length()) keyWord = i;
		}
		WordArt.Face face = faceFor(index);
		WordArt.Glyph[] art = new WordArt.Glyph[words.length];
		for (int i = 0; i < words.length; i++) {
			// Arabic, Hebrew, Hindi, Thai...: the pixel font can't join or stack those letters, so always a real font
			WordArt.Face wf = face != null ? face : i == keyWord ? WordArt.Face.SERIF_ITALIC
					: WordArt.needsRealFont(words[i]) ? WordArt.Face.HEAVY : null;
			if (wf == null) continue;
			art[i] = WordArt.get(wf, words[i]);
			if (art[i] != null) words[i] = WordArt.shown(wf, words[i]);
		}
		float space = font.width(" "), rowMax = 64f, rowH = 11f;
		int[] row = new int[words.length];
		float[] ww = new float[words.length], left = new float[words.length];
		List<Float> rowW = new ArrayList<>();
		float x = 0;
		int r = 0, inRow = 0;
		for (int i = 0; i < words.length; i++) {
			ww[i] = art[i] != null ? ART_H * art[i].aspect() : font.width(words[i]);
			if (inRow > 0 && (x + space + ww[i] > rowMax || inRow >= 3)) {
				rowW.add(x);
				r++;
				x = 0;
				inRow = 0;
			}
			if (inRow > 0) x += art[i] != null && art[i - 1] != null ? space * 0.25f : space;
			left[i] = x;
			row[i] = r;
			x += ww[i];
			inRow++;
		}
		rowW.add(x);
		int rows = rowW.size();
		float[] wx = new float[words.length], wy = new float[words.length];
		boolean rtl = WordArt.rightToLeft(shown);
		float width = 1f;
		for (float w : rowW) width = Math.max(width, w);
		for (int i = 0; i < words.length; i++) {
			wx[i] = left[i] - rowW.get(row[i]) / 2f + ww[i] / 2f;
			if (rtl) wx[i] = -wx[i]; // Arabic, Hebrew: the first word goes on the right
			wy[i] = (row[i] - (rows - 1) / 2f) * rowH;
		}
		float height = rows * rowH;
		// size: big, growing with distance, but never wider than about what you can see there
		float scale = (float) Math.max(0.02, dist * 0.022) * (ch ? 1.2f : 1f) * (drop ? 1.15f : 1f);
		float maxW = (float) Math.max(2.5, dist * (surface ? 1.05 : 0.7));
		scale = Math.min(scale, maxW / width);
		// and never taller than about half of what you can see there (long lines wrap to 3-4 rows)
		scale = Math.min(scale, (float) (dist * (surface ? 0.62 : 0.5)) / Math.max(1f, height + 4f));
		// keep the letters out of the ground: low on a wall, a step or a slope on the floor, a hill in
		// front of a line in the air... walls and air: nudge the line up (down under a ceiling) until
		// it's clear. Floor: slide it back towards you first so it stays flat on the ground, then make
		// it a bit smaller, and only then lift it (a block at most, and never up to your eyes).
		Vec3 lift = surface && hit.getDirection() == Direction.DOWN ? new Vec3(0, -0.25, 0) : new Vec3(0, 0.25, 0);
		boolean floor = surface && hit.getDirection() == Direction.UP;
		Vec3 back = new Vec3(-up.x * 0.25, 0, -up.z * 0.25);
		double lifted = 0, slid = 0;
		double maxLift = floor ? Math.min(1.0, cam.y - 0.8 - anchor.y) : height * scale + 1.5;
		int shrunk = 0;
		for (int tries = 0; tries < 30 && buried(mc, anchor, right, up, normal, width, height, scale); tries++) {
			Vec3 slide = anchor.add(back);
			if (floor && slid < 1.5 && hasFloor(mc, slide)) {
				anchor = slide;
				slid += 0.25;
			} else if (floor && shrunk < 3) {
				scale *= 0.88f;
				shrunk++;
			} else if (lifted + 0.25 <= maxLift) {
				anchor = anchor.add(lift);
				lifted += 0.25;
			} else {
				scale *= 0.85f;
			}
		}
		Line line = new Line(index, text, anchor, right, up, normal, surface, block, scale, words, wx, wy, ww, width, height, ch, drop, clock);
		line.art = art;
		line.at = startsOf(words);
		line.kinds = new WorldKeywords.Kind[words.length];
		for (int i = 0; i < words.length; i++) line.kinds[i] = WorldKeywords.find(words[i]);
		if (ch && index >= 0 && words.length > 1) {
			int best = 0;
			for (int i = 1; i < words.length; i++) if (words[i].length() > words[best].length()) best = i;
			line.key = best;
		}
		if (surface && block != null) {
			try {
				int c = block.getMapColor(mc.level, hit.getBlockPos()).col;
				if (c != 0) line.surfaceColor = c;
			} catch (RuntimeException ignored) {
				// some blocks don't have a map colour here: keep the album colour
			}
		}
		return line;
	}

	/** Solid ground right under this point (so a line slid along the floor stays on it). */
	private static boolean hasFloor(Minecraft mc, Vec3 at) {
		BlockPos below = BlockPos.containing(at.x, at.y - 0.1, at.z);
		return !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()
				&& mc.level.getBlockState(below.above()).getCollisionShape(mc.level, below.above()).isEmpty();
	}

	/**
	 * True if any part of a line placed like this would be inside a solid block: samples the text's
	 * area (a bit bigger, for the slam and the taller real-font words) through its thickness.
	 */
	private static boolean buried(Minecraft mc, Vec3 anchor, Vector3f right, Vector3f up, Vector3f normal,
	                              float width, float height, float scale) {
		double hw = (width / 2f + 2f) * scale * 1.1, hh = (height / 2f + 2.5f) * scale * 1.1;
		BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
		for (int iu = -4; iu <= 4; iu++) {
			for (int iv = -2; iv <= 2; iv++) {
				for (int id = 0; id < 2; id++) {
					double u = hw * iu / 4, v = hh * iv / 2, d = id == 0 ? 0.08 : DEPTH * scale;
					double x = anchor.x + right.x * u + up.x * v + normal.x * d;
					double y = anchor.y + right.y * u + up.y * v + normal.y * d;
					double z = anchor.z + right.z * u + up.z * v + normal.z * d;
					bp.set(Math.floor(x), Math.floor(y), Math.floor(z));
					BlockState st = mc.level.getBlockState(bp);
					if (st.isAir()) continue;
					var shape = st.getCollisionShape(mc.level, bp);
					if (shape.isEmpty()) continue; // grass, flowers, torches... words can go through those
					double lx = x - bp.getX(), ly = y - bp.getY(), lz = z - bp.getZ();
					for (var box : shape.toAabbs()) if (box.contains(lx, ly, lz)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * The line's words. Chinese, Japanese and Thai don't put spaces between words, so a whole
	 * line would be one huge "word": those runs get cut into short chunks (a few characters, at
	 * word breaks where Java knows them) so they land one after another like everything else.
	 */
	static String[] splitWords(String text) {
		List<String> out = new ArrayList<>();
		for (String token : text.trim().split("\\s+")) {
			if (token.isEmpty()) continue;
			if (token.codePointCount(0, token.length()) <= 4 || !unspaced(token)) {
				out.add(token);
				continue;
			}
			// Java only knows where Thai words end when asked in Thai
			java.text.BreakIterator it = java.text.BreakIterator.getWordInstance(thaiLike(token) ? Locale.of("th") : Locale.ROOT);
			it.setText(token);
			StringBuilder chunk = new StringBuilder();
			List<String> pieces = new ArrayList<>();
			for (int a = it.first(), b = it.next(); b != java.text.BreakIterator.DONE; a = b, b = it.next()) {
				String piece = token.substring(a, b);
				// Java doesn't know where Chinese or Thai words end, so a long run comes back whole:
				// cut it every few characters (whole characters, so Thai marks stay on their letter)
				if (piece.codePointCount(0, piece.length()) <= (thaiLike(piece) ? 10 : 4)) {
					pieces.add(piece);
					continue;
				}
				java.text.BreakIterator chars = java.text.BreakIterator.getCharacterInstance(Locale.ROOT);
				chars.setText(piece);
				int size = thaiLike(piece) ? 5 : 3, count = 0, from = 0;
				for (int c = chars.next(); c != java.text.BreakIterator.DONE; c = chars.next()) {
					if (++count == size) {
						pieces.add(piece.substring(from, c));
						from = c;
						count = 0;
					}
				}
				if (from < piece.length()) pieces.add(piece.substring(from));
			}
			for (String piece : pieces) {
				boolean letters = piece.codePoints().anyMatch(Character::isLetterOrDigit);
				// punctuation sticks to the chunk before it; otherwise start a new chunk past ~4 characters
				if (letters && chunk.length() > 0 && chunk.codePointCount(0, chunk.length()) + piece.codePointCount(0, piece.length()) > (thaiLike(piece) ? 7 : 4)) {
					out.add(chunk.toString());
					chunk.setLength(0);
				}
				chunk.append(piece);
			}
			if (chunk.length() > 0) out.add(chunk.toString());
		}
		if (out.isEmpty()) out.add(text.trim());
		return out.toArray(String[]::new);
	}

	private static boolean thaiLike(String s) {
		return s.codePoints().anyMatch(cp -> switch (Character.UnicodeScript.of(cp)) {
			case THAI, LAO, KHMER, MYANMAR -> true;
			default -> false;
		});
	}

	private static boolean unspaced(String token) {
		return token.codePoints().anyMatch(cp -> switch (Character.UnicodeScript.of(cp)) {
			case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR -> true;
			default -> false;
		});
	}

	/** Where each word starts as a fraction of the line, by its place in the text (like the lyrics do). */
	static float[] startsOf(String[] words) {
		int total = 0;
		for (String w : words) total += w.length();
		float[] at = new float[words.length];
		int seen = 0;
		for (int i = 0; i < words.length; i++) {
			at[i] = seen / (float) Math.max(1, total);
			seen += words[i].length();
		}
		return at;
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

		// keywords change the world
		WorldKeywords.Kind kind = l.kinds == null ? null : l.kinds[i];
		if (kind != null && clock - lastKind.getOrDefault(kind, -99f) > 1.2f) {
			lastKind.put(kind, clock);
			Vec3 at = l.at(l.wordX[i], l.wordY[i]);
			WorldKeywords.burst(mc, kind, at, l.normal, Math.max(1f, l.wordW[i] * l.scale), rng);
			if (kind.ambient()) weather.put(kind, clock + 4.5f);
			// and the sky changes with it, like a scene cut
			SceneFx.Scene scene = SceneFx.sceneFor(kind);
			if (scene != null && sceneOn) SceneFx.trigger(scene, 6f);
			switch (kind) {
				case STORM -> {
					bolts.add(new float[]{(float) at.x, (float) at.y, (float) at.z, clock, rng.nextInt(1 << 20)});
					flash = 1f;
					flashColor = 0xDDEEFF;
					EditCamera.hit(0.8f, 0.9f);
				}
				case BOOM -> EditCamera.hit(0.7f, 1f);
				case SUN, STAR -> {
					flash = Math.max(flash, 0.5f);
					flashColor = kind.color;
				}
				case HELL, BLOOD -> {
					flash = Math.max(flash, 0.45f);
					flashColor = kind.color;
				}
				default -> { }
			}
		}
	}

	/** Has the camera turned well away from this line (about 20 degrees)? */
	private static boolean lookedAway(Minecraft mc, Line l) {
		Vec3 to = l.anchor.subtract(mc.player.getEyePosition()).normalize();
		Vec3 fw = mc.player.getViewVector(1f);
		return to.dot(fw) < 0.94;
	}

	/** The line breaks up into dust in the text's colour. */
	private void crumble(Minecraft mc, Line l) {
		int col = l.parent != null ? 0xFFFFFF : Draw.mix(0xFFFFFF, lineColor(l), 0.5f);
		var dust = new net.minecraft.core.particles.DustParticleOptions(col, 1.1f);
		int budget = 36;
		for (int i = 0; i < l.words.length && budget > 0; i++) {
			if (l.landedAt[i] < 0) continue;
			int n = Math.min(budget, 3 + (int) (l.wordW[i] / 6));
			budget -= n;
			for (int k = 0; k < n; k++) {
				Vec3 at = l.at(l.wordX[i] + (rng.nextFloat() - 0.5f) * l.wordW[i], l.wordY[i] + (rng.nextFloat() - 0.5f) * 7f);
				mc.level.addParticle(dust, at.x + l.normal.x * 0.05, at.y + l.normal.y * 0.05, at.z + l.normal.z * 0.05,
						l.normal.x * 0.02, 0.02 + l.normal.y * 0.02, l.normal.z * 0.02);
			}
		}
	}

	/** Glow colour: the song's style, or for other songs the album colour mixed with the surface's colour. */
	private int lineColor(Line l) {
		if (songStyle != null) return styleColor();
		if (l.surfaceColor < 0) return accent;
		return Draw.mix(accent, Draw.mix(l.surfaceColor, 0xFFFFFF, 0.25f), 0.55f);
	}

	private int styleColor() {
		if (profile != null && profile.color() >= 0) return profile.color();
		return baseStyleColor();
	}

	private int baseStyleColor() {
		return switch (songStyle) {
			case DARK -> 0xFF2B3A;
			case SAD -> 0x8FB6FF;
			case LOVE -> 0xFF7AB8;
			case RETRO -> 0xFFC85A;
			case POP -> Draw.hsv(clock * 60f, 0.6f, 1f);
			case HYPE -> 0xFFFFFF;
		};
	}

	/** What the slide calls the song's look. */
	private String lookName() {
		if (profile != null && profile.look() != null) return profile.look();
		if (songStyle == null) return "CINEMATIC";
		String[] names = switch (songStyle) {
			case HYPE -> new String[]{"HYPE EDIT", "FINAL BOSS", "NO MERCY", "MAIN EVENT", "GOD MODE"};
			case DARK -> new String[]{"VILLAIN ARC", "DARK MODE", "NO MORE MR NICE", "AFTER MIDNIGHT", "VILLAIN ERA"};
			case SAD -> new String[]{"SAD HOURS", "3AM THOUGHTS", "MISSING YOU", "RAINY DAYS", "CORE MEMORY"};
			case LOVE -> new String[]{"LOVE STORY", "HOPELESS ROMANTIC", "SOFT HOURS", "YOU AND ME", "BUTTERFLIES"};
			case POP -> new String[]{"MAIN CHARACTER", "IT GIRL ERA", "SUMMER CORE", "POP STAR", "PLAY IT LOUD"};
			case RETRO -> new String[]{"VHS TAPE", "THROWBACK", "RETRO CORE", "OLD SCHOOL", "ON REPEAT"};
		};
		return names[Math.floorMod(songKey.hashCode(), names.length)];
	}

	/** End of the song: a little credits card, like the edits finish on. */
	private void drawCredits(Frame f) {
		var lyr = f.lyrics();
		var track = f.track();
		if (lyr == null || lyr.lines.isEmpty() || track == null) return;
		long after = f.posMs() - lyr.lines.getLast().endMs();
		if (after < 1200 || after > 9200) return;
		// clear the stage: whatever's still standing crumbles away
		for (Line l : lines) if (l.diedAt < 0) l.diedAt = clock;
		float t = (after - 1200) / 1000f;
		float a = vis * Math.min(1f, t / 0.8f) * Math.min(1f, (8f - t) / 1f);
		if (a < 0.02f) return;
		var g = f.g();
		var font = f.font();
		int w = f.width(), h = f.height();
		g.fill(0, 0, w, h, Draw.argb(0x000000, 0.5f * a));
		float rise = (1f - Draw.easeOutCubic(Math.min(1f, t / 1.2f))) * 12f;
		float cy = h * 0.38f + rise;
		int col = songStyle != null ? styleColor() : accent;
		// the title in an elegant serif (pixel font if system fonts aren't available)
		WordArt.Glyph title = WordArt.get(WordArt.Face.SERIF_ITALIC, track.title());
		float y = cy;
		if (title != null) {
			float th = 40f, tw = Math.min(w * 0.85f, th * title.aspect());
			th = tw / title.aspect();
			dev.lyricraft.hud.Sprites.draw(g, title.tex(), w / 2f - tw / 2f, y, tw, th, Draw.argb(0xFFFFFF, a));
			y += th + 2;
		}
		String[] lines = {title != null ? "" : track.title(), track.artist() == null ? "" : track.artist(), "", "edited with Lyricraft"};
		float[] sizes = {2.2f, 1.2f, 0.6f, 0.9f};
		for (int i = 0; i < lines.length; i++) {
			if (i == 0 && lines[0].isEmpty()) continue;
			String s = Draw.ellipsize(font, lines[i], Math.round(w * 0.8f / sizes[i]));
			float sw = font.width(s) * sizes[i];
			int c = i == 0 ? 0xFFFFFF : i == 3 ? col : 0xD8DCE8;
			if (!s.isEmpty()) Draw.glowText(g, font, s, w / 2f - sw / 2f, y, sizes[i], c, a * (i == 3 ? 0.9f : 1f), i == 0 ? 0.5f : 0f);
			y += font.lineHeight * sizes[i] + 4;
		}
	}

	/** The look slide: slides in at the start of a song with letterbox bars, then slides away. */
	private void drawSlide(Frame f) {
		float t = clock - slideAt;
		if (t < 0f || t > 3.8f || slideTitle == null || vis < 0.05f) return;
		var g = f.g();
		var font = f.font();
		int w = f.width(), h = f.height();
		float in = Draw.easeOutCubic(Math.clamp(t / 0.5f, 0f, 1f));
		float out = Math.clamp((t - 3.1f) / 0.7f, 0f, 1f);
		out = out * out;
		float a = vis * (1f - out);
		int col = songStyle != null ? styleColor() : accent;

		// letterbox bars slide in from the top and bottom
		int bar = Math.round(h * 0.075f * in * (1f - out));
		g.fill(0, 0, w, bar, Draw.argb(0x000000, 0.9f * vis));
		g.fill(0, h - bar, w, h, Draw.argb(0x000000, 0.9f * vis));

		// the slide: comes in from the left, leaves to the right
		float x = -w * 0.6f * (1f - in) + w * 0.7f * out + w * 0.07f;
		float y = h * 0.6f;
		String look = lookName();
		float big = 3f;
		float lw = font.width(look) * big;
		// a dark band behind it with a coloured edge
		g.fillGradient(Math.round(x - 10), Math.round(y - 14), Math.round(x + lw + 40), Math.round(y + 9 * big + 26),
				Draw.argb(0x000000, 0.55f * a), Draw.argb(0x000000, 0.25f * a));
		g.fill(Math.round(x - 10), Math.round(y - 14), Math.round(x - 7), Math.round(y + 9 * big + 26), Draw.argb(col, a));
		// a line that wipes across under the title
		float wipe = Draw.easeOutCubic(Math.clamp((t - 0.25f) / 0.5f, 0f, 1f));
		g.fill(Math.round(x), Math.round(y + 9 * big + 3), Math.round(x + (lw + 24) * wipe), Math.round(y + 9 * big + 4), Draw.argb(col, a));
		Draw.text(g, font, "a minecraft edit", x, y - 10, 0.8f, Draw.argb(0xE8E8F0, 0.75f * a), false);
		Draw.glowText(g, font, look, x, y, big, col, a, 0.6f);
		String song = "♪ " + slideTitle + (slideArtist == null || slideArtist.isBlank() ? "" : "  —  " + slideArtist);
		Draw.text(g, font, Draw.ellipsize(font, song, Math.round(w * 0.6f)), x, y + 9 * big + 8, 1f, Draw.argb(0xFFFFFF, 0.9f * a), true);
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
		for (float[] b : bolts) bolt(out, pose, cam, b, clock, vis);
		for (Line l : lines) {
			int glowCol = lineColor(l);
			float fade = l.diedAt < 0 ? 1f : 1f - Math.clamp((clock - l.diedAt) / FADE_S, 0f, 1f);
			float a = fade * vis;
			if (a < 0.02f) continue;
			pose.pushPose();
			pose.translate(l.anchor.x - cam.x, l.anchor.y - cam.y, l.anchor.z - cam.z);
			if (l.drop && clock - l.born < 1.4f) beam(out, pose, l, glowCol, (1f - (clock - l.born) / 1.4f) * vis);
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
				Entry e = entry(l.words[i], age, l.chorus);
				float slam = e.scale;
				float wa = a * e.alpha;
				float cx = l.wordX[i], cy = l.wordY[i] + e.dy;
				boolean newest = current && (i == l.words.length - 1 || l.landedAt[i + 1] < 0);

				// glow behind the word that just hit
				if (newest || age < 0.4f) {
					float g = (newest ? 0.35f : 0f) + 0.5f * Math.max(0f, 1f - age / 0.4f);
					int gc = l.kinds != null && l.kinds[i] != null ? l.kinds[i].color : glowCol;
					glow(out, pose, cx, cy, l.wordW[i] / 2f + 3, 5.5f, gc, (l.kinds != null && l.kinds[i] != null ? g + 0.3f : g) * a);
				}
				// chorus: echo copies of the word, a bit bigger and fainter, pulsing (the classic repeat-text look)
				if (l.chorus && current && e.text.equals(l.words[i]) && (l.art == null || l.art[i] == null)) {
					for (int c = 1; c <= 2; c++) {
						float es = slam * (1f + c * 0.11f + 0.03f * (float) Math.sin(clock * 7f + c));
						pose.pushPose();
						pose.translate(cx, cy, DEPTH + 0.05f * c);
						pose.scale(es, es, 1f);
						out.submitText(pose, -l.wordW[i] / 2f, -4.5f, FormattedCharSequence.forward(l.words[i], Style.EMPTY), false,
								mode, FULL_BRIGHT, Draw.argb(glowCol, wa * (c == 1 ? 0.22f : 0.1f)), 0, 0);
						pose.popPose();
					}
				}
				boolean key = i == l.key && current;
				WorldKeywords.Kind kw = l.kinds == null ? null : l.kinds[i];
				int base = e.tint >= 0 ? e.tint : kw != null ? Draw.mix(kw.color, 0xFFFFFF, 0.12f) : key ? 0xFFFFFF
						: newest ? Draw.mix(0xFFFFFF, glowCol, 0.35f) : current ? 0xFFFFFF : 0xD8DCE8;
				pose.pushPose();
				pose.translate(cx, cy, 0);
				pose.scale(slam, slam, 1f);
				// the key word of a chorus line sits on a coloured bar
				if (key) bar(out, pose, l.wordW[i] / 2f + 2.5f, 5.5f, glowCol == 0xFFFFFF ? accent : glowCol, wa);
				int side = l.surfaceColor >= 0 ? Draw.mix(base, l.surfaceColor, 0.35f) : base;
				WordArt.Glyph glyph = l.art == null ? null : l.art[i];
				if (glyph != null) {
					float reveal = l.words[i].isEmpty() ? 1f : Math.min(1f, e.text.length() / (float) l.words[i].length());
					artWord(out, pose, glyph, l.wordW[i], reveal, base, side, wa, l.onSurface);
					pose.popPose();
					continue;
				}
				// solid 3D block letters: the word stacked a few times along the depth, sides getting darker
				// (standing up off the ground, sticking out of walls, like the letters in Minecraft lyric edits)
				FormattedCharSequence seq = FormattedCharSequence.forward(e.text, Style.EMPTY);
				int layers = DEPTH_LAYERS;
				for (int d = 0; d < layers; d++) {
					float z = DEPTH * d / layers;
					float dark = 0.62f - 0.3f * d / layers;
					pose.pushPose();
					pose.translate(0, 0, z);
					out.submitText(pose, -l.wordW[i] / 2f, -4.5f, seq, false, mode, FULL_BRIGHT,
							Draw.argb(Draw.mix(side, 0x000000, dark), wa), 0, 0);
					pose.popPose();
				}
				// a dark outline round the front, like the white-on-black edit captions
				int ol = Draw.argb(0x101014, wa * 0.85f);
				for (int o = 0; o < 4; o++) {
					pose.pushPose();
					pose.translate(o == 0 ? -0.7f : o == 1 ? 0.7f : 0f, o == 2 ? -0.7f : o == 3 ? 0.7f : 0f, DEPTH - 0.04f);
					out.submitText(pose, -l.wordW[i] / 2f, -4.5f, seq, false, mode, FULL_BRIGHT, ol, 0, 0);
					pose.popPose();
				}
				pose.translate(0, 0, DEPTH);
				out.submitText(pose, -l.wordW[i] / 2f, -4.5f, seq, false, mode, FULL_BRIGHT, Draw.argb(base, wa), 0, 0);
				pose.popPose();
			}
			pose.popPose();
		}
	}

	/** How a word looks while it comes in: its own entrance for each kind of song. */
	private record Entry(String text, float scale, float alpha, float dy, int tint) {}

	private Entry entry(String word, float age, boolean chorus) {
		EditSongs.Style st = songStyle == null ? EditSongs.Style.HYPE : songStyle;
		switch (st) {
			case DARK -> {
				// glitch: scrambled symbols that resolve into the word, flickering red
				if (age < 0.22f) {
					StringBuilder b = new StringBuilder();
					Random r = new Random((long) (age * 30) * 31 + word.hashCode());
					for (int c = 0; c < word.length(); c++) {
						b.append(c < word.length() * (age / 0.22f) ? word.charAt(c) : GLITCH.charAt(r.nextInt(GLITCH.length())));
					}
					return new Entry(b.toString(), 1.05f, 0.9f, (r.nextFloat() - 0.5f) * 1.5f, 0xFF3B4A);
				}
				return new Entry(word, 1f, 1f, 0f, -1);
			}
			case RETRO -> {
				// typewriter: letter by letter
				int n = Math.min(word.length(), 1 + (int) (age / 0.035f));
				return new Entry(word.substring(0, n), 1f, 1f, 0f, -1);
			}
			case SAD -> {
				// slow fade, drifting up into place
				float k = Math.clamp(age / 0.55f, 0f, 1f);
				return new Entry(word, 1f, Draw.easeOutCubic(k), (1f - Draw.easeOutCubic(k)) * 6f, -1);
			}
			case LOVE -> {
				// soft pop
				float k = Math.clamp(age / 0.3f, 0f, 1f);
				return new Entry(word, 0.6f + 0.4f * Draw.easeOutBack(k), Math.min(1f, k * 2f), 0f, -1);
			}
			case POP -> {
				// bounce in from above
				float k = Math.clamp(age / 0.45f, 0f, 1f);
				float bounce = (1f - k) * Math.abs((float) Math.cos(k * Math.PI * 2.5f)) * 9f;
				return new Entry(word, 1f, Math.min(1f, k * 3f), -bounce, -1);
			}
			default -> {
				if (songStyle == null && !chorus) {
					// songs not on the list: the word builds up letter by letter with a small drop
					int n = Math.min(word.length(), 1 + (int) (age / 0.028f));
					float k = Math.clamp(age / 0.18f, 0f, 1f);
					return new Entry(word.substring(0, n), 1f + 0.25f * (1f - Draw.easeOutCubic(k)), 1f, -2.5f * (1f - k), -1);
				}
				// slam: comes in big and hits the surface
				float k = Math.clamp(age / 0.13f, 0f, 1f);
				return new Entry(word, 1f + (chorus ? 1.4f : 1.1f) * (1f - Draw.easeOutCubic(k)), Math.min(1f, 0.25f + k), 0f, -1);
			}
		}
	}

	/** A jagged lightning bolt from the sky down onto a word (additive, flickering). */
	private static void bolt(net.minecraft.client.renderer.SubmitNodeCollector out, PoseStack pose, Vec3 cam, float[] b, float clock, float vis) {
		float age = clock - b[3];
		float a = vis * (1f - age / 0.6f) * (((int) (age * 30)) % 3 == 0 ? 0.4f : 1f);
		if (a < 0.03f) return;
		Random r = new Random((long) b[4]);
		int segs = 12;
		double top = 28;
		double[] xs = new double[segs + 1], zs = new double[segs + 1];
		for (int i = 1; i < segs; i++) {
			xs[i] = xs[i - 1] + (r.nextDouble() - 0.5) * 2.2;
			zs[i] = zs[i - 1] + (r.nextDouble() - 0.5) * 2.2;
		}
		pose.pushPose();
		pose.translate(b[0] - cam.x, b[1] - cam.y, b[2] - cam.z);
		int ai = Math.clamp(Math.round(a * 255), 0, 255);
		out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, vc) -> {
			for (int w = 0; w < 2; w++) {
				float half = w == 0 ? 0.35f : 0.12f;
				int alpha = w == 0 ? ai / 3 : ai;
				for (int i = 0; i < segs; i++) {
					float y0 = (float) (top * (segs - i) / segs), y1 = (float) (top * (segs - i - 1) / segs);
					float x0 = (float) xs[i], z0 = (float) zs[i], x1 = (float) xs[i + 1], z1 = (float) zs[i + 1];
					// two crossed ribbons, both windings
					for (int axis = 0; axis < 2; axis++) {
						float dx = axis == 0 ? half : 0, dz = axis == 0 ? 0 : half;
						vc.addVertex(p, x0 - dx, y0, z0 - dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x1 - dx, y1, z1 - dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x1 + dx, y1, z1 + dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x0 + dx, y0, z0 + dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x0 - dx, y0, z0 - dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x0 + dx, y0, z0 + dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x1 + dx, y1, z1 + dz).setColor(200, 225, 255, alpha);
						vc.addVertex(p, x1 - dx, y1, z1 - dz).setColor(200, 225, 255, alpha);
					}
				}
			}
		});
		pose.popPose();
	}

	/** Font for a line: by the song's style; for other songs only the title gets one. */
	private WordArt.Face faceFor(int index) {
		if (profile != null && profile.font() != null) {
			if (profile.font().equals("PIXEL")) return index == -1 ? WordArt.Face.SERIF_ITALIC : null;
			try {
				return WordArt.Face.valueOf(profile.font());
			} catch (IllegalArgumentException ignored) {
				// unknown font name: fall back to the style's
			}
		}
		if (songStyle == null) return index == -1 ? WordArt.Face.SERIF_ITALIC : null;
		return switch (songStyle) {
			case HYPE -> WordArt.Face.IMPACT;
			case DARK -> WordArt.Face.SERIF_BOLD;
			case SAD -> index == -1 ? WordArt.Face.SCRIPT : WordArt.Face.SERIF_ITALIC;
			case LOVE -> WordArt.Face.SCRIPT;
			case POP -> WordArt.Face.HEAVY;
			case RETRO -> WordArt.Face.MONO;
		};
	}

	/** A real-font word as block letters: the texture stacked along the depth, sides darker, front on top. */
	private static void artWord(net.minecraft.client.renderer.SubmitNodeCollector out, PoseStack pose, WordArt.Glyph g, float w,
	                            float reveal, int base, int side, float alpha, boolean surface) {
		var id = g.tex().id();
		var rt = surface ? RenderTypes.textPolygonOffset(id) : RenderTypes.text(id);
		float h = ART_H, x0 = -w / 2f, x1 = x0 + w * reveal, y0 = -h / 2f, y1 = h / 2f;
		out.submitCustomGeometry(pose, rt, (p, vc) -> {
			int n = DEPTH_LAYERS * 2;
			for (int d = 0; d <= n; d++) {
				boolean front = d == n;
				float z = DEPTH * d / n;
				int c = front ? base : Draw.mix(side, 0x000000, 0.66f - 0.3f * d / n);
				int a = Math.clamp(Math.round(alpha * 255), 0, 255);
				int r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
				texQuad(vc, p, x0, y0, x1, y1, z, reveal, r, gg, b, a);
			}
		});
	}

	private static void texQuad(VertexConsumer vc, PoseStack.Pose p, float x0, float y0, float x1, float y1, float z, float u1,
	                            int r, int g, int b, int a) {
		// both windings, so it shows whichever way it ends up facing
		vc.addVertex(p, x0, y0, z).setColor(r, g, b, a).setUv(0, 0).setLight(FULL_BRIGHT);
		vc.addVertex(p, x0, y1, z).setColor(r, g, b, a).setUv(0, 1).setLight(FULL_BRIGHT);
		vc.addVertex(p, x1, y1, z).setColor(r, g, b, a).setUv(u1, 1).setLight(FULL_BRIGHT);
		vc.addVertex(p, x1, y0, z).setColor(r, g, b, a).setUv(u1, 0).setLight(FULL_BRIGHT);
		vc.addVertex(p, x0, y0, z).setColor(r, g, b, a).setUv(0, 0).setLight(FULL_BRIGHT);
		vc.addVertex(p, x1, y0, z).setColor(r, g, b, a).setUv(u1, 0).setLight(FULL_BRIGHT);
		vc.addVertex(p, x1, y1, z).setColor(r, g, b, a).setUv(u1, 1).setLight(FULL_BRIGHT);
		vc.addVertex(p, x0, y1, z).setColor(r, g, b, a).setUv(0, 1).setLight(FULL_BRIGHT);
	}

	/** A flat coloured bar behind a word (the highlighted word on a chorus line). */
	private static void bar(net.minecraft.client.renderer.SubmitNodeCollector out, PoseStack pose, float hw, float hh, int rgb, float alpha) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, a = Math.clamp(Math.round(alpha * 0.85f * 255), 0, 255);
		out.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, vc) -> {
			vc.addVertex(p, -hw, -hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, -hw, hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, hw, hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, hw, -hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, -hw, -hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, hw, -hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, hw, hh, 0.05f).setColor(r, g, b, a);
			vc.addVertex(p, -hw, hh, 0.05f).setColor(r, g, b, a);
		});
	}

	/** A beam of light shooting up from the line on the drop (two crossed quads, additive). */
	private static void beam(net.minecraft.client.renderer.SubmitNodeCollector out, PoseStack pose, Line l, int rgb, float alpha) {
		if (alpha < 0.02f) return;
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, ai = Math.clamp(Math.round(alpha * 0.6f * 255), 0, 255);
		float w = Math.max(0.4f, l.height * l.scale * 0.6f), h = 48f;
		out.submitCustomGeometry(pose, RenderTypes.lightning(), (p, vc) -> {
			for (int side = 0; side < 2; side++) {
				float dx = side == 0 ? w : 0, dz = side == 0 ? 0 : w;
				// bright at the bottom, gone at the top, soft at both edges (two halves)
				vc.addVertex(p, -dx, 0, -dz).setColor(r, g, b, 0);
				vc.addVertex(p, 0, 0, 0).setColor(r, g, b, ai);
				vc.addVertex(p, 0, h, 0).setColor(r, g, b, 0);
				vc.addVertex(p, -dx, h, -dz).setColor(r, g, b, 0);
				vc.addVertex(p, 0, 0, 0).setColor(r, g, b, ai);
				vc.addVertex(p, dx, 0, dz).setColor(r, g, b, 0);
				vc.addVertex(p, dx, h, dz).setColor(r, g, b, 0);
				vc.addVertex(p, 0, h, 0).setColor(r, g, b, 0);
			}
			// and the other winding, so it's visible from both sides
			for (int side = 0; side < 2; side++) {
				float dx = side == 0 ? w : 0, dz = side == 0 ? 0 : w;
				vc.addVertex(p, -dx, 0, -dz).setColor(r, g, b, 0);
				vc.addVertex(p, -dx, h, -dz).setColor(r, g, b, 0);
				vc.addVertex(p, 0, h, 0).setColor(r, g, b, 0);
				vc.addVertex(p, 0, 0, 0).setColor(r, g, b, ai);
				vc.addVertex(p, 0, 0, 0).setColor(r, g, b, ai);
				vc.addVertex(p, 0, h, 0).setColor(r, g, b, 0);
				vc.addVertex(p, dx, h, dz).setColor(r, g, b, 0);
				vc.addVertex(p, dx, 0, dz).setColor(r, g, b, 0);
			}
		});
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
