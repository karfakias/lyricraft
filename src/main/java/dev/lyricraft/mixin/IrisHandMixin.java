package dev.lyricraft.mixin;

import dev.lyricraft.hud.style.CinematicShots;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With an Iris shader pack on, Iris draws the hand itself instead of the game, so the normal hook
 * doesn't hide it during cinematic angles. Only applies when Iris is installed; if Iris renames
 * these, it's skipped quietly.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class IrisHandMixin {
	@Inject(method = {"renderSolid", "renderTranslucent"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void lyricraft$hideIrisHand(CallbackInfo ci) {
		if (CinematicShots.onAngle()) ci.cancel();
	}
}
