package dev.lyricraft.mixin;

import dev.lyricraft.hud.style.SceneFx;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Edit style scene changes: rain (and thunder) on your screen only. The real weather never changes. */
@Mixin(Level.class)
public abstract class SceneRainMixin {
	@Shadow
	public abstract boolean isClientSide();

	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true, require = 0)
	private void lyricraft$sceneRain(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!isClientSide()) return;
		float r = SceneFx.rain();
		if (r > cir.getReturnValue()) cir.setReturnValue(r);
	}

	@Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true, require = 0)
	private void lyricraft$sceneThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (!isClientSide()) return;
		float t = SceneFx.thunder();
		if (t > cir.getReturnValue()) cir.setReturnValue(t);
	}
}
