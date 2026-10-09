package com.holomap.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.holomap.client.diorama.DioramaManager;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * Logo antes do jogo desenhar a textura do mapa no quadro, a pose já está no espaço do mapa (128×128, já com a
 * direção e a rotação do quadro). É ali que a maquete entra — por isso ela vale para quadro no chão, na parede e no teto.
 */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMixin {
	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V"))
	private void holomap$submitDiorama(ItemFrameRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		DioramaManager.submit(state, poseStack);
	}

	/**
	 * Quadro com mapa fica invisível (como um quadro com {@code Invisible:1b}): sobra só o mapa 3D, encostado no bloco.
	 * É só visual, no cliente — a entidade continua lá e dá para tirar o mapa normalmente.
	 */
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/decoration/ItemFrame;Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;F)V",
		at = @At("TAIL"))
	private void holomap$hideFrameWithMap(ItemFrame entity, ItemFrameRenderState state, float partialTicks, CallbackInfo ci) {
		if (state.mapId != null) {
			state.isInvisible = true;
			state.frameModel.clear();
		}
	}
}
