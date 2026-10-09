package com.holomap.client.diorama;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.holomap.Holomap;
import com.holomap.HolomapConfig;
import com.holomap.client.ClientState;
import com.holomap.client.look.BlockLooks;
import com.holomap.client.terrain.ClientTerrain;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.terrain.LodPolicy;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dono das maquetes: uma por mapa e nível de detalhe, montada em segundo plano, subida para a GPU uma vez
 * e refeita só quando algum chunk da área do mapa muda. Mapas lado a lado viram um mapa 3D grande sozinhos,
 * porque cada um desenha a própria área no próprio quadro.
 */
public final class DioramaManager {
	/**
	 * Uma área é conferida no máximo a cada 4 ticks. A maquete detalhada (perto) se refaz até 4 vezes por segundo,
	 * para quem está construindo ver na hora; as de longe esperam mais (detalhe 2: 10 ticks, 4: 20, 8: 40).
	 */
	private static final int CHECK_INTERVAL = 4;
	private static final int MIN_REBUILD_TICKS = 5;
	/**
	 * O mundo muda sozinho o tempo todo (grama vira terra, alga cresce, folha cai), e cada mudança tocaria a maquete
	 * inteira. Por isso, somando todos os mapas, sai no máximo uma remontagem a cada 5 ticks, a mais perto primeiro.
	 * Maquete que ainda não existe não espera essa vez.
	 */
	private static final int GLOBAL_REBUILD_TICKS = 5;
	private static final int UNUSED_EVICT_TICKS = 200;
	private static final int ACTIVE_TICKS = 40;

	/** O pipeline de texto do jogo, com o vertex shader próprio (neblina pela distância real até a câmera). */
	private static final RenderPipeline SOLID = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
		.withLocation(Holomap.id("pipeline/diorama_solid"))
		.withVertexShader(Holomap.id("core/diorama"))
		.withColorTargetState(ColorTargetState.DEFAULT)
		.build());
	private static final RenderPipeline WATER = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
		.withLocation(Holomap.id("pipeline/diorama_water"))
		.withVertexShader(Holomap.id("core/diorama"))
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build());

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HoloMap diorama");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private static final class Entry {
		final int mapId, lod;
		MapInfo info;
		DioramaMesh mesh;
		long builtStamp = -1;
		long lastBuildTick = Long.MIN_VALUE / 2;
		long lastCheckTick;
		long lastUsedTick;
		CompletableFuture<DioramaBuilder.Built> pending;
		long pendingStamp;

		Entry(int mapId, int lod) {
			this.mapId = mapId;
			this.lod = lod;
		}
	}

	private record Draw(DioramaMesh mesh, Matrix4f pose, float brightness) {
	}

	/** Números para o {@code /holomapstats} e para o teste de carga. */
	public record Stats(int meshes, long quads, long gpuBytes, int building, int builds, double lastBuildMs, double avgBuildMs,
						double avgSnapshotMs, int knownChunks, long receivedChunks, long localChanges, long serverChanges, int serverProtocol) {
		public String describe() {
			return String.format(Locale.ROOT,
				"HoloMap: %d dioramas, %,d faces, %.1f MB GPU, %d building%n"
					+ "builds: %d (worker: last %.1f ms, avg %.1f ms; game thread: avg %.2f ms)%n"
					+ "terrain: %,d chunks known, %,d received from server, changes %,d local / %,d server (protocol %s)",
				meshes, quads, gpuBytes / 1048576.0, building, builds, lastBuildMs, avgBuildMs, avgSnapshotMs,
				knownChunks, receivedChunks, localChanges, serverChanges, serverProtocol == 0 ? "none" : Integer.toString(serverProtocol));
		}
	}

	private static final Map<Long, Entry> ENTRIES = new HashMap<>();
	private static final List<Draw> DRAWS = new ArrayList<>();
	/** Último nível de detalhe usado em cada mapa (para a folga entre níveis). */
	private static final Int2IntOpenHashMap LAST_LOD = new Int2IntOpenHashMap();
	private static final AtomicInteger BUILDS = new AtomicInteger();
	private static final AtomicLong BUILD_NANOS = new AtomicLong();
	/** Tempo gasto na thread do jogo copiando áreas (a parte da montagem que pesa no FPS). */
	private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();
	private static volatile long lastBuildNanos;
	private static double appliedVerticalScale = Double.NaN;
	private static long ticks;
	private static long lastRebuildTick = Long.MIN_VALUE / 2;

	static {
		LAST_LOD.defaultReturnValue(-1);
	}

	private DioramaManager() {
	}

	private static long key(int mapId, int lod) {
		return ((long) mapId << 8) | lod;
	}

	/** A rocha que preenche a maquete abaixo do solo: pedra, netherrack ou pedra do End. */
	private static BlockLooks.Look deepFill(ClientLevel level) {
		BlockState rock = level.dimension() == Level.NETHER ? Blocks.NETHERRACK.defaultBlockState()
			: level.dimension() == Level.END ? Blocks.END_STONE.defaultBlockState()
			: Blocks.STONE.defaultBlockState();
		return BlockLooks.of(Block.getId(rock));
	}

	/** Y que fica rente à textura do mapa: um pouco abaixo do nível do mar, para lagos e vales terem fundo. */
	public static int baseY(ClientLevel level) {
		return Math.max(level.getMinY(), level.getSeaLevel() - DioramaBuilder.DEPTH_BELOW_SEA);
	}

	private static int lodFor(int mapId, double distSq) {
		int index = LodPolicy.index(Math.sqrt(distSq), HolomapConfig.get().lodDistances, LAST_LOD.get(mapId));
		LAST_LOD.put(mapId, index);
		return LodPolicy.LOD[index];
	}

	/** Chamado pelo mixin do {@code ItemFrameRenderer}, com a pose já no espaço do mapa (128×128). Agenda o desenho da maquete. */
	public static void submit(ItemFrameRenderState state, PoseStack poseStack) {
		Minecraft mc = Minecraft.getInstance();
		if (state.mapId == null || mc.level == null) return;
		int mapId = state.mapId.id();
		MapInfo info = ClientState.mapInfo(mapId);
		if (info == null || !info.dimension().equals(mc.level.dimension().identifier().toString())) return;

		int lod = lodFor(mapId, state.distanceToCameraSq);
		Entry e = ENTRIES.computeIfAbsent(key(mapId, lod), k -> new Entry(mapId, lod));
		e.info = info;
		e.lastUsedTick = ticks;
		DioramaMesh mesh = e.mesh;
		if (mesh == null) mesh = anyLod(mapId, lod);
		if (mesh != null) {
			if (DRAWS.size() > 1024) DRAWS.clear();
			DRAWS.add(new Draw(mesh, new Matrix4f(poseStack.last().pose()), brightness(mc, state.lightCoords)));
		}
	}

	/** Enquanto o detalhe pedido não fica pronto, mostra o que já existir (evita o mapa "piscar" ao se aproximar). */
	private static DioramaMesh anyLod(int mapId, int wanted) {
		DioramaMesh best = null;
		int bestDiff = Integer.MAX_VALUE;
		for (int lod : LodPolicy.LOD) {
			Entry other = ENTRIES.get(key(mapId, lod));
			if (other != null && other.mesh != null && Math.abs(lod - wanted) < bestDiff) {
				best = other.mesh;
				bestDiff = Math.abs(lod - wanted);
			}
		}
		return best;
	}

	/**
	 * Luz do quadro → brilho da maquete (a malha é montada com luz cheia). A luz do céu é multiplicada pelo quanto o
	 * céu ilumina agora, então à noite a maquete escurece junto com o mundo e só a luz de tocha continua forte.
	 */
	private static float brightness(Minecraft mc, int lightCoords) {
		int block = (lightCoords >> 4) & 0xF;
		int sky = (lightCoords >> 20) & 0xF;
		float skyFactor = 1f;
		try {
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			skyFactor = mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partial);
		} catch (RuntimeException ignored) {
			// câmera ainda sem mundo: luz cheia
		}
		float light = Math.max(block / 15f, sky / 15f * skyFactor);
		return 0.3f + 0.7f * light;
	}

	/** Chamado no fim do passe sólido do jogo (mixin em {@code LevelRenderer.executeSolid}). */
	public static void draw(RenderPass pass) {
		if (DRAWS.isEmpty()) return;
		PreparedRenderType prep = RenderTypes.text(TextureAtlas.LOCATION_BLOCKS).prepare();
		Matrix4f view = RenderSystem.getModelViewMatrixCopy();
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		drawAll(pass, prep, view, indices, false);
		drawAll(pass, prep, view, indices, true);
		DRAWS.clear();
	}

	private static void drawAll(RenderPass pass, PreparedRenderType prep, Matrix4f view, RenderSystem.AutoStorageIndexBuffer indices, boolean water) {
		boolean bound = false;
		for (Draw d : DRAWS) {
			var buffer = water ? d.mesh.water : d.mesh.solid;
			int quads = water ? d.mesh.waterQuads : d.mesh.solidQuads;
			if (buffer == null || quads == 0) continue;
			if (!bound) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(water ? WATER : SOLID));
				RenderSystem.bindDefaultUniforms(pass);
				for (PreparedRenderType.Texture tex : prep.textures()) pass.setUniform(tex.name(), tex.textureView(), tex.sampler());
				bound = true;
			}
			float b = d.brightness;
			GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view).mul(d.pose), new Vector4f(b, b, b, 1f));
			pass.setUniform("DynamicTransforms", transform);
			int indexCount = quads * 6;
			pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
			pass.setVertexBuffer(0, buffer.slice());
			pass.drawIndexed(indexCount, 1, 0, 0, 0);
		}
	}

	public static void tick(Minecraft mc) {
		ticks++;
		if (mc.level == null) {
			clear();
			return;
		}
		double verticalScale = HolomapConfig.get().verticalScale;
		if (verticalScale != appliedVerticalScale) {
			appliedVerticalScale = verticalScale;
			invalidate();
		}
		int baseY = baseY(mc.level);
		Entry next = null;
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		Iterator<Entry> it = ENTRIES.values().iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			// montagem terminou: sobe para a GPU e troca a malha antiga
			if (e.pending != null && e.pending.isDone()) {
				try {
					DioramaBuilder.Built built = e.pending.join();
					DioramaMesh old = e.mesh;
					e.mesh = DioramaMesh.upload(built, "HoloMap map " + e.mapId);
					if (old != null) old.close();
					e.builtStamp = e.pendingStamp;
				} catch (RuntimeException ex) {
					Holomap.LOGGER.warn("Failed to build the diorama of map {}", e.mapId, ex);
				}
				e.pending = null;
			}
			if (ticks - e.lastUsedTick > UNUSED_EVICT_TICKS) {
				if (e.pending == null) {
					if (e.mesh != null) e.mesh.close();
					it.remove();
				}
				continue;
			}
			if (ticks - e.lastUsedTick > ACTIVE_TICKS || e.info == null) continue;

			int k = 1 << e.info.scale();
			int ax = e.info.centerX() - 64 * k, az = e.info.centerZ() - 64 * k;
			int bx = ax + 128 * k - 1, bz = az + 128 * k - 1;
			minX = Math.min(minX, ax);
			minZ = Math.min(minZ, az);
			maxX = Math.max(maxX, bx);
			maxZ = Math.max(maxZ, bz);

			if (e.pending != null || ticks - e.lastBuildTick < (long) MIN_REBUILD_TICKS * e.lod) continue;
			boolean first = e.builtStamp < 0;
			if (!first) {
				if (ticks - lastRebuildTick < GLOBAL_REBUILD_TICKS || ticks - e.lastCheckTick < CHECK_INTERVAL) continue;
				e.lastCheckTick = ticks;
				if (!ClientTerrain.changedSince(mc.level.dimension(), ax, az, bx, bz, e.builtStamp)) continue;
			}
			// a primeira montagem passa na frente; entre as outras, a mais detalhada (mais perto da câmera)
			if (next == null || first && next.builtStamp >= 0 || first == next.builtStamp < 0 && e.lod < next.lod) next = e;
		}
		if (next != null) schedule(mc, next, baseY, (float) verticalScale);
		enforceGpuBudget();
		if (minX != Integer.MAX_VALUE) ClientState.requestRegion(minX, minZ, maxX, maxZ);
	}

	/** Copia a área na thread do jogo (uma por tick, no máximo) e manda a parte pesada para a outra thread. */
	private static void schedule(Minecraft mc, Entry e, int baseY, float verticalScale) {
		if (e.builtStamp >= 0) lastRebuildTick = ticks;
		ClientTerrain.Reader reader = ClientTerrain.reader(mc.level.dimension());
		long snapStart = System.nanoTime();
		DioramaBuilder.Snapshot snap = DioramaBuilder.snapshot(reader, e.info.centerX(), e.info.centerZ(), e.info.scale(), e.lod, baseY,
			verticalScale, deepFill(mc.level));
		SNAPSHOT_NANOS.addAndGet(System.nanoTime() - snapStart);
		e.pendingStamp = ClientTerrain.modCount();
		e.pending = CompletableFuture.supplyAsync(() -> timed(snap), WORKER);
		e.lastBuildTick = ticks;
		e.lastCheckTick = ticks;
	}

	private static DioramaBuilder.Built timed(DioramaBuilder.Snapshot snap) {
		long start = System.nanoTime();
		DioramaBuilder.Built built = snap.mesh();
		long took = System.nanoTime() - start;
		lastBuildNanos = took;
		BUILD_NANOS.addAndGet(took);
		BUILDS.incrementAndGet();
		return built;
	}

	/**
	 * Acima do teto de memória de vídeo, solta as maquetes menos usadas recentemente (níveis de detalhe que ficaram
	 * para trás, quadros fora da tela). As que estão sendo desenhadas agora ficam.
	 */
	private static void enforceGpuBudget() {
		long limit = HolomapConfig.get().gpuMemoryMb * 1048576L;
		long total = 0;
		for (Entry e : ENTRIES.values()) if (e.mesh != null) total += e.mesh.bytes;
		if (total <= limit) return;
		List<Map.Entry<Long, Entry>> byAge = new ArrayList<>();
		for (Map.Entry<Long, Entry> me : ENTRIES.entrySet()) {
			Entry e = me.getValue();
			if (e.mesh != null && e.pending == null && e.lastUsedTick < ticks - 1) byAge.add(me);
		}
		byAge.sort(Comparator.comparingLong(me -> me.getValue().lastUsedTick));
		for (Map.Entry<Long, Entry> me : byAge) {
			if (total <= limit) break;
			// sai inteira: se o quadro voltar a ser visto, ela é montada de novo
			total -= me.getValue().mesh.bytes;
			me.getValue().mesh.close();
			ENTRIES.remove(me.getKey());
		}
	}

	public static Stats stats() {
		int meshes = 0, building = 0;
		long quads = 0, bytes = 0;
		for (Entry e : ENTRIES.values()) {
			if (e.mesh != null) {
				meshes++;
				quads += e.mesh.quads();
				bytes += e.mesh.bytes;
			}
			if (e.pending != null) building++;
		}
		int builds = BUILDS.get();
		double avg = builds == 0 ? 0 : BUILD_NANOS.get() / 1e6 / builds;
		double snap = builds == 0 ? 0 : SNAPSHOT_NANOS.get() / 1e6 / builds;
		return new Stats(meshes, quads, bytes, building, builds, lastBuildNanos / 1e6, avg, snap,
			ClientTerrain.knownChunks(), ClientTerrain.receivedChunks(), ClientTerrain.localChanges(), ClientTerrain.serverChanges(),
			ClientState.serverProtocol());
	}

	/** Texturas ou escala vertical mudaram: remonta tudo. */
	public static void invalidate() {
		for (Entry e : ENTRIES.values()) e.builtStamp = -1;
	}

	public static void clear() {
		for (Entry e : ENTRIES.values()) {
			if (e.mesh != null) e.mesh.close();
			if (e.pending != null) e.pending.thenAccept(DioramaBuilder.Built::close);
		}
		ENTRIES.clear();
		DRAWS.clear();
		LAST_LOD.clear();
	}
}
