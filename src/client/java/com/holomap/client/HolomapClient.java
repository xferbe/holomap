package com.holomap.client;

import com.holomap.Holomap;
import com.holomap.client.diorama.DioramaManager;
import com.holomap.client.look.BlockLooks;
import com.holomap.client.terrain.ClientTerrain;
import com.holomap.net.HolomapNet.Hello;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.TerrainBatch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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

		ClientPlayNetworking.registerGlobalReceiver(Hello.TYPE, (p, ctx) -> ClientState.onHello(p.protocol()));
		ClientPlayNetworking.registerGlobalReceiver(TerrainBatch.TYPE, (p, ctx) -> ClientTerrain.applyFromServer(p.dimension(), p.keys(), p.chunks()));
		ClientPlayNetworking.registerGlobalReceiver(MapInfo.TYPE, (p, ctx) -> ClientState.onMapInfo(p));

		// diagnóstico: quanto as maquetes estão custando agora
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommands.literal("holomapstats").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal(DioramaManager.stats().describe()));
				return 1;
			})));

		// as maquetes entram pelo passe sólido do LevelRenderer vanilla; quem troca o renderizador pode escondê-las
		for (String mod : new String[] {"sodium", "iris"}) {
			if (FabricLoader.getInstance().isModLoaded(mod)) {
				Holomap.LOGGER.warn("Holomap was not tested with {}: if dioramas do not show up, please open an issue.", mod);
			}
		}
	}

	private static void tick(Minecraft mc) {
		if (mc.level != null && BlockLooks.checkReload()) DioramaManager.invalidate();
		ClientTerrain.tick(mc.level);
		DioramaManager.tick(mc);
		ClientState.tick();
	}
}
