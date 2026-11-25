package dev.lyricraft.mixin;

import dev.lyricraft.hud.style.CinematicShots;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No first-person hand floating in the corner while the Edit style is on a cinematic angle. */
@Mixin(GameRenderer.class)
public abstract class HideHandMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void lyricraft$hideHandOnAngle(CallbackInfo ci) {
		if (CinematicShots.onAngle()) ci.cancel();
	}
}
