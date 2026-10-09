package com.holomap.terrain;

import java.util.Arrays;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Lê as colunas de um chunk carregado: do bloco mais alto para baixo até o chão firme (bloco sólido e opaco que
 * não seja tronco nem folha), mais um bloco embaixo dele. Roda igual no cliente e no servidor.
 */
public final class TerrainSampler {
	/** No Nether a busca começa abaixo do teto de bedrock. */
	private static final int CEILING_SCAN_START = 100;

	private TerrainSampler() {
	}

	private static boolean isGround(BlockState state) {
		return state.isSolidRender() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES);
	}

	public static ChunkSummary sample(Level level, LevelChunk chunk) {
		ChunkSummary s = new ChunkSummary();
		boolean ceiling = level.dimensionType().hasCeiling();
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();
		Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] out = new int[256 * 6];
		int n = 0;

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = ChunkSummary.index(lx, lz);
				int x = baseX + lx;
				int z = baseZ + lz;
				s.offsets[i] = n;
				int y = ceiling
					? findFloorBelowCeiling(chunk, pos, x, z, minY)
					: chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
				while (y > minY && chunk.getBlockState(pos.set(x, y, z)).isAir()) y--;
				if (y <= minY) {
					s.top[i] = ChunkSummary.UNKNOWN;
					continue;
				}
				s.top[i] = (short) y;
				s.biome[i] = (short) biomes.getId(level.getBiome(pos.set(x, y + 1, z)).value());

				// pilha do topo até o chão firme, e mais um bloco embaixo
				boolean groundFound = false;
				for (int k = 0; k < ChunkSummary.MAX_STACK && y - k > minY; k++) {
					BlockState state = chunk.getBlockState(pos.set(x, y - k, z));
					if (n == out.length) out = Arrays.copyOf(out, out.length * 2);
					out[n++] = Block.getId(state);
					if (groundFound) break;
					if (isGround(state)) groundFound = true;
				}
			}
		}
		s.offsets[256] = n;
		s.states = Arrays.copyOf(out, n);
		return s;
	}

	private static int findFloorBelowCeiling(LevelChunk chunk, BlockPos.MutableBlockPos pos, int x, int z, int minY) {
		int y = CEILING_SCAN_START;
		// sai do bloco sólido em que começou
		while (y > minY && !chunk.getBlockState(pos.set(x, y, z)).isAir()) y--;
		// desce pelo ar até achar o chão
		while (y > minY && chunk.getBlockState(pos.set(x, y, z)).isAir()) y--;
		return y;
	}
}
