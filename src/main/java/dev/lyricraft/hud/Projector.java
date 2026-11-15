package dev.lyricraft.hud;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Turns points in the world into HUD coordinates using the camera's real projection, so text
 * "pinned" in the world (in the sky, around the player) moves and shrinks with perspective
 * exactly like a block would.
 */
public final class Projector {

	/** A world point seen on screen. {@code pxPerBlock} = how many GUI pixels one block is at that distance. */
	public record Point(float x, float y, float pxPerBlock, float depth) {}

	private final Matrix4f viewProj = new Matrix4f();
	private Vec3 camPos = Vec3.ZERO;
	private Vector3f up = new Vector3f(0, 1, 0);
	private Vector3f forward = new Vector3f(0, 0, 1);
	private Vector3f left = new Vector3f(1, 0, 0);
	private float yawDeg;
	private int width, height;
	private boolean valid;

	public void update(Camera camera, int guiWidth, int guiHeight) {
		valid = camera != null && camera.isInitialized();
		if (!valid) return;
		camera.getViewRotationProjectionMatrix(viewProj);
		camPos = camera.position();
		up = new Vector3f(camera.upVector());
		forward = new Vector3f(camera.forwardVector());
		Vector3fc l = camera.leftVector();
		left = new Vector3f(l);
		yawDeg = camera.yRot();
		width = guiWidth;
		height = guiHeight;
	}

	public boolean valid() {
		return valid;
	}

	public Vec3 cameraPos() {
		return camPos;
	}

	public float yawDeg() {
		return yawDeg;
	}

	public Vector3f forward() {
		return new Vector3f(forward);
	}

	public Vector3f left() {
		return new Vector3f(left);
	}

	/** Horizontal direction for a Minecraft yaw (degrees). */
	public static Vec3 yawDir(float yawDeg) {
		double r = Math.toRadians(yawDeg);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Projects a point given relative to the camera position. Returns null if it's behind the camera. */
	public Point projectRelative(double dx, double dy, double dz) {
		if (!valid) return null;
		Vector4f v = new Vector4f((float) dx, (float) dy, (float) dz, 1f);
		viewProj.transform(v);
		if (v.w < 0.1f) return null;
		float sx = (v.x / v.w + 1f) * 0.5f * width;
		float sy = (1f - v.y / v.w) * 0.5f * height;

		// one block "up" (in camera space) to measure on-screen size
		Vector4f u = new Vector4f((float) dx + up.x, (float) dy + up.y, (float) dz + up.z, 1f);
		viewProj.transform(u);
		float ppb = 0f;
		if (u.w > 0.1f) {
			float ux = (u.x / u.w + 1f) * 0.5f * width;
			float uy = (1f - u.y / u.w) * 0.5f * height;
			ppb = (float) Math.hypot(ux - sx, uy - sy);
		}
		return new Point(sx, sy, ppb, v.w);
	}

	public Point project(Vec3 world) {
		return projectRelative(world.x - camPos.x, world.y - camPos.y, world.z - camPos.z);
	}

	/** Is this screen point (with some margin) on screen? */
	public boolean onScreen(Point p, float margin) {
		return p != null && p.x() > -margin && p.x() < width + margin && p.y() > -margin && p.y() < height + margin;
	}
}
