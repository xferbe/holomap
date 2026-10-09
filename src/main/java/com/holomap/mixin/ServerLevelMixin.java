package com.holomap.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.holomap.server.HolomapServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Todo bloco que muda no servidor passa por aqui; só marcamos o chunk como sujo (custo ~zero). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void holomap$onBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		if (old != current) HolomapServer.onBlockChanged((ServerLevel) (Object) this, pos);
	}
}
