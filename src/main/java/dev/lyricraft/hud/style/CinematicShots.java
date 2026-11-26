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
 * Cinematic camera angles for the Edit style: on the chorus the view cuts to a shot around you
 * (over the shoulder, from the side, low, wide, from above) and slowly orbits, like the angle
 * changes in an edit. Only while you're standing still: the moment you move or turn, it cuts
 * straight back to your normal view. It never changes where you're actually looking.
 *
 * <p>No angles while riding, gliding, swimming, falling, sleeping or zoomed in with a spyglass,
 * and in tight spots it picks the shot with the most room (or skips it): the camera keeps a bit
 * of space from walls and pulls in when it would hit one, and cuts back if there's no room left.
 */
public final class CinematicShots {
	private CinematicShots() {}

	/** yaw offset from where you face, pitch (down is positive), distance, orbit speed (deg/s). */
	private record Shot(float yaw, float pitch, float dist, float orbit) {}

	private static final Shot[] SHOTS = {
			new Shot(-28f, 14f, 4.2f, 7f),    // over the left shoulder
			new Shot(30f, 10f, 4.6f, -7f),    // over the right shoulder
			new Shot(-80f, 4f, 4.8f, 9f),     // from the side
			new Shot(15f, 34f, 9f, 5f),       // wide, from above and behind
			new Shot(-40f, -12f, 3.4f, 10f),  // low, looking up past you
			new Shot(0f, 72f, 8f, 12f),       // straight down from above
	};

	private static Shot shot;
	private static long startNanos, lastSeen;
	private static float idle;
	private static float lastYaw, lastPitch;
	private static int next;
	/** How far back the camera is right now (pulls in fast at walls, eases back out). */
	private static float dist;

	/** Closer than this to your head and the shot just looks like you're inside a wall. */
	private static final float MIN_ROOM = 1.6f;

	/** Called every frame by the Edit style. Tracks whether you're standing still. */
	static void tick(Minecraft mc, float dt, boolean enabled) {
		lastSeen = System.nanoTime();
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

	/** Cut to the next angle (if you've been standing still for a moment and there's room for it). */
	static void cut(boolean big) {
		Minecraft mc = Minecraft.getInstance();
		if (idle < 1.2f || mc.player == null || mc.level == null) return;
		Vec3 eye = mc.player.getEyePosition();
		Shot want = SHOTS[big ? 3 : next++ % SHOTS.length];
		// in a cave or a small room the planned shot may not fit: take the one with the most room
		Shot pick = null;
		double best = 0;
		for (int i = 0; i < SHOTS.length; i++) {
			Shot s = i == 0 ? want : SHOTS[(next + i) % SHOTS.length];
			double r = room(mc.level, mc.player, eye, back(mc.player.getYRot() + s.yaw(), s.pitch()), s.dist());
			if (s == want && r >= s.dist() * 0.8) {
				pick = s;
				break;
			}
			if (r / s.dist() > best && r >= MIN_ROOM) {
				best = r / s.dist();
				pick = s;
			}
		}
		if (pick == null) return; // nowhere to put the camera: stay in your own view
		shot = pick;
		dist = 0f;
		startNanos = System.nanoTime();
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

	/** The camera for this frame: {x, y, z, yaw, pitch}, or null for the normal view. */
	public static double[] camera(Entity entity, Vec3 eye) {
		Minecraft mc = Minecraft.getInstance();
		Shot s = shot;
		if (s == null || entity != mc.player || !active() || mc.level == null) return null;
		float t = (System.nanoTime() - startNanos) / 1e9f;
		// ease in from your own view over a short whip, then drift around you
		float in = Draw.easeOutCubic(Math.clamp(t / 0.35f, 0f, 1f));
		float yaw = mc.player.getYRot() + (s.yaw() + s.orbit() * t) * in;
		float pitch = mc.player.getXRot() + (s.pitch() - mc.player.getXRot()) * in;
		Vec3 dir = back(yaw, pitch);
		// the orbit can swing into a wall: pull in right away, ease back out once there's room
		float room = (float) room(mc.level, entity, eye, dir, s.dist());
		float target = s.dist() * in;
		dist = room < dist ? room : Draw.approach(dist, Math.min(target, room), 0.15f);
		if (in >= 1f && dist < MIN_ROOM) {
			shot = null; // boxed in: back to your own view
			return null;
		}
		Vec3 pos = eye.add(dir.scale(dist));
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}
}
