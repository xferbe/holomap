package com.holomap.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.holomap.net.HolomapNet;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.MapInfoRequest;
import com.holomap.net.HolomapNet.PlayerMarker;
import com.holomap.net.HolomapNet.Players;
import com.holomap.net.HolomapNet.TerrainBatch;
import com.holomap.net.HolomapNet.ViewRequest;
import com.holomap.terrain.ChunkSummary;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Lado do servidor (integrado no LAN/singleplayer ou dedicado). Mantém o terreno de tudo que foi carregado,
 * manda para cada cliente só os chunks que ele está olhando e que mudaram, e sincroniza a posição dos jogadores.
 */
public final class HolomapServer {
	/** Maior lado de uma região pedida, em blocos. Limita o custo da varredura. */
	private static final int MAX_REGION_SPAN = 2048;
	private static final int SCAN_INTERVAL = 5;
	private static final int PLAYERS_INTERVAL = 4;
	/** Sem pedido novo por 5 s = cliente parou de olhar. */
	private static final int VIEW_TIMEOUT = 100;

	private static final ServerTerrainStore TERRAIN = new ServerTerrainStore();
	private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
	private static Path dataDir;

	private HolomapServer() {
	}

	private static final class Viewer {
		ResourceKey<Level> dimension;
		int[] regions = new int[0];
		long lastRequest;
		/** Versão de cada chunk que este cliente já recebeu. */
		final Long2LongOpenHashMap sent = new Long2LongOpenHashMap();
		long scannedModCount = -1;
		boolean needsRescan = true;
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			dataDir = server.getWorldPath(LevelResource.ROOT).resolve("holomap");
			TERRAIN.load(dataDir.resolve("terrain"), server.getAllLevels());
		});
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
			if (dataDir != null) TERRAIN.save(dataDir.resolve("terrain"), true);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (dataDir != null) TERRAIN.save(dataDir.resolve("terrain"), false).join();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TERRAIN.clear();
			VIEWERS.clear();
			dataDir = null;
		});

		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> TERRAIN.onChunkLoad(level, chunk));
		ServerChunkEvents.CHUNK_UNLOAD.register(TERRAIN::onChunkUnload);
		ServerTickEvents.END_SERVER_TICK.register(HolomapServer::tick);

		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> VIEWERS.remove(listener.player.getUUID()));

		ServerPlayNetworking.registerGlobalReceiver(ViewRequest.TYPE, (payload, ctx) -> onViewRequest(ctx.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(MapInfoRequest.TYPE, (payload, ctx) -> {
			MapItemSavedData data = MapItem.getSavedData(new MapId(payload.mapId()), ctx.server().overworld());
			if (data != null) {
				ctx.responseSender().sendPacket(new MapInfo(payload.mapId(), data.dimension.identifier().toString(),
					data.centerX, data.centerZ, data.scale));
			}
		});
	}

	/** Chamado pelo mixin em {@code ServerLevel.sendBlockUpdated}. */
	public static void onBlockChanged(ServerLevel level, BlockPos pos) {
		TERRAIN.markDirty(level, pos.getX(), pos.getZ());
	}

	private static void onViewRequest(ServerPlayer player, ViewRequest req) {
		Identifier dimId = Identifier.tryParse(req.dimension());
		if (dimId == null) return;
		Viewer v = VIEWERS.computeIfAbsent(player.getUUID(), k -> new Viewer());
		ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
		int[] regions = req.regions().clone();
		for (int i = 0; i + 3 < regions.length; i += 4) {
			// garante min <= max e corta regiões enormes em volta do centro (mapa bem afastado)
			int minX = Math.min(regions[i], regions[i + 2]), maxX = Math.max(regions[i], regions[i + 2]);
			int minZ = Math.min(regions[i + 1], regions[i + 3]), maxZ = Math.max(regions[i + 1], regions[i + 3]);
			int midX = (int) (((long) minX + maxX) / 2), midZ = (int) (((long) minZ + maxZ) / 2);
			int half = MAX_REGION_SPAN / 2;
			regions[i] = Math.max(minX, midX - half);
			regions[i + 1] = Math.max(minZ, midZ - half);
			regions[i + 2] = Math.min(maxX, midX + half);
			regions[i + 3] = Math.min(maxZ, midZ + half);
		}
		if (!dim.equals(v.dimension)) {
			v.sent.clear();
			v.dimension = dim;
		}
		if (!java.util.Arrays.equals(regions, v.regions)) {
			v.regions = regions;
			v.needsRescan = true;
		}
		v.lastRequest = player.level().getServer().getTickCount();
	}

	private static void tick(MinecraftServer server) {
		long tick = server.getTickCount();
		TERRAIN.tick(server.getAllLevels(), tick);

		if (tick % PLAYERS_INTERVAL == 0) sendPlayers(server);
		if (tick % SCAN_INTERVAL != 0 || VIEWERS.isEmpty()) return;

		var it = VIEWERS.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Viewer v = entry.getValue();
			if (player == null) {
				it.remove();
				continue;
			}
			if (tick - v.lastRequest > VIEW_TIMEOUT) continue;
			sendTerrain(player, v);
		}
	}

	/** Manda até 32 chunks por vez, os mais próximos do centro primeiro, pulando o que o cliente já tem carregado. */
	private static void sendTerrain(ServerPlayer player, Viewer v) {
		if (!v.needsRescan && v.scannedModCount == TERRAIN.modCount()) return;
		v.scannedModCount = TERRAIN.modCount();
		v.needsRescan = false;

		ChunkTrackingView tracking = player.level().dimension().equals(v.dimension) ? player.getChunkTrackingView() : null;
		List<long[]> candidates = new ArrayList<>(); // {key, distância²}
		for (int r = 0; r + 3 < v.regions.length; r += 4) {
			int minCx = v.regions[r] >> 4, minCz = v.regions[r + 1] >> 4;
			int maxCx = v.regions[r + 2] >> 4, maxCz = v.regions[r + 3] >> 4;
			int midCx = (minCx + maxCx) >> 1, midCz = (minCz + maxCz) >> 1;
			for (int cz = minCz; cz <= maxCz; cz++) {
				for (int cx = minCx; cx <= maxCx; cx++) {
					if (tracking != null && tracking.contains(cx, cz)) continue;
					long key = ChunkPos.pack(cx, cz);
					ChunkSummary s = TERRAIN.get(v.dimension, key);
					if (s == null || v.sent.get(key) == s.version) continue;
					long dx = cx - midCx, dz = cz - midCz;
					candidates.add(new long[] {key, dx * dx + dz * dz});
				}
			}
		}
		if (candidates.isEmpty()) return;
		candidates.sort((a, b) -> Long.compare(a[1], b[1]));

		int n = Math.min(candidates.size(), HolomapNet.MAX_CHUNKS_PER_BATCH);
		long[] keys = new long[n];
		List<ChunkSummary> chunks = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			long key = candidates.get(i)[0];
			ChunkSummary s = TERRAIN.get(v.dimension, key);
			keys[i] = key;
			chunks.add(s);
			v.sent.put(key, s.version);
		}
		ServerPlayNetworking.send(player, new TerrainBatch(v.dimension.identifier().toString(), keys, chunks));
		// sobrou coisa: continua no próximo ciclo mesmo sem mudança nova
		if (candidates.size() > n) v.needsRescan = true;
	}

	private static void sendPlayers(MinecraftServer server) {
		List<ServerPlayer> all = server.getPlayerList().getPlayers();
		if (all.size() < 2) return;
		for (ServerPlayer receiver : all) {
			if (!ServerPlayNetworking.canSend(receiver, Players.TYPE)) continue;
			ResourceKey<Level> dim = receiver.level().dimension();
			List<PlayerMarker> markers = new ArrayList<>();
			for (ServerPlayer p : all) {
				if (p == receiver || !p.level().dimension().equals(dim) || p.isSpectator()) continue;
				markers.add(new PlayerMarker(p.getUUID(), p.getGameProfile().name(), p.getX(), p.getY(), p.getZ(), p.getYRot()));
			}
			ServerPlayNetworking.send(receiver, new Players(dim.identifier().toString(), markers));
		}
	}
}
