package com.holomap.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.holomap.client.terrain.ClientTerrain;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Qualquer bloco que muda no cliente (seu ou vindo do servidor) refaz o chunk no próximo tick. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void holomap$onBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		if (old != current) ClientTerrain.onBlockChanged((ClientLevel) (Object) this, pos.getX(), pos.getZ());
	}
}
