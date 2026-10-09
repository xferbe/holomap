package com.holomap.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import com.holomap.HolomapConfig;
import com.holomap.client.diorama.DioramaManager;
import com.holomap.client.terrain.ClientTerrain;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Mundo normal com seed fixa: uma mesa de mapa (quadro deitado em cima de um bloco) e uma parede 3×3 de mapas
 * vizinhos (384×384 blocos, 576 chunks). Confere que as maquetes são montadas, mede o custo da parede (teste de
 * carga) e tira screenshots: da mesa, da torre construída ao vivo, da parede, de longe e à noite.
 * Rodar com {@code gradlew runClientGameTest}; as imagens ficam em {@code build/run/clientGameTest/screenshots}
 * e os números em {@code build/run/clientGameTest/holomap-stats.txt}.
 */
public class HolomapClientTest implements FabricClientGameTest {
	private static final int WALL = 3;
	/** Biomas que ficam bonitos na maquete: verde, árvores, sem neve. */
	private static final Set<ResourceKey<Biome>> SCENIC = Set.of(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.FOREST,
		Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST, Biomes.MEADOW, Biomes.CHERRY_GROVE, Biomes.SAVANNA, Biomes.RIVER);

	private static String cmd(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}

	/**
	 * Procura, pelo gerador do mundo (sem gerar chunks), a área de mapa mais bonita perto do spawn: quase toda em
	 * terra, com morros mas sem montanha. Devolve o centro dessa área, alinhado à grade dos mapas de escala 0.
	 */
	private static int[] findScenicMap(ServerLevel level, BlockPos spawn) {
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		int sea = level.getSeaLevel();
		int baseCx = Math.floorDiv(spawn.getX() + 64, 128) * 128, baseCz = Math.floorDiv(spawn.getZ() + 64, 128) * 128;
		int[] best = {baseCx, baseCz};
		double bestScore = Double.MAX_VALUE;
		for (int gx = -6; gx <= 6; gx++) {
			for (int gz = -6; gz <= 6; gz++) {
				int cx = baseCx + gx * 128, cz = baseCz + gz * 128;
				int land = 0, n = 0;
				double sum = 0, sumSq = 0;
				for (int dx = -60; dx <= 60; dx += 8) {
					for (int dz = -60; dz <= 60; dz += 8) {
						int h = gen.getBaseHeight(cx + dx, cz + dz, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
						n++;
						if (h > sea + 1) land++;
						sum += h;
						sumSq += (double) h * h;
					}
				}
				// o centro (onde fica a cena) precisa ser terra com folga
				int center = gen.getBaseHeight(cx, cz, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
				double mean = sum / n, std = Math.sqrt(Math.max(0, sumSq / n - mean * mean));
				if (land < n * 0.95 || center < sea + 3 || std > 16 || !scenic(level, cx, cz, center)) continue;
				double score = Math.abs(std - 9) + (Math.abs(gx) + Math.abs(gz)) * 0.3;
				if (score < bestScore) {
					bestScore = score;
					best = new int[] {cx, cz};
				}
			}
		}
		return best;
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot(TestScreenshotOptions.of(name).withSize(1600, 900));
	}

	/** O centro e os quatro cantos da área do mapa estão em bioma verde. */
	private static boolean scenic(ServerLevel level, int cx, int cz, int y) {
		int[][] points = {{0, 0}, {-48, -48}, {48, -48}, {-48, 48}, {48, 48}};
		for (int[] p : points) {
			var biome = level.getUncachedNoiseBiome((cx + p[0]) >> 2, y >> 2, (cz + p[1]) >> 2);
			if (biome.unwrapKey().filter(SCENIC::contains).isEmpty()) return false;
		}
		return true;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.renderDistance().set(8));
		// a parede inteira chega rápido; o limite padrão (40/s) é para não pesar no LAN
		HolomapConfig.get().chunksPerSecond = 120;
		try (TestSingleplayerContext sp = ctx.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("holomap"))
			.create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule advance_weather false");

			// a cena fica no centro de uma área de mapa bonita: a mesa mostra o terreno em volta dela
			int[] scene = server.computeOnServer(s -> findScenicMap(s.overworld(), s.getPlayerList().getPlayers().get(0).blockPosition()));
			int x = scene[0], z = scene[1] - 4;
			server.runCommand(cmd("forceload add %d %d %d %d", x - 16, z - 16, x + 16, z + 24));
			int y = server.computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));

			// clareira com a mesa de mapa: bloco com o quadro deitado em cima
			server.runCommand(cmd("fill %d %d %d %d %d %d air", x - 4, y, z - 4, x + 5, y + 8, z + 12));
			server.runCommand(cmd("fill %d %d %d %d %d %d grass_block", x - 4, y - 1, z - 4, x + 5, y - 1, z + 12));
			server.runCommand(cmd("setblock %d %d %d oak_planks", x, y, z + 3));
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				ItemStack map = MapItem.create(level, x, z, (byte) 0, true, false);
				ItemFrame frame = new ItemFrame(level, new BlockPos(x, y + 1, z + 3), Direction.UP);
				frame.setItem(map);
				level.addFreshEntity(frame);
			});

			// parede 3×3 de mapas vizinhos virada para o norte. De frente para ela (olhando para o sul) o leste fica à
			// esquerda, então a coluna i mostra o mapa i passos a oeste, e a linha r (de baixo para cima) r passos ao norte.
			int cx = scene[0], cz = scene[1];
			server.runCommand(cmd("fill %d %d %d %d %d %d stone_bricks", x - 1, y, z + 10, x + WALL, y + WALL + 1, z + 10));
			for (int i = 0; i < WALL; i++) {
				for (int r = 0; r < WALL; r++) {
					int mapX = cx - i * 128, mapZ = cz - r * 128;
					// carrega a área de cada mapa para o servidor conhecer o terreno todo
					server.runCommand(cmd("forceload add %d %d %d %d", mapX - 64, mapZ - 64, mapX + 63, mapZ + 63));
					int fx = x + i, fy = y + 1 + r;
					server.runOnServer(s -> {
						ServerLevel level = s.overworld();
						ItemStack map = MapItem.create(level, mapX, mapZ, (byte) 0, false, false);
						ItemFrame frame = new ItemFrame(level, new BlockPos(fx, fy, z + 9), Direction.NORTH);
						frame.setItem(map);
						level.addFreshEntity(frame);
					});
				}
			}

			ctx.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
			// espectador não cai nem tem mão na tela: a câmera fica onde o tp mandou
			server.runCommand("gamemode spectator @a");

			// de cima e de lado, a uns dois blocos da mesa
			server.runCommand(cmd("tp @a %d.5 %d %d.6 0 50", x, y + 1, z + 1));
			ctx.waitTicks(300);
			shot(ctx, "01-table");

			server.runCommand(cmd("tp @a %d.5 %d %d.5 0 90", x, y + 2, z + 3));
			ctx.waitTicks(20);
			shot(ctx, "02-table-from-above");

			// tempo real: torre de ouro na clareira
			server.runCommand(cmd("fill %d %d %d %d %d %d gold_block", x + 3, y, z - 3, x + 4, y + 20, z - 2));
			server.runCommand(cmd("tp @a %d.5 %d %d.6 0 50", x, y + 1, z + 1));
			ctx.waitTicks(30);
			shot(ctx, "03-table-live-tower");

			// parede: espera o terreno de todos os mapas chegar e as maquetes ficarem prontas
			server.runCommand(cmd("tp @a %d.5 %d %d.5 0 8", x + WALL / 2, y + 1, z + 3));
			int wallMinX = cx - (WALL - 1) * 128 - 64, wallMinZ = cz - (WALL - 1) * 128 - 64;
			int wallChunks = WALL * WALL * 64;
			int waited = ctx.waitFor(mc -> {
				// quase todo o terreno da parede no cliente (gerado no servidor e recebido) e nada mais montando
				int known = ClientTerrain.countKnown(mc.level.dimension(), wallMinX, wallMinZ, cx + 63, cz + 63);
				DioramaManager.Stats st = DioramaManager.stats();
				return known >= wallChunks * 95 / 100 && st.meshes() >= WALL * WALL && st.building() == 0;
			}, 20 * 240);
			// as últimas remontagens (no ritmo do orçamento) terminam antes da foto
			ctx.waitTicks(100);
			// o servidor já guardou o terreno; não precisa mais manter a área carregada
			server.runCommand("forceload remove all");
			ctx.waitTicks(40);
			shot(ctx, "04-wall");
			loadTest(ctx, server, waited, x, y, z);

			// os mesmos mapas deitados no chão: uma maquete grande, vista de cima e de perto
			for (int i = 0; i < WALL; i++) {
				for (int r = 0; r < WALL; r++) {
					int mapX = cx - i * 128, mapZ = cz - r * 128;
					int fx = x + 1 - i, fz = z + 7 - r;
					server.runOnServer(s -> {
						ServerLevel level = s.overworld();
						ItemStack map = MapItem.create(level, mapX, mapZ, (byte) 0, false, false);
						ItemFrame frame = new ItemFrame(level, new BlockPos(fx, y, fz), Direction.UP);
						frame.setItem(map);
						level.addFreshEntity(frame);
					});
				}
			}
			server.runCommand(cmd("tp @a %d.5 %d %d.5 0 56", x, y + 3, z + 3));
			ctx.waitFor(mc -> DioramaManager.stats().building() == 0, 20 * 60);
			ctx.waitTicks(100);
			shot(ctx, "07-floor");

			server.runCommand(cmd("tp @a %d.5 %d %d.5 0 35", x, y + 14, z - 10));
			ctx.waitTicks(40);
			shot(ctx, "05-from-afar");

			// à noite o relevo escurece com o mundo (luz do céu), não fica aceso
			server.runCommand("time set midnight");
			server.runCommand(cmd("setblock %d %d %d torch", x + 1, y, z + 3));
			server.runCommand(cmd("tp @a %d.5 %d %d.6 0 50", x, y + 1, z + 1));
			ctx.waitTicks(40);
			shot(ctx, "06-table-at-night");
		}
	}

	/** Média e mínimo de FPS em {@code seconds} segundos. */
	private static int[] fps(ClientGameTestContext ctx, int seconds) {
		int sum = 0, min = Integer.MAX_VALUE;
		for (int i = 0; i < seconds; i++) {
			ctx.waitTicks(20);
			int fps = ctx.computeOnClient(mc -> mc.getFps());
			sum += fps;
			min = Math.min(min, fps);
		}
		return new int[] {sum / seconds, min};
	}

	/**
	 * Mede a parede parada na tela e depois de costas para ela (as maquetes fora da tela não são desenhadas),
	 * para separar o custo delas do resto do jogo. Falha se as maquetes não ficaram prontas.
	 */
	private static void loadTest(ClientGameTestContext ctx, TestServerContext server, int ticksToBuild, int x, int y, int z) {
		int buildsBefore = DioramaManager.stats().builds();
		long localBefore = ClientTerrain.localChanges(), serverBefore = ClientTerrain.serverChanges();
		int[] facing = fps(ctx, 10);
		int rebuilds = DioramaManager.stats().builds() - buildsBefore;
		long localChanged = ClientTerrain.localChanges() - localBefore, serverChanged = ClientTerrain.serverChanges() - serverBefore;
		DioramaManager.Stats st = DioramaManager.stats();
		server.runCommand(cmd("tp @a %d.5 %d %d.5 180 8", x + WALL / 2, y + 1, z + 3));
		ctx.waitTicks(40);
		int[] away = fps(ctx, 10);
		String report = String.format(Locale.ROOT,
			"wall %dx%d: ready after %d ticks%n"
				+ "fps facing the wall: avg %d, min %d (rebuilds while still: %d)%n"
				+ "terrain changes while still: %d local, %d from server%n"
				+ "fps facing away:     avg %d, min %d%n%s%n",
			WALL, WALL, ticksToBuild, facing[0], facing[1], rebuilds, localChanged, serverChanged, away[0], away[1], st.describe());
		try {
			Files.writeString(Path.of("holomap-stats.txt"), report, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		System.out.println(report);
		if (st.meshes() < WALL * WALL || st.quads() == 0) throw new AssertionError("Dioramas were not built: " + st.describe());
	}
}
