package dev.lyricraft.mixin;

import dev.lyricraft.hud.style.CinematicShots;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cinematic angles for the Edit style: moves the view only (never where the player aims). */
@Mixin(Camera.class)
public abstract class CameraAngleMixin {
	@Shadow private boolean detached;

	@Shadow
	public abstract Entity entity();

	@Shadow
	public abstract Vec3 position();

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "alignWithEntity", at = @At("TAIL"), require = 0)
	private void lyricraft$cinematicAngle(float partialTick, CallbackInfo ci) {
		double[] c = CinematicShots.camera(entity(), position());
		if (c == null) return;
		setRotation((float) c[3], (float) c[4]);
		setPosition(new Vec3(c[0], c[1], c[2]));
		detached = true; // so your own character shows in the shot
	}
}
