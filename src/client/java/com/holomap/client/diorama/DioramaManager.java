package com.holomap.client.diorama;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.holomap.Holomap;
import com.holomap.client.ClientState;
import com.holomap.client.ClientState.PlayerView;
import com.holomap.client.MapSprites;
import com.holomap.client.terrain.ClientTerrain;
import com.holomap.client.terrain.TerrainColors;
import com.holomap.net.HolomapNet.MapInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Dono das maquetes: uma por mapa e nível de detalhe, montada em segundo plano, subida para a GPU uma vez
 * e refeita só quando algum chunk da área do mapa muda. Mapas lado a lado viram um mapa 3D grande sozinhos,
 * porque cada um desenha a própria área no próprio quadro.
 */
public final class DioramaManager {
	/** Quadros perto mostram cada coluna; longe, células de 2, 4 ou 8 colunas (menos faces, mesma aparência à distância). */
	private static final double[] LOD_DISTANCE = {8, 20, 40};
	private static final int[] LOD = {1, 2, 4, 8};
	/** Uma área é conferida no máximo a cada 4 ticks e remontada no máximo 4 vezes por segundo. */
	private static final int CHECK_INTERVAL = 4;
	private static final int MIN_REBUILD_TICKS = 5;
	private static final int UNUSED_EVICT_TICKS = 200;
	private static final int ACTIVE_TICKS = 40;

	private static final RenderPipeline SOLID = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
		.withLocation(Holomap.id("pipeline/diorama_solid"))
		.withColorTargetState(ColorTargetState.DEFAULT)
		.build());
	private static final RenderPipeline WATER = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
		.withLocation(Holomap.id("pipeline/diorama_water"))
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build());

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Holomap diorama");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private static final class Entry {
		final int mapId, lod;
		MapInfo info;
		DioramaMesh mesh;
		long builtStamp = -1;
		long lastBuildTick = -MIN_REBUILD_TICKS;
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

	private static final Map<Long, Entry> ENTRIES = new HashMap<>();
	private static final List<Draw> DRAWS = new ArrayList<>();
	private static long ticks;

	private DioramaManager() {
	}

	private static long key(int mapId, int lod) {
		return ((long) mapId << 8) | lod;
	}

	/** Y que fica rente à textura do mapa: um pouco abaixo do nível do mar, para lagos e vales terem fundo. */
	public static int baseY(ClientLevel level) {
		return Math.max(level.getMinY(), level.getSeaLevel() - DioramaBuilder.DEPTH_BELOW_SEA);
	}

	private static int lodFor(double distSq) {
		for (int i = 0; i < LOD_DISTANCE.length; i++) {
			if (distSq <= LOD_DISTANCE[i] * LOD_DISTANCE[i]) return LOD[i];
		}
		return LOD[LOD.length - 1];
	}

	/**
	 * Chamado pelo mixin do {@code ItemFrameRenderer}, com a pose já no espaço do mapa (128×128). Agenda o desenho
	 * da maquete (no passe sólido) e desenha as setas dos jogadores por cima do relevo.
	 */
	public static void submit(ItemFrameRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (state.mapId == null || mc.level == null) return;
		int mapId = state.mapId.id();
		MapInfo info = ClientState.mapInfo(mapId);
		if (info == null || !info.dimension().equals(mc.level.dimension().identifier().toString())) return;

		int lod = lodFor(state.distanceToCameraSq);
		Entry e = ENTRIES.computeIfAbsent(key(mapId, lod), k -> new Entry(mapId, lod));
		e.info = info;
		e.lastUsedTick = ticks;
		DioramaMesh mesh = e.mesh;
		if (mesh == null) mesh = anyLod(mapId, lod);
		if (mesh != null) {
			if (DRAWS.size() > 1024) DRAWS.clear();
			DRAWS.add(new Draw(mesh, new Matrix4f(poseStack.last().pose()), brightness(state.lightCoords)));
		}
		submitPlayers(mc, info, poseStack, collector, state.lightCoords);
	}

	/** Enquanto o detalhe pedido não fica pronto, mostra o que já existir (evita o mapa "piscar" ao se aproximar). */
	private static DioramaMesh anyLod(int mapId, int wanted) {
		DioramaMesh best = null;
		int bestDiff = Integer.MAX_VALUE;
		for (int lod : LOD) {
			Entry other = ENTRIES.get(key(mapId, lod));
			if (other != null && other.mesh != null && Math.abs(lod - wanted) < bestDiff) {
				best = other.mesh;
				bestDiff = Math.abs(lod - wanted);
			}
		}
		return best;
	}

	/** Luz do quadro → brilho da maquete (a malha é montada com luz cheia). */
	private static float brightness(int lightCoords) {
		int block = (lightCoords >> 4) & 0xF;
		int sky = (lightCoords >> 20) & 0xF;
		return 0.35f + 0.65f * Math.max(block, sky) / 15f;
	}

	/** Setas do mapa vanilla em cima do relevo, onde cada jogador está (inclusive quem está longe). */
	private static void submitPlayers(Minecraft mc, MapInfo info, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		int k = 1 << info.scale();
		double originX = info.centerX() - 64.0 * k, originZ = info.centerZ() - 64.0 * k;
		int baseY = baseY(mc.level);
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		TextureAtlasSprite arrow = MapSprites.player();
		for (PlayerView p : ClientState.players(partial)) {
			float px = (float) ((p.pos().x - originX) / k), py = (float) ((p.pos().z - originZ) / k);
			if (px < 0 || py < 0 || px > 128 || py > 128) continue;
			float h = (float) Math.max(0, (p.pos().y - baseY) / k);
			float z = -h - 0.6f;
			int color = p.self() ? 0xFFFFFFFF : TerrainColors.playerColor(p.id());
			float yaw = p.yaw();
			collector.submitCustomGeometry(poseStack, RenderTypes.text(arrow.atlasLocation()), (pose, buf) -> arrow(pose, buf, arrow, px, py, z, yaw, color));
		}
	}

	private static void arrow(PoseStack.Pose pose, VertexConsumer buf, TextureAtlasSprite s, float x, float y, float z, float yaw, int color) {
		double r = Math.toRadians(yaw);
		float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
		float h = 3f;
		float[][] c = {{-h, h}, {h, h}, {h, -h}, {-h, -h}};
		float[][] uv = {{s.getU0(), s.getV0()}, {s.getU1(), s.getV0()}, {s.getU1(), s.getV1()}, {s.getU0(), s.getV1()}};
		for (int i = 0; i < 4; i++) {
			float rx = c[i][0] * cos - c[i][1] * sin, ry = c[i][0] * sin + c[i][1] * cos;
			buf.addVertex(pose, x + rx, y + ry, z).setColor(color).setUv(uv[i][0], uv[i][1]).setLight(0xF000F0);
		}
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
		ClientTerrain.Reader reader = null;
		boolean scheduled = false;
		int baseY = baseY(mc.level);
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		Iterator<Entry> it = ENTRIES.values().iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			// montagem terminou: sobe para a GPU e troca a malha antiga
			if (e.pending != null && e.pending.isDone()) {
				try {
					DioramaBuilder.Built built = e.pending.join();
					DioramaMesh old = e.mesh;
					Holomap.LOGGER.debug("Maquete do mapa {} (detalhe {}): {} faces sólidas, {} de água", e.mapId, e.lod, built.solidQuads(), built.waterQuads());
					e.mesh = DioramaMesh.upload(built, "Holomap map " + e.mapId);
					if (old != null) old.close();
					e.builtStamp = e.pendingStamp;
				} catch (RuntimeException ex) {
					Holomap.LOGGER.warn("Falha montando a maquete do mapa {}", e.mapId, ex);
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

			if (scheduled || e.pending != null || ticks - e.lastBuildTick < MIN_REBUILD_TICKS) continue;
			if (e.builtStamp >= 0) {
				if (ticks - e.lastCheckTick < CHECK_INTERVAL) continue;
				e.lastCheckTick = ticks;
				if (!ClientTerrain.changedSince(mc.level.dimension(), ax, az, bx, bz, e.builtStamp)) continue;
			}
			// uma cópia por tick, no máximo; a parte pesada vai para a outra thread
			if (reader == null) reader = ClientTerrain.reader(mc.level.dimension());
			DioramaBuilder.Snapshot snap = DioramaBuilder.snapshot(reader, e.info.centerX(), e.info.centerZ(), e.info.scale(), e.lod, baseY);
			e.pendingStamp = ClientTerrain.modCount();
			e.pending = CompletableFuture.supplyAsync(snap::mesh, WORKER);
			e.lastBuildTick = ticks;
			e.lastCheckTick = ticks;
			scheduled = true;
		}
		if (minX != Integer.MAX_VALUE) ClientState.requestRegion(minX, minZ, maxX, maxZ);
	}

	/** Texturas mudaram (resource pack): remonta tudo. */
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
	}
}
