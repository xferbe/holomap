package com.holomap.test;

import java.util.Locale;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Mundo normal com seed fixa: uma mesa de mapa (quadro deitado em cima de um bloco) e uma parede 2×2 de mapas
 * vizinhos. Tira screenshots da mesa, da parede, de longe e depois de construir uma torre (tempo real).
 * Rodar com {@code gradlew runClientGameTest}; as imagens ficam em {@code build/run/clientGameTest/screenshots}.
 */
public class HolomapClientTest implements FabricClientGameTest {
	private static String cmd(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}

	/** Toda a área da cena sem água por perto (senão a clareira inunda). */
	private static boolean dryArea(ServerLevel level, int x, int z) {
		for (int dx = -10; dx <= 10; dx += 2) {
			for (int dz = -10; dz <= 18; dz += 2) {
				level.getChunk((x + dx) >> 4, (z + dz) >> 4);
				int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
				int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x + dx, z + dz);
				if (surface != floor) return false;
			}
		}
		return true;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.renderDistance().set(10));
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

			int[] spot = server.computeOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos p = s.getPlayerList().getPlayers().get(0).blockPosition();
				// procura terra firme (sem água por cima) em espiral perto do spawn
				for (int r = 0; r <= 256; r += 16) {
					for (int dx = -r; dx <= r; dx += 16) {
						for (int dz = -r; dz <= r; dz += 16) {
							if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
							int x = p.getX() + dx, z = p.getZ() + dz;
							int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
							if (surface > level.getSeaLevel() + 2 && dryArea(level, x, z)) return new int[] {x, surface, z};
						}
					}
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
				return new int[] {p.getX(), y, p.getZ()};
			});
			int x = spot[0], y = spot[1], z = spot[2];

			// clareira com a mesa de mapa: bloco com o quadro deitado em cima
			server.runCommand(cmd("fill %d %d %d %d %d %d air", x - 4, y, z - 4, x + 4, y + 8, z + 12));
			server.runCommand(cmd("fill %d %d %d %d %d %d grass_block", x - 4, y - 1, z - 4, x + 4, y - 1, z + 12));
			server.runCommand(cmd("setblock %d %d %d oak_planks", x, y, z + 3));
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				ItemStack map = MapItem.create(level, x, z, (byte) 0, true, false);
				ItemFrame frame = new ItemFrame(level, new BlockPos(x, y + 1, z + 3), Direction.UP);
				frame.setItem(map);
				level.addFreshEntity(frame);
			});

			// parede 2×2 de mapas vizinhos (áreas lado a lado) virada para o norte
			server.runCommand(cmd("fill %d %d %d %d %d %d stone_bricks", x - 1, y, z + 10, x + 2, y + 3, z + 10));
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				int cx = Math.floorDiv(x + 64, 128) * 128, cz = Math.floorDiv(z + 64, 128) * 128;
				// de frente para a parede (olhando para o sul), o leste fica à esquerda
				int[][] cells = {{1, 2, -128, -128}, {0, 2, 0, -128}, {1, 1, -128, 0}, {0, 1, 0, 0}};
				for (int[] c : cells) {
					ItemStack map = MapItem.create(level, cx + c[2], cz + c[3], (byte) 0, false, false);
					ItemFrame frame = new ItemFrame(level, new BlockPos(x + c[0], y + c[1], z + 9), Direction.NORTH);
					frame.setItem(map);
					level.addFreshEntity(frame);
				}
			});

			server.runCommand(cmd("tp @a %d.5 %d %d.2 0 40", x, y, z + 1));
			ctx.waitTicks(300);
			ctx.takeScreenshot("01-mesa");

			server.runCommand(cmd("tp @a %d.5 %d %d.5 0 90", x, y + 2, z + 3));
			ctx.waitTicks(20);
			ctx.takeScreenshot("02-mesa-de-cima");

			// tempo real: torre de ouro na clareira
			server.runCommand(cmd("fill %d %d %d %d %d %d gold_block", x + 3, y, z - 3, x + 4, y + 20, z - 2));
			server.runCommand(cmd("tp @a %d.5 %d %d.2 0 40", x, y, z + 1));
			ctx.waitTicks(30);
			ctx.takeScreenshot("03-mesa-torre-ao-vivo");

			server.runCommand(cmd("tp @a %d.5 %d %d.5 20 15", x + 2, y + 1, z + 6));
			ctx.waitTicks(40);
			ctx.takeScreenshot("04-parede-2x2");

			server.runCommand(cmd("tp @a %d.5 %d %d.5 0 35", x, y + 14, z - 10));
			ctx.waitTicks(40);
			ctx.takeScreenshot("05-de-longe");
		}
	}
}
