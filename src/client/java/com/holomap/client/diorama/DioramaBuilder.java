package com.holomap.client.diorama;

import java.util.HashMap;
import java.util.Map;

import com.holomap.client.look.BlockLooks;
import com.holomap.client.look.BlockLooks.Layer;
import com.holomap.client.look.BlockLooks.Look;
import com.holomap.client.terrain.ClientTerrain;
import com.holomap.terrain.ChunkSummary;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Monta a maquete 3D de um mapa em duas etapas:
 * <ol>
 *   <li>{@link #snapshot} — na thread do cliente, copia as colunas da área do mapa e resolve textura/tinta de cada bloco;</li>
 *   <li>{@link Snapshot#mesh()} — em outra thread, gera só as faces visíveis, já no espaço do mapa (128×128 "pixels").</li>
 * </ol>
 * Cada célula é um cubo de {@code lod} pixels do mapa; a altura usa a mesma escala, então o relevo é fiel.
 */
public final class DioramaBuilder {
	/** Luz embutida nos vértices (cheia); o brilho real do quadro entra como multiplicador na hora de desenhar. */
	private static final int FULL_LIGHT = 0xF000F0;
	/** Sombreamento por face igual ao do terreno do jogo: baixo, cima, norte, sul, oeste, leste. */
	private static final float[] FACE_SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};
	/** A maquete começa um pouco à frente da textura do mapa (que fica em z = -0,01). */
	private static final float BASE_OFFSET = 0.02f;
	private static final float WATER_SURFACE = 0.875f;
	/** A textura de água já é semitransparente; a lâmina vai com alfa cheio e o fundo embaixo dela escurece. */
	private static final int WATER_ALPHA = 0xFF;
	private static final float UNDERWATER_SHADE = 0.55f;
	/** Quantos níveis abaixo do nível do mar a maquete ainda mostra. */
	public static final int DEPTH_BELOW_SEA = 24;

	private DioramaBuilder() {
	}

	/** Copia o que é preciso da área do mapa. Roda na thread do cliente (texturas e tintas só existem lá). */
	public static Snapshot snapshot(ClientTerrain.Reader reader, int centerX, int centerZ, int scale, int lod, int baseY) {
		int k = 1 << scale;
		int n = 128 / lod;
		int cb = k * lod;
		int originX = centerX - 64 * k;
		int originZ = centerZ - 64 * k;
		Snapshot s = new Snapshot(n, lod);
		Map<Long, int[]> tints = s.tints;

		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				int c = j * n + i;
				int wx = originX + i * cb + cb / 2;
				int wz = originZ + j * cb + cb / 2;
				ChunkSummary chunk = reader.chunk(wx, wz);
				s.topL[c] = -1;
				if (chunk == null) continue;
				int idx = ChunkSummary.index(wx & 15, wz & 15);
				int top = chunk.top[idx];
				if (top == ChunkSummary.UNKNOWN) continue;
				int bottomY = chunk.bottom(idx);
				int biome = chunk.biome[idx];

				int topL = Math.floorDiv(top - baseY, cb);
				int botL = Math.max(0, Math.floorDiv(bottomY - baseY, cb));
				if (topL < 0) {
					topL = 0;
					botL = 0;
				}
				botL = Math.min(botL, topL);
				Look[] stack = new Look[topL - botL + 1];
				for (int level = topL; level >= botL; level--) {
					// o bloco que aparece na célula é o mais alto dela que tem forma
					int yHigh = Math.min(top, baseY + level * cb + cb - 1);
					int yLow = Math.max(baseY + level * cb, yHigh - 15);
					Look found = BlockLooks.AIR;
					for (int y = yHigh; y >= yLow; y--) {
						Look l = BlockLooks.of(chunk.stateAt(idx, y));
						if (l.renderable) {
							found = l;
							break;
						}
					}
					if (top < baseY && level == 0) found = firstRenderable(chunk, idx, top, bottomY);
					stack[topL - level] = found;
					addTints(tints, found, biome);
				}
				Look fill = firstRenderable(chunk, idx, bottomY, bottomY - 4);
				if (fill == BlockLooks.AIR) fill = stack[stack.length - 1];
				addTints(tints, fill, biome);

				s.topL[c] = topL;
				s.botL[c] = botL;
				s.stack[c] = stack;
				s.fill[c] = fill;
				s.biome[c] = biome;
			}
		}
		return s;
	}

	private static Look firstRenderable(ChunkSummary chunk, int idx, int fromY, int toY) {
		for (int y = fromY; y >= toY; y--) {
			Look l = BlockLooks.of(chunk.stateAt(idx, y));
			if (l.renderable) return l;
		}
		return BlockLooks.AIR;
	}

	private static void addTints(Map<Long, int[]> tints, Look look, int biome) {
		if (!look.renderable) return;
		long key = tintKey(look, biome);
		if (!tints.containsKey(key)) tints.put(key, BlockLooks.tints(look, biome));
	}

	private static long tintKey(Look look, int biome) {
		return ((long) look.id << 20) ^ (biome & 0xFFFF);
	}

	/** Cópia imutável da área; {@link #mesh()} pode rodar em qualquer thread. */
	public static final class Snapshot {
		final int n, lod;
		final int[] topL, botL, biome;
		final Look[][] stack;
		final Look[] fill;
		final Map<Long, int[]> tints = new HashMap<>();

		Snapshot(int n, int lod) {
			this.n = n;
			this.lod = lod;
			topL = new int[n * n];
			botL = new int[n * n];
			biome = new int[n * n];
			stack = new Look[n * n][];
			fill = new Look[n * n];
		}

		private Look at(int i, int j, int level) {
			if (i < 0 || j < 0 || i >= n || j >= n || level < 0) return BlockLooks.AIR;
			int c = j * n + i;
			int top = topL[c];
			if (top < 0 || level > top) return BlockLooks.AIR;
			if (level < botL[c]) return fill[c];
			return stack[c][top - level];
		}

		private boolean inside(int i, int j) {
			return i >= 0 && j >= 0 && i < n && j < n;
		}

		/** Gera as faces visíveis. Devolve null se a área ainda não tem nenhum terreno conhecido. */
		public Built mesh() {
			ByteBufferBuilder solidBytes = new ByteBufferBuilder(1 << 20);
			ByteBufferBuilder waterBytes = new ByteBufferBuilder(1 << 16);
			BufferBuilder solid = new BufferBuilder(solidBytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
			BufferBuilder water = new BufferBuilder(waterBytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
			int solidQuads = 0, waterQuads = 0;
			int[][] dirs = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

			for (int j = 0; j < n; j++) {
				for (int i = 0; i < n; i++) {
					int c = j * n + i;
					if (topL[c] < 0) continue;
					// abaixo do menor "começo de pilha" da vizinhança tudo é maciço e escondido; nas bordas mostra o corte até a base
					int start = botL[c];
					for (int d = 2; d < 6; d++) {
						int ni = i + dirs[d][0], nj = j + dirs[d][2];
						if (!inside(ni, nj) || topL[nj * n + ni] < 0) {
							start = 0;
							break;
						}
						start = Math.min(start, botL[nj * n + ni]);
					}
					for (int level = Math.max(0, start - 1); level <= topL[c]; level++) {
						Look look = at(i, j, level);
						if (!look.renderable) continue;
						int[] tint = tints.get(tintKey(look, biome[c]));
						if (look.water) {
							Look above = at(i, j, level + 1);
							if (!above.water && !above.occludes) {
								waterQuads += face(water, look, tint, 1, i, level, j, WATER_SURFACE, WATER_ALPHA, 1f);
							}
							for (int d = 2; d < 6; d++) {
								if (!inside(i + dirs[d][0], j + dirs[d][2])) {
									float h = above.water ? 1f : WATER_SURFACE;
									waterQuads += face(water, look, tint, d, i, level, j, h, WATER_ALPHA, 1f);
								}
							}
							continue;
						}
						for (int d = 0; d < 6; d++) {
							if (d == 0 && level == 0) continue;
							Look nb = at(i + dirs[d][0], j + dirs[d][2], level + dirs[d][1]);
							boolean hidden = nb.occludes || (nb == look && !look.water);
							if (hidden) continue;
							// fundo de rio/mar mais escuro, para a água parecer funda
							float shade = at(i, j, level + 1).water ? UNDERWATER_SHADE : 1f;
							solidQuads += face(solid, look, tint, d, i, level, j, 1f, 0xFF, shade);
						}
					}
				}
			}
			MeshData solidMesh = solidQuads > 0 ? solid.build() : null;
			MeshData waterMesh = waterQuads > 0 ? water.build() : null;
			return new Built(solidMesh, solidBytes, solidQuads, waterMesh, waterBytes, waterQuads);
		}

		/**
		 * Emite uma face de cubo (todas as camadas) no espaço do mapa. O cubo da célula (i, level, j) ocupa
		 * x ∈ [i, i+1]·lod, y (linhas do mapa = sul) ∈ [j, j+1]·lod e altura ∈ [level, level+1]·lod — a altura
		 * cresce para o lado do observador, que no espaço do mapa é z negativo.
		 */
		private int face(BufferBuilder out, Look look, int[] tint, int dir, int i, int level, int j, float topFrac, int alpha, float extraShade) {
			float f = lod;
			float x0 = i * f, x1 = x0 + f;
			float z0 = j * f, z1 = z0 + f;
			float y0 = level * f, y1 = y0 + f * topFrac;
			int quads = 0;
			for (Layer layer : look.faces[dir]) {
				int color = layer.tinted() && tint != null && layer.tintIndex() < tint.length ? tint[layer.tintIndex()] : 0xFFFFFFFF;
				color = shade(color, FACE_SHADE[dir] * extraShade, alpha);
				TextureAtlasSprite s = layer.sprite();
				float u0 = s.getU0(), u1 = s.getU1(), v0 = s.getV0(), v1 = s.getV1();
				float vb = v0 + (v1 - v0) * topFrac;
				switch (dir) {
					case 1 -> quad(out, color, x0, y1, z0, u0, v0, x0, y1, z1, u0, v1, x1, y1, z1, u1, v1, x1, y1, z0, u1, v0);
					case 0 -> quad(out, color, x0, y0, z1, u0, v0, x0, y0, z0, u0, v1, x1, y0, z0, u1, v1, x1, y0, z1, u1, v0);
					case 2 -> quad(out, color, x1, y1, z0, u0, v0, x1, y0, z0, u0, vb, x0, y0, z0, u1, vb, x0, y1, z0, u1, v0);
					case 3 -> quad(out, color, x0, y1, z1, u0, v0, x0, y0, z1, u0, vb, x1, y0, z1, u1, vb, x1, y1, z1, u1, v0);
					case 4 -> quad(out, color, x0, y1, z0, u0, v0, x0, y0, z0, u0, vb, x0, y0, z1, u1, vb, x0, y1, z1, u1, v0);
					default -> quad(out, color, x1, y1, z1, u0, v0, x1, y0, z1, u0, vb, x1, y0, z0, u1, vb, x1, y1, z0, u1, v0);
				}
				quads++;
			}
			return quads;
		}

		/** Converte de (leste, cima, sul) para o espaço do mapa (x, y, z) = (leste, sul, -cima). */
		private static void quad(BufferBuilder out, int color,
								 float ax, float ay, float az, float au, float av,
								 float bx, float by, float bz, float bu, float bv,
								 float cx, float cy, float cz, float cu, float cv,
								 float dx, float dy, float dz, float du, float dv) {
			out.addVertex(ax, az, -ay - BASE_OFFSET).setUv(au, av).setLight(FULL_LIGHT).setColor(color);
			out.addVertex(bx, bz, -by - BASE_OFFSET).setUv(bu, bv).setLight(FULL_LIGHT).setColor(color);
			out.addVertex(cx, cz, -cy - BASE_OFFSET).setUv(cu, cv).setLight(FULL_LIGHT).setColor(color);
			out.addVertex(dx, dz, -dy - BASE_OFFSET).setUv(du, dv).setLight(FULL_LIGHT).setColor(color);
		}

		private static int shade(int argb, float f, int alpha) {
			int r = (int) (((argb >> 16) & 0xFF) * f);
			int g = (int) (((argb >> 8) & 0xFF) * f);
			int b = (int) ((argb & 0xFF) * f);
			return alpha << 24 | r << 16 | g << 8 | b;
		}
	}

	/** Resultado da montagem: os vértices prontos para subir para a GPU (na thread de render). */
	public record Built(MeshData solid, ByteBufferBuilder solidBytes, int solidQuads,
						MeshData water, ByteBufferBuilder waterBytes, int waterQuads) implements AutoCloseable {
		@Override
		public void close() {
			if (solid != null) solid.close();
			if (water != null) water.close();
			solidBytes.close();
			waterBytes.close();
		}
	}
}
