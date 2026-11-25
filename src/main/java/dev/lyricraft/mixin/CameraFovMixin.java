package dev.lyricraft.mixin;

import dev.lyricraft.hud.style.EditCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Zoom punches for the Edit style: briefly narrows the field of view on the hits. */
@Mixin(Camera.class)
public abstract class CameraFovMixin {
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void lyricraft$editZoom(float partialTick, CallbackInfoReturnable<Float> cir) {
		float k = EditCamera.fovFactor();
		if (k != 1f) cir.setReturnValue(cir.getReturnValue() * k);
	}
}
