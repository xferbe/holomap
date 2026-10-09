package com.holomap.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.holomap.Holomap;
import com.holomap.terrain.ChunkSummary;
import com.holomap.terrain.TerrainSampler;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Terreno resumido de tudo que já foi carregado no servidor, por dimensão.
 * Sobrevive ao chunk descarregar e é salvo no mundo, então o amigo vê o que você explorou.
 */
public final class ServerTerrainStore {
	private static final int MAGIC = 0x484D5433; // "HMT3" (pilha 3D por coluna)
	/** Chunks novos amostrados por tick. Cada um custa ~256 leituras de bloco. */
	private static final int SAMPLES_PER_TICK = 24;
	/** Um chunk alterado é reamostrado no máximo a cada 10 ticks (0,5 s). */
	private static final int DIRTY_INTERVAL = 10;

	private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkSummary>> dims = new HashMap<>();
	private final Map<ResourceKey<Level>, LongLinkedOpenHashSet> loadQueue = new HashMap<>();
	private final Map<ResourceKey<Level>, LongLinkedOpenHashSet> dirty = new HashMap<>();
	private long nextVersion = 1;
	/** Muda sempre que algum chunk muda; os assinantes usam para pular varreduras à toa. */
	private long modCount;
	private boolean unsaved;

	public long modCount() {
		return modCount;
	}

	public ChunkSummary get(ResourceKey<Level> dim, long key) {
		Long2ObjectOpenHashMap<ChunkSummary> map = dims.get(dim);
		return map == null ? null : map.get(key);
	}

	public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		loadQueue.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(chunk.getPos().pack());
	}

	public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		long key = chunk.getPos().pack();
		LongLinkedOpenHashSet set = dirty.get(level.dimension());
		if (set != null && set.remove(key)) {
			store(level, chunk);
		}
		LongLinkedOpenHashSet queued = loadQueue.get(level.dimension());
		if (queued != null && queued.remove(key) && get(level.dimension(), key) == null) {
			store(level, chunk);
		}
	}

	public void markDirty(ServerLevel level, int blockX, int blockZ) {
		dirty.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(ChunkPos.pack(blockX >> 4, blockZ >> 4));
	}

	public void tick(Iterable<ServerLevel> levels, long tick) {
		boolean dirtyPass = tick % DIRTY_INTERVAL == 0;
		for (ServerLevel level : levels) {
			ResourceKey<Level> dim = level.dimension();
			LongLinkedOpenHashSet queue = loadQueue.get(dim);
			if (queue != null) {
				for (int n = 0; n < SAMPLES_PER_TICK && !queue.isEmpty(); n++) {
					sampleIfLoaded(level, queue.removeFirstLong());
				}
			}
			LongLinkedOpenHashSet changed = dirty.get(dim);
			if (dirtyPass && changed != null && !changed.isEmpty()) {
				LongIterator it = changed.iterator();
				while (it.hasNext()) {
					sampleIfLoaded(level, it.nextLong());
				}
				changed.clear();
			}
		}
	}

	private void sampleIfLoaded(ServerLevel level, long key) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
		if (chunk != null) store(level, chunk);
	}

	private void store(ServerLevel level, LevelChunk chunk) {
		ChunkSummary fresh = TerrainSampler.sample(level, chunk);
		Long2ObjectOpenHashMap<ChunkSummary> map = dims.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
		long key = chunk.getPos().pack();
		if (fresh.sameContent(map.get(key))) return;
		fresh.version = nextVersion++;
		map.put(key, fresh);
		modCount++;
		unsaved = true;
	}

	// ---- persistência ----

	private static Path fileFor(Path dir, ResourceKey<Level> dim) {
		var id = dim.identifier();
		return dir.resolve(id.getNamespace() + "_" + id.getPath().replace('/', '_') + ".bin.gz");
	}

	public void load(Path dir, Iterable<ServerLevel> levels) {
		for (ServerLevel level : levels) {
			Path file = fileFor(dir, level.dimension());
			if (!Files.exists(file)) continue;
			try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
				if (in.readInt() != MAGIC) continue;
				int count = in.readInt();
				Long2ObjectOpenHashMap<ChunkSummary> map = new Long2ObjectOpenHashMap<>(count);
				for (int i = 0; i < count; i++) {
					long key = in.readLong();
					ChunkSummary s = ChunkSummary.read(in);
					s.version = nextVersion++;
					map.put(key, s);
				}
				dims.put(level.dimension(), map);
			} catch (IOException e) {
				Holomap.LOGGER.warn("Não consegui ler o terreno salvo de {}", file, e);
			}
		}
		modCount++;
	}

	/** Copia o estado na thread do servidor e grava em segundo plano (os resumos são imutáveis depois de prontos). */
	public CompletableFuture<Void> save(Path dir, boolean async) {
		if (!unsaved) return CompletableFuture.completedFuture(null);
		unsaved = false;
		Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkSummary>> snapshot = new HashMap<>();
		dims.forEach((dim, map) -> snapshot.put(dim, new Long2ObjectOpenHashMap<>(map)));
		Runnable write = () -> {
			try {
				Files.createDirectories(dir);
				for (var entry : snapshot.entrySet()) {
					Path file = fileFor(dir, entry.getKey());
					Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
					try (OutputStream raw = Files.newOutputStream(tmp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
						out.writeInt(MAGIC);
						out.writeInt(entry.getValue().size());
						for (Long2ObjectMap.Entry<ChunkSummary> e : entry.getValue().long2ObjectEntrySet()) {
							out.writeLong(e.getLongKey());
							e.getValue().write(out);
						}
					}
					Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException e) {
				Holomap.LOGGER.warn("Não consegui salvar o terreno do Holomap", e);
			}
		};
		if (async) return CompletableFuture.runAsync(write);
		write.run();
		return CompletableFuture.completedFuture(null);
	}

	public void clear() {
		dims.clear();
		loadQueue.clear();
		dirty.clear();
		unsaved = false;
	}
}
