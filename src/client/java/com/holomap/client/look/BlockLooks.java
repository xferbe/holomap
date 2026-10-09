package com.holomap.client.look;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Como cada bloco aparece no jogo, para a maquete: as camadas de textura de cada uma das 6 faces, tiradas do
 * próprio modelo do bloco (resource packs entram de graça), a tinta de bioma e se ele esconde o vizinho.
 * Só roda na thread do cliente; o resultado ({@link Look}) é imutável e pode ir para a thread que monta a malha.
 */
public final class BlockLooks {
	/** Uma camada de textura de uma face (a lateral da grama tem duas: terra + borda verde tingida). */
	public record Layer(TextureAtlasSprite sprite, int tintIndex) {
		public boolean tinted() {
			return tintIndex >= 0;
		}
	}

	public static final class Look {
		public final int id;
		public final BlockState state;
		/** Camadas por face, no índice de {@link Direction#get3DDataValue()} (baixo, cima, norte, sul, oeste, leste). */
		public final Layer[][] faces;
		/** Aparece na maquete (tem forma sólida ou é fluido); flor, grama alta e tocha ficam de fora. */
		public final boolean renderable;
		/** Cubo cheio e opaco: esconde a face do vizinho encostada nele. */
		public final boolean occludes;
		public final boolean water;

		Look(int id, BlockState state, Layer[][] faces, boolean renderable, boolean occludes, boolean water) {
			this.id = id;
			this.state = state;
			this.faces = faces;
			this.renderable = renderable;
			this.occludes = occludes;
			this.water = water;
		}

		/** Maior tintIndex usado em qualquer camada (para dimensionar o vetor de tintas). */
		public int maxTintIndex() {
			int max = -1;
			for (Layer[] face : faces) for (Layer l : face) max = Math.max(max, l.tintIndex());
			return max;
		}
	}

	public static final Look AIR = new Look(0, Blocks.AIR.defaultBlockState(), new Layer[6][0], false, false, false);

	private static final Int2ObjectOpenHashMap<Look> CACHE = new Int2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<int[]> TINTS = new Long2ObjectOpenHashMap<>();
	private static TextureAtlasSprite reloadProbe;

	private BlockLooks() {
	}

	/** Chamado a cada tick: se o atlas foi refeito (troca de resource pack), os sprites antigos não valem mais. */
	public static boolean checkReload() {
		TextureAtlasSprite probe = blockAtlas().getSprite(Identifier.withDefaultNamespace("block/stone"));
		if (probe == reloadProbe) return false;
		reloadProbe = probe;
		CACHE.clear();
		TINTS.clear();
		return true;
	}

	/** Ao trocar de mundo: os ids de bioma podem ser outros. */
	public static void clearTints() {
		TINTS.clear();
	}

	private static TextureAtlas blockAtlas() {
		return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
	}

	public static Look of(int stateId) {
		if (stateId == 0) return AIR;
		Look look = CACHE.get(stateId);
		if (look == null) {
			look = build(stateId, Block.stateById(stateId));
			CACHE.put(stateId, look);
		}
		return look;
	}

	private static Look build(int id, BlockState state) {
		if (state.isAir()) return AIR;
		FluidState fluid = state.getFluidState();
		boolean water = fluid.is(Fluids.WATER) && state.getBlock() == Blocks.WATER;
		if (water) {
			TextureAtlasSprite still = blockAtlas().getSprite(Identifier.withDefaultNamespace("block/water_still"));
			Layer[] l = {new Layer(still, 0)};
			return new Look(id, state, new Layer[][] {l, l, l, l, l, l}, true, false, true);
		}

		boolean renderable = !fluid.isEmpty()
			|| state.getRenderShape() != RenderShape.INVISIBLE && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		if (!renderable) return new Look(id, state, new Layer[6][0], false, false, false);

		BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);
		TextureAtlasSprite particle = model.particleMaterial().sprite();
		Layer[][] faces = new Layer[6][];
		for (Direction d : Direction.values()) faces[d.get3DDataValue()] = layersFor(parts, d, particle);
		return new Look(id, state, faces, true, state.isSolidRender(), false);
	}

	/** Todas as camadas da face (na ordem do modelo); se o modelo não tiver essa face, usa a textura de partícula. */
	private static Layer[] layersFor(List<BlockStateModelPart> parts, Direction face, TextureAtlasSprite fallback) {
		List<Layer> layers = new ArrayList<>();
		for (BlockStateModelPart part : parts) {
			for (BakedQuad q : part.getQuads(face)) layers.add(new Layer(q.materialInfo().sprite(), q.materialInfo().tintIndex()));
		}
		if (layers.isEmpty()) {
			for (BlockStateModelPart part : parts) {
				for (BakedQuad q : part.getQuads(null)) {
					if (q.direction() == face) layers.add(new Layer(q.materialInfo().sprite(), q.materialInfo().tintIndex()));
				}
			}
		}
		if (layers.isEmpty()) layers.add(new Layer(fallback, -1));
		// um modelo pode repetir a mesma textura em vários quads da face (escadas, lajes): basta uma de cada
		List<Layer> unique = new ArrayList<>();
		for (Layer l : layers) {
			if (!unique.contains(l)) unique.add(l);
			if (unique.size() == 2) break;
		}
		return unique.toArray(new Layer[0]);
	}

	// ---- tinta de bioma ----

	/** Tintas (ARGB) por tintIndex do bloco naquele bioma; posição sem tinta fica branca. */
	public static int[] tints(Look look, int biomeId) {
		int max = look.maxTintIndex();
		if (max < 0) return null;
		long key = ((long) look.id << 20) ^ (biomeId & 0xFFFF);
		int[] cached = TINTS.get(key);
		if (cached != null) return cached;
		int[] out = new int[max + 1];
		Biome biome = biome(biomeId);
		for (int t = 0; t <= max; t++) out[t] = tint(look, t, biome);
		TINTS.put(key, out);
		return out;
	}

	private static int tint(Look look, int tintIndex, Biome biome) {
		if (biome == null) return 0xFFFFFFFF;
		// a tinta da água não passa pela tabela de cores de bloco (é do renderizador de fluido): vem direto do bioma
		if (look.water) return 0xFF000000 | biome.getWaterColor();
		BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(look.state, tintIndex);
		if (source == null) return 0xFFFFFFFF;
		try {
			return 0xFF000000 | source.colorInWorld(look.state, new BiomeTint(biome), BlockPos.ZERO);
		} catch (RuntimeException e) {
			return 0xFF000000 | source.color(look.state);
		}
	}

	private static Biome biome(int id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		return mc.level.registryAccess().lookupOrThrow(Registries.BIOME).byId(id);
	}

	/** Um "mundo" mínimo que só sabe responder a tinta de um bioma — é o que as fontes de cor do jogo pedem. */
	private record BiomeTint(Biome biome) implements BlockAndTintGetter {
		@Override
		public int getBlockTint(BlockPos pos, ColorResolver resolver) {
			return resolver.getColor(biome, pos.getX(), pos.getZ());
		}

		@Override
		public CardinalLighting cardinalLighting() {
			return CardinalLighting.DEFAULT;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			return LevelLightEngine.EMPTY;
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return 0;
		}

		@Override
		public int getMinY() {
			return 0;
		}
	}
}
