package dev.lyricraft.hud.style;

import dev.lyricraft.hud.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Cinematic camera angles for the Edit style: on the chorus the view cuts to a shot around you
 * (over the shoulder, from the side, low, wide, from above) and slowly orbits, like the angle
 * changes in an edit. Only while you're standing still: the moment you move or turn, it cuts
 * straight back to your normal view. It never changes where you're actually looking.
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

	/** Called every frame by the Edit style. Tracks whether you're standing still. */
	static void tick(Minecraft mc, float dt, boolean enabled) {
		lastSeen = System.nanoTime();
		var p = mc.player;
		if (!enabled || p == null || !mc.options.getCameraType().isFirstPerson() || mc.gui.screen() != null) {
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

	/** Cut to the next angle (if you've been standing still for a moment). */
	static void cut(boolean big) {
		if (idle < 1.2f) return;
		shot = SHOTS[big ? 3 : next++ % SHOTS.length];
		startNanos = System.nanoTime();
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
		double dist = s.dist() * in;
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		Vec3 look = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		Vec3 want = eye.subtract(look.scale(dist));
		// don't put the camera inside a wall
		var hit = mc.level.clip(new ClipContext(eye, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
		Vec3 pos = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().add(look.scale(0.25)) : want;
		return new double[]{pos.x, pos.y, pos.z, yaw, pitch};
	}
}
