package dev.lyricraft.mixin;

import dev.lyricraft.hud.style.SceneFx;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Edit style scene changes: the sky, fog and cloud colours your client sees (never the light). */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class SkySceneMixin {
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Inject(method = "getValue", at = @At("RETURN"), cancellable = true, require = 0)
	private void lyricraft$scene(EnvironmentAttribute attribute, float partialTick, CallbackInfoReturnable cir) {
		Object v = cir.getReturnValue();
		Object out = SceneFx.apply(attribute, v);
		if (out != v) cir.setReturnValue(out);
	}
}
