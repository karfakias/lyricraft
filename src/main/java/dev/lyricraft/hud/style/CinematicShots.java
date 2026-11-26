package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cinematic camera for the Edit style, like the shots in Minecraft lyric edits: every line cuts
 * to a new angle around you, and on the chorus it cuts on every word. The shots never sit still:
 * pushing in on your face, pulling back to show the place, craning up, a drone flying in from
 * far away, a low hero shot, a tilted dutch angle... with a little handheld drift. Only while
 * you're standing still: the moment you move or turn, it cuts straight back to your own view.
 * It never changes where you're actually looking.
 *
 * <p>No angles while riding, gliding, swimming, falling, sleeping or zoomed in with a spyglass,
 * and in tight spots it picks the shot with the most room (or skips it): the camera keeps a bit
 * of space from walls and pulls in when it would hit one, and cuts back if there's no room left.
 */
public final class CinematicShots {
	private CinematicShots() {}

	/**
	 * A shot: where it starts and where it drifts to over a few seconds.
	 *
	 * @param yaw     around you, from behind (0) to in front looking back at you (180)
	 * @param pitch   looking down (positive) or up (negative), start and end
	 * @param dist    how far back, start and end (push in / pull back)
	 * @param orbit   degrees per second around you
	 * @param roll    tilt (dutch angle)
	 * @param punchy  good for the fast cuts on the chorus words
	 */
	private record Shot(String name, float yaw, float pitch, float pitchEnd, float dist, float distEnd, float orbit, float roll, boolean punchy) {}

	private static final Shot[] SHOTS = {
			new Shot("shoulder left", -28f, 14f, 10f, 4.4f, 3.4f, 7f, 0f, true),
			new Shot("face", 180f, 6f, 3f, 3.4f, 2.3f, -5f, 0f, true),
			new Shot("drone", 25f, 30f, 20f, 14f, 8f, 4f, 0f, false),
			new Shot("side", -82f, 4f, 2f, 4.8f, 4.0f, 9f, 0f, true),
			new Shot("hero low", 150f, -14f, -20f, 3.2f, 2.6f, 8f, 0f, true),
			new Shot("reveal", 200f, 8f, 20f, 2.4f, 7.5f, -9f, 0f, false),
			new Shot("shoulder right", 30f, 10f, 8f, 4.6f, 3.8f, -7f, 0f, true),
			new Shot("dutch", -55f, 8f, 6f, 3.8f, 3.2f, 6f, 12f, true),
			new Shot("crane", 20f, -6f, 38f, 5f, 7f, 5f, 0f, false),
			new Shot("top", 0f, 72f, 82f, 8f, 11f, 12f, 0f, false),
	};
	/** How long a shot takes to drift from its start to its end. */
	private static final float LIFE = 5f;
	/** The fast word cuts on the chorus can't come quicker than this. */
	private static final float MIN_QUICK = 0.42f;

	private static Shot shot;
	/** Calm setting: chorus only, no word cuts, no tilted or straight-down shots. */
	private static boolean calm;
	private static long startNanos, lastSeen;
	private static float idle;
	private static float lastYaw, lastPitch;
	private static int next;
	/** How far back the camera is right now (pulls in fast at walls, eases back out). */
	private static float dist;

	/** Closer than this to your head and the shot just looks like you're inside a wall. */
	private static final float MIN_ROOM = 1.6f;

	/** Called every frame by the Edit style. Tracks whether you're standing still. */
	static void tick(Minecraft mc, float dt, dev.lyricraft.Config.Cinematic mode, boolean visible) {
		lastSeen = System.nanoTime();
		calm = mode == dev.lyricraft.Config.Cinematic.CALM;
		boolean enabled = visible && mode != dev.lyricraft.Config.Cinematic.OFF;
		var p = mc.player;
		if (!enabled || p == null || !mc.options.getCameraType().isFirstPerson() || mc.gui.screen() != null || !canFilm(p)) {
			shot = null;
			idle = 0;
			return;
		}
		// "still" = you're not pressing movement keys or turning (getting nudged by a cow doesn't count)
		var o = mc.options;
		boolean keys = o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
				|| o.keyJump.isDown() || o.keyShift.isDown();
		boolean still = !keys && Math.abs(p.getYRot() - lastYaw) < 0.5f && Math.abs(p.getXRot() - lastPitch) < 0.5f;
		lastYaw = p.getYRot();
		lastPitch = p.getXRot();
		idle = still ? idle + dt : 0f;
		if (!still) shot = null; // you moved: straight back to your own view
	}

	/** Angles only when you're standing (or hovering) somewhere normal. */
	private static boolean canFilm(LocalPlayer p) {
		if (p.isPassenger() || p.isFallFlying() || p.isSleeping() || p.isDeadOrDying() || p.isScoping()) return false;
		if (p.isSwimming() || p.isEyeInFluid(FluidTags.WATER) || p.isEyeInFluid(FluidTags.LAVA)) return false;
		return p.onGround() || p.getAbilities().flying;
	}

	/** A new line: cut to the next angle (big = the drop: the drone or the wide pull-back). Calm: chorus lines only. */
	static void cut(boolean big, boolean chorus) {
		if (calm && !chorus) {
			shot = null;
			return;
		}
		if (big) pick(i -> SHOTS[i].name().equals("drone") || SHOTS[i].name().equals("reveal"));
		else pick(i -> !calm || gentle(SHOTS[i]));
	}

	private static boolean gentle(Shot s) {
		return s.roll() == 0f && !s.name().equals("top");
	}

	/** A word on the chorus: a fast cut to a close, punchy angle (only once a shot is already up). */
	static void quickCut() {
		if (calm || shot == null || !active() || (System.nanoTime() - startNanos) / 1e9f < MIN_QUICK) return;
		pick(i -> SHOTS[i].punchy() && SHOTS[i] != shot);
	}

	private static void pick(java.util.function.IntPredicate ok) {
		Minecraft mc = Minecraft.getInstance();
		if (idle < 1.2f || mc.player == null || mc.level == null) return;
		Vec3 eye = mc.player.getEyePosition();
		// go through the shots in turn (from where we left off), skipping ones that don't fit here
		Shot pickShot = null, fallback = null;
		double best = 0;
		for (int k = 0; k < SHOTS.length; k++) {
			int i = (next + k) % SHOTS.length;
			Shot s = SHOTS[i];
			if (!ok.test(i) || s == shot) continue;
			double want = Math.max(s.dist(), s.distEnd());
			double r = room(mc.level, mc.player, eye, back(mc.player.getYRot() + s.yaw(), s.pitch()), want);
			if (r >= want * 0.8) {
				pickShot = s;
				next = i + 1;
				break;
			}
			if (r / want > best && r >= MIN_ROOM) {
				best = r / want;
				fallback = s;
			}
		}
		if (pickShot == null) pickShot = fallback;
		if (pickShot == null) return; // nowhere to put the camera: stay in your own view
		boolean first = shot == null;
		shot = pickShot;
		if (first) dist = 0f; // whip out from your own view; between shots it's a straight cut
		else dist = Math.min(shot.dist(), (float) room(mc.level, mc.player, eye, back(mc.player.getYRot() + shot.yaw(), shot.pitch()), shot.dist()));
		startNanos = System.nanoTime() - (first ? 0L : 400_000_000L); // straight cuts start already eased in
		EditCamera.hit(first ? 0.25f : 0.18f, 0f);
	}

	/** Unit vector from your eyes out to where the camera sits for this yaw/pitch. */
	private static Vec3 back(float yaw, float pitch) {
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		return new Vec3(Math.sin(yr) * Math.cos(pr), Math.sin(pr), -Math.cos(yr) * Math.cos(pr));
	}

	/** How far the camera can go along {@code dir} before a block, keeping a small gap (like the game's own third person). */
	private static double room(Level level, Entity e, Vec3 eye, Vec3 dir, double max) {
		double best = max;
		for (int i = 0; i < 8; i++) {
			Vec3 a = eye.add(((i & 1) * 2 - 1) * 0.1, ((i >> 1 & 1) * 2 - 1) * 0.1, ((i >> 2 & 1) * 2 - 1) * 0.1);
			var hit = level.clip(new ClipContext(a, a.add(dir.scale(max)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, e));
			if (hit.getType() != HitResult.Type.MISS) best = Math.min(best, hit.getLocation().distanceTo(a) - 0.2);
		}
		return Math.max(0, best);
	}

	/** Back to your own view. */
	static void end() {
		shot = null;
	}

	/** True while the view is actually on an angle (the hand isn't drawn then). */
	public static boolean onAngle() {
		Minecraft mc = Minecraft.getInstance();
		return active() && mc.player != null && mc.options.getCameraType().isFirstPerson();
	}

	static boolean active() {
		return shot != null && System.nanoTime() - lastSeen < 250_000_000L;
	}

	/** Tilt of the current shot (the dutch angle), in degrees. */
	public static float roll() {
		Shot s = shot;
		if (s == null || !onAngle() || s.roll() == 0f) return 0f;
		float t = (System.nanoTime() - startNanos) / 1e9f;
		return s.roll() * Draw.easeOutCubic(Math.clamp(t / 0.35f, 0f, 1f));
	}

	/** The camera for this frame: {x, y, z, yaw, pitch}, or null for the normal view. */
	public static double[] camera(Entity entity, Vec3 eye) {
		Minecraft mc = Minecraft.getInstance();
		Shot s = shot;
		if (s == null || entity != mc.player || !active() || mc.level == null) return null;
		float t = (System.nanoTime() - startNanos) / 1e9f;
		// ease in from your own view over a short whip, then drift: orbit, push in / pull back, crane
		float in = Draw.easeOutCubic(Math.clamp(t / 0.35f, 0f, 1f));
		float u = Math.clamp(t / LIFE, 0f, 1f);
		u = u * u * (3 - 2 * u);
		// a little handheld drift, like a real camera operator
		float hy = 0.5f * (float) (Math.sin(t * 1.3) + 0.4 * Math.sin(t * 2.9));
		float hp = 0.35f * (float) (Math.sin(t * 1.7 + 1.0) + 0.4 * Math.sin(t * 3.3));
		float yaw = mc.player.getYRot() + (s.yaw() + s.orbit() * t + hy) * in;
		float shotPitch = s.pitch() + (s.pitchEnd() - s.pitch()) * u + hp;
		float pitch = mc.player.getXRot() + (shotPitch - mc.player.getXRot()) * in;
		Vec3 dir = back(yaw, pitch);
		float target = (s.dist() + (s.distEnd() - s.dist()) * u) * in;
		// the move can swing into a wall: pull in right away, ease back out once there's room
		float room = (float) room(mc.level, entity, eye, dir, Math.max(s.dist(), s.distEnd()));
		dist = room < dist ? room : Draw.approach(dist, Math.min(target, room), 0.15f);
		if (in >= 1f && dist < MIN_ROOM) {
			shot = null; // boxed in: back to your own view
			return null;
		}
		Vec3 pos = eye.add(dir.scale(dist));
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}
}
