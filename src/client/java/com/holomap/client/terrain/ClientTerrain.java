package com.holomap.client.terrain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.holomap.terrain.ChunkSummary;
import com.holomap.terrain.TerrainSampler;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Terreno 3D conhecido pelo cliente. Duas fontes:
 * <ul>
 *   <li>chunks carregados localmente — amostrados aqui, refeitos no tick seguinte a qualquer mudança de bloco;</li>
 *   <li>chunks fora do alcance — vêm do servidor (se ele tiver o mod).</li>
 * </ul>
 * O dado local sempre ganha, porque é o mais fresco. Cada chunk guarda "quando mudou" para cada maquete saber
 * se precisa se refazer sem olhar o mundo inteiro.
 */
public final class ClientTerrain {
	/** Chunks amostrados por tick. Chegar num lugar novo enche a fila; isso espalha o custo. */
	private static final int SAMPLES_PER_TICK = 12;

	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkSummary>> DIMS = new HashMap<>();
	private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> STAMPS = new HashMap<>();
	private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
	private static final LongOpenHashSet LOADED = new LongOpenHashSet();
	private static ClientLevel currentLevel;
	private static long modCount;
	private static long receivedChunks;
	private static long localChanges, serverChanges;

	private ClientTerrain() {
	}

	public static int knownChunks() {
		int n = 0;
		for (Long2ObjectOpenHashMap<ChunkSummary> map : DIMS.values()) n += map.size();
		return n;
	}

	/** Chunks que mudaram de conteúdo: lidos aqui / vindos do servidor. */
	public static long localChanges() {
		return localChanges;
	}

	public static long serverChanges() {
		return serverChanges;
	}

	/** Chunks recebidos do servidor nesta sessão. */
	public static long receivedChunks() {
		return receivedChunks;
	}

	/** Muda sempre que qualquer chunk muda. */
	public static long modCount() {
		return modCount;
	}

	public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		syncLevel(level);
		long key = chunk.getPos().pack();
		LOADED.add(key);
		QUEUE.add(key);
	}

	public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		if (level == currentLevel) LOADED.remove(chunk.getPos().pack());
	}

	/** Bloco mudou: o chunk passa para a frente da fila e é refeito no próximo tick. */
	public static void onBlockChanged(ClientLevel level, int blockX, int blockZ) {
		if (level != currentLevel) return;
		QUEUE.addAndMoveToFirst(ChunkPos.pack(blockX >> 4, blockZ >> 4));
	}

	public static void tick(ClientLevel level) {
		syncLevel(level);
		if (level == null) return;
		for (int n = 0; n < SAMPLES_PER_TICK && !QUEUE.isEmpty(); n++) {
			long key = QUEUE.removeFirstLong();
			LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
			if (chunk != null) if (put(level.dimension(), key, TerrainSampler.sample(level, chunk))) localChanges++;
		}
	}

	public static void applyFromServer(String dimension, long[] keys, List<ChunkSummary> chunks) {
		Identifier id = Identifier.tryParse(dimension);
		if (id == null) return;
		ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, id);
		boolean sameLevel = currentLevel != null && currentLevel.dimension().equals(dim);
		receivedChunks += keys.length;
		for (int i = 0; i < keys.length; i++) {
			if (sameLevel && LOADED.contains(keys[i])) continue;
			if (put(dim, keys[i], chunks.get(i))) serverChanges++;
		}
	}

	/** Guarda o chunk; devolve true se o conteúdo mudou. */
	private static boolean put(ResourceKey<Level> dim, long key, ChunkSummary summary) {
		Long2ObjectOpenHashMap<ChunkSummary> map = DIMS.computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>());
		if (summary.sameContent(map.get(key))) return false;
		map.put(key, summary);
		modCount++;
		STAMPS.computeIfAbsent(dim, k -> new Long2LongOpenHashMap()).put(key, modCount);
		return true;
	}

	/** Algum chunk da área (em blocos) mudou depois de {@code since}? */
	public static boolean changedSince(ResourceKey<Level> dim, int minX, int minZ, int maxX, int maxZ, long since) {
		if (since >= modCount) return false;
		Long2LongOpenHashMap stamps = STAMPS.get(dim);
		if (stamps == null) return false;
		for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
			for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
				if (stamps.get(ChunkPos.pack(cx, cz)) > since) return true;
			}
		}
		return false;
	}

	private static void syncLevel(ClientLevel level) {
		if (level == currentLevel) return;
		currentLevel = level;
		LOADED.clear();
		QUEUE.clear();
	}

	public static void clear() {
		DIMS.clear();
		STAMPS.clear();
		LOADED.clear();
		QUEUE.clear();
		currentLevel = null;
		receivedChunks = 0;
		modCount++;
	}

	public static Reader reader(ResourceKey<Level> dim) {
		return new Reader(DIMS.get(dim));
	}

	/** Leitor de colunas com cache do último chunk — consultas vizinhas quase sempre caem no mesmo. */
	public static final class Reader {
		private final Long2ObjectOpenHashMap<ChunkSummary> map;
		private long lastKey = Long.MIN_VALUE;
		private ChunkSummary last;

		Reader(Long2ObjectOpenHashMap<ChunkSummary> map) {
			this.map = map;
		}

		/** Chunk da coluna, ou null se desconhecido. */
		public ChunkSummary chunk(int x, int z) {
			if (map == null) return null;
			long key = ChunkPos.pack(x >> 4, z >> 4);
			if (key != lastKey) {
				lastKey = key;
				last = map.get(key);
			}
			return last;
		}

		/** Y do bloco mais alto ou {@link ChunkSummary#UNKNOWN}. */
		public int top(int x, int z) {
			ChunkSummary c = chunk(x, z);
			return c == null ? ChunkSummary.UNKNOWN : c.top[ChunkSummary.index(x & 15, z & 15)];
		}
	}
}
