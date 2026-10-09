package com.holomap.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.holomap.HolomapConfig;
import com.holomap.net.HolomapNet;
import com.holomap.net.HolomapNet.Hello;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.MapInfoRequest;
import com.holomap.net.HolomapNet.TerrainBatch;
import com.holomap.net.HolomapNet.ViewRequest;
import com.holomap.terrain.ChunkSummary;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
 * Lado do servidor (integrado no LAN/singleplayer ou dedicado). Mantém o terreno de tudo que foi carregado e manda
 * para cada cliente só os chunks que ele está olhando, que mudaram e que ele não tem carregado — numa fila por
 * jogador, os mais próximos do centro primeiro, com limite de chunks por segundo.
 */
public final class HolomapServer {
	/** Maior lado de uma região pedida, em blocos. Limita o custo da varredura. */
	private static final int MAX_REGION_SPAN = 2048;
	private static final int SEND_INTERVAL = 5;
	/** Sem pedido novo por 5 s = cliente parou de olhar. */
	private static final int VIEW_TIMEOUT = 100;

	private static final ServerTerrainStore TERRAIN = new ServerTerrainStore();
	private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
	private static Path dataDir;
	private static long sentChunks;

	private HolomapServer() {
	}

	private static final class Viewer {
		ResourceKey<Level> dimension;
		int[] regions = new int[0];
		long lastRequest;
		/** Versão de cada chunk que este cliente já recebeu. */
		final Long2LongOpenHashMap sent = new Long2LongOpenHashMap();
		/** O que falta mandar, na ordem de envio. */
		final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
		/** Até onde o log de mudanças já foi lido; -1 = varrer as regiões inteiras de novo. */
		long logPos = -1;
		/** Chunks que ainda podem sair neste ciclo (acumula entre ciclos até o tamanho de um lote). */
		double credit;
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
			sentChunks = 0;
		});

		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> TERRAIN.onChunkLoad(level, chunk));
		ServerChunkEvents.CHUNK_UNLOAD.register(TERRAIN::onChunkUnload);
		ServerTickEvents.END_SERVER_TICK.register(HolomapServer::tick);

		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			if (ServerPlayNetworking.canSend(listener.player, Hello.TYPE)) sender.sendPacket(new Hello(HolomapNet.PROTOCOL));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> VIEWERS.remove(listener.player.getUUID()));

		ServerPlayNetworking.registerGlobalReceiver(ViewRequest.TYPE, (payload, ctx) -> onViewRequest(ctx.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(MapInfoRequest.TYPE, (payload, ctx) -> {
			MapItemSavedData data = MapItem.getSavedData(new MapId(payload.mapId()), ctx.server().overworld());
			if (data != null) {
				ctx.responseSender().sendPacket(new MapInfo(payload.mapId(), data.dimension.identifier().toString(),
					data.centerX, data.centerZ, data.scale));
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("holomap").then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))));
	}

	/** Chamado pelo mixin em {@code ServerLevel.sendBlockUpdated}. */
	public static void onBlockChanged(ServerLevel level, BlockPos pos) {
		TERRAIN.markDirty(level, pos.getX(), pos.getZ());
	}

	/** Garante min <= max e corta regiões enormes em volta do centro (mapa bem afastado). */
	public static int[] clampRegions(int[] in) {
		int[] regions = Arrays.copyOf(in, in.length - in.length % 4);
		for (int i = 0; i + 3 < regions.length; i += 4) {
			int minX = Math.min(regions[i], regions[i + 2]), maxX = Math.max(regions[i], regions[i + 2]);
			int minZ = Math.min(regions[i + 1], regions[i + 3]), maxZ = Math.max(regions[i + 1], regions[i + 3]);
			int midX = (int) (((long) minX + maxX) / 2), midZ = (int) (((long) minZ + maxZ) / 2);
			int half = MAX_REGION_SPAN / 2;
			regions[i] = Math.max(minX, midX - half);
			regions[i + 1] = Math.max(minZ, midZ - half);
			regions[i + 2] = Math.min(maxX, midX + half);
			regions[i + 3] = Math.min(maxZ, midZ + half);
		}
		return regions;
	}

	private static void onViewRequest(ServerPlayer player, ViewRequest req) {
		Identifier dimId = Identifier.tryParse(req.dimension());
		if (dimId == null) return;
		Viewer v = VIEWERS.computeIfAbsent(player.getUUID(), k -> new Viewer());
		ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
		int[] regions = clampRegions(req.regions());
		if (!dim.equals(v.dimension)) {
			v.sent.clear();
			v.dimension = dim;
			v.logPos = -1;
		}
		if (!Arrays.equals(regions, v.regions)) {
			v.regions = regions;
			v.logPos = -1;
		}
		v.lastRequest = player.level().getServer().getTickCount();
	}

	private static void tick(MinecraftServer server) {
		long tick = server.getTickCount();
		TERRAIN.tick(server.getAllLevels(), tick);
		if (tick % SEND_INTERVAL != 0 || VIEWERS.isEmpty()) return;

		double perCycle = HolomapConfig.get().chunksPerSecond * SEND_INTERVAL / 20.0;
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
			updatePending(v);
			v.credit = Math.min(v.credit + perCycle, HolomapNet.MAX_CHUNKS_PER_BATCH);
			sendTerrain(player, v);
		}
	}

	private static boolean inRegions(int[] regions, int cx, int cz) {
		for (int r = 0; r + 3 < regions.length; r += 4) {
			if (cx >= regions[r] >> 4 && cx <= regions[r + 2] >> 4 && cz >= regions[r + 1] >> 4 && cz <= regions[r + 3] >> 4) return true;
		}
		return false;
	}

	private static boolean needs(Viewer v, long key) {
		ChunkSummary s = TERRAIN.get(v.dimension, key);
		return s != null && v.sent.get(key) != s.version;
	}

	/** Lê só as mudanças novas; se a área mudou (ou o log foi cortado), varre as regiões e refaz a fila por distância. */
	private static void updatePending(Viewer v) {
		long end = TERRAIN.logEnd();
		if (v.logPos >= 0 && TERRAIN.changesSince(v.logPos, c -> {
			if (c.dim().equals(v.dimension) && inRegions(v.regions, ChunkPos.getX(c.key()), ChunkPos.getZ(c.key())) && needs(v, c.key())) {
				// chunk que mudou vai na frente: é o que o jogador está esperando ver
				v.pending.addAndMoveToFirst(c.key());
			}
		})) {
			v.logPos = end;
			return;
		}
		v.logPos = end;
		v.pending.clear();
		List<long[]> candidates = new ArrayList<>(); // {key, distância²}
		for (int r = 0; r + 3 < v.regions.length; r += 4) {
			int minCx = v.regions[r] >> 4, minCz = v.regions[r + 1] >> 4;
			int maxCx = v.regions[r + 2] >> 4, maxCz = v.regions[r + 3] >> 4;
			int midCx = (minCx + maxCx) >> 1, midCz = (minCz + maxCz) >> 1;
			for (int cz = minCz; cz <= maxCz; cz++) {
				for (int cx = minCx; cx <= maxCx; cx++) {
					long key = ChunkPos.pack(cx, cz);
					if (!needs(v, key)) continue;
					long dx = cx - midCx, dz = cz - midCz;
					candidates.add(new long[] {key, dx * dx + dz * dz});
				}
			}
		}
		candidates.sort((a, b) -> Long.compare(a[1], b[1]));
		for (long[] c : candidates) v.pending.add(c[0]);
	}

	private static void sendTerrain(ServerPlayer player, Viewer v) {
		if (v.pending.isEmpty() || v.credit < 1) return;
		ChunkTrackingView tracking = player.level().dimension().equals(v.dimension) ? player.getChunkTrackingView() : null;
		List<Long> keys = new ArrayList<>();
		List<ChunkSummary> chunks = new ArrayList<>();
		while (!v.pending.isEmpty() && keys.size() < (int) v.credit) {
			long key = v.pending.removeFirstLong();
			// o cliente já tem esse chunk carregado e lê ele sozinho
			if (tracking != null && tracking.contains(ChunkPos.getX(key), ChunkPos.getZ(key))) continue;
			ChunkSummary s = TERRAIN.get(v.dimension, key);
			if (s == null || v.sent.get(key) == s.version) continue;
			keys.add(key);
			chunks.add(s);
			v.sent.put(key, s.version);
		}
		if (keys.isEmpty()) return;
		v.credit -= keys.size();
		sentChunks += keys.size();
		long[] packed = new long[keys.size()];
		for (int i = 0; i < packed.length; i++) packed[i] = keys.get(i);
		ServerPlayNetworking.send(player, new TerrainBatch(v.dimension.identifier().toString(), packed, chunks));
	}

	private static int info(CommandSourceStack source) {
		StringBuilder sb = new StringBuilder("Holomap terrain:");
		long bytes = 0;
		for (ResourceKey<Level> dim : TERRAIN.dimensions()) {
			sb.append(String.format(Locale.ROOT, "\n  %s: %d chunks", dim.identifier(), TERRAIN.size(dim)));
			if (dataDir != null) {
				try {
					Path file = ServerTerrainStore.fileFor(dataDir.resolve("terrain"), dim);
					if (Files.exists(file)) bytes += Files.size(file);
				} catch (IOException ignored) {
					// tamanho é só informativo
				}
			}
		}
		int queued = 0;
		for (Viewer v : VIEWERS.values()) queued += v.pending.size();
		sb.append(String.format(Locale.ROOT, "\n  on disk: %.1f MB", bytes / 1048576.0));
		sb.append(String.format(Locale.ROOT, "\n  viewers: %d, queued: %d, sent this session: %d", VIEWERS.size(), queued, sentChunks));
		String text = sb.toString();
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}
}
