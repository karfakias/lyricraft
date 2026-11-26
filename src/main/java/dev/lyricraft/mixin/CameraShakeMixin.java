package dev.lyricraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.lyricraft.hud.style.EditCamera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera shake for the Edit style, added where the game adds its own hurt tilt (view only). */
@Mixin(GameRenderer.class)
public abstract class CameraShakeMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), require = 0)
	private void lyricraft$editShake(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
		float roll = EditCamera.roll(), pitch = EditCamera.pitch(), yaw = EditCamera.yaw();
		if (roll == 0f && pitch == 0f && yaw == 0f) return;
		pose.mulPose(new org.joml.Matrix4f().rotation(Axis.ZP.rotationDegrees(roll)));
		pose.mulPose(new org.joml.Matrix4f().rotation(Axis.XP.rotationDegrees(pitch)));
		pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(yaw)));
	}
}
