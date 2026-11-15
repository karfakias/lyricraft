package dev.lyricraft.mixin;

import dev.lyricraft.online.Badges;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds the Lyricraft badge after the name tag above players who use the mod. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void lyricraft$badge(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (!(entity instanceof Player player)) return;
		Component name = cir.getReturnValue();
		Component decorated = Badges.decorate(name, player.getUUID());
		if (decorated != name) cir.setReturnValue(decorated);
	}
}
