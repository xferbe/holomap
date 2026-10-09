package com.holomap.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.holomap.client.diorama.DioramaManager;
import com.mojang.renderpearl.api.commands.RenderPass;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

/** As maquetes vão no mesmo passe sólido do terreno e das entidades, logo depois delas (vale em qualquer modo gráfico). */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "executeSolid", at = @At("TAIL"))
	private void holomap$drawDioramas(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass, CallbackInfo ci) {
		DioramaManager.draw(renderPass);
	}
}
