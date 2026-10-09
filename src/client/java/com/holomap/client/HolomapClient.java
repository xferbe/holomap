package com.holomap.client;

import com.holomap.client.diorama.DioramaManager;
import com.holomap.client.look.BlockLooks;
import com.holomap.client.terrain.ClientTerrain;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.Players;
import com.holomap.net.HolomapNet.TerrainBatch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Mapa em item frame vira maquete 3D do terreno, atualizada em tempo real. Não tem tecla nem tela: é só pendurar o mapa. */
public class HolomapClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientChunkEvents.CHUNK_LOAD.register(ClientTerrain::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(ClientTerrain::onChunkUnload);
		ClientTickEvents.END_CLIENT_TICK.register(HolomapClient::tick);

		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> {
			ClientTerrain.clear();
			ClientState.clear();
			DioramaManager.clear();
			BlockLooks.clearTints();
		});

		ClientPlayNetworking.registerGlobalReceiver(TerrainBatch.TYPE, (p, ctx) -> ClientTerrain.applyFromServer(p.dimension(), p.keys(), p.chunks()));
		ClientPlayNetworking.registerGlobalReceiver(Players.TYPE, (p, ctx) -> ClientState.onPlayers(p.dimension(), p.players()));
		ClientPlayNetworking.registerGlobalReceiver(MapInfo.TYPE, (p, ctx) -> ClientState.onMapInfo(p));
	}

	private static void tick(Minecraft mc) {
		if (mc.level != null && BlockLooks.checkReload()) DioramaManager.invalidate();
		ClientTerrain.tick(mc.level);
		DioramaManager.tick(mc);
		ClientState.tick();
	}
}
