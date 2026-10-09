package com.holomap.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.holomap.net.HolomapNet;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.MapInfoRequest;
import com.holomap.net.HolomapNet.PlayerMarker;
import com.holomap.net.HolomapNet.ViewRequest;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** O que o cliente sabe vindo do servidor: jogadores distantes e a área de cada mapa. */
public final class ClientState {
	/** Pedido de área é renovado a cada 1 s; o servidor esquece quem some por 5 s. */
	private static final int VIEW_REFRESH_TICKS = 20;
	/** Intervalo entre posições do servidor (4 ticks) — usado para suavizar o movimento. */
	private static final long PLAYER_LERP_MS = 200;

	private static final Map<UUID, RemotePlayer> REMOTE = new HashMap<>();
	private static String remoteDimension = "";
	private static final Int2ObjectOpenHashMap<MapInfo> MAP_INFO = new Int2ObjectOpenHashMap<>();
	private static final IntOpenHashSet MAP_INFO_REQUESTED = new IntOpenHashSet();

	private static final List<int[]> pendingRegions = new ArrayList<>();
	private static int[] lastSentRegions = new int[0];
	private static int ticksSinceView;

	private ClientState() {
	}

	public static boolean serverHasMod() {
		return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(ViewRequest.TYPE);
	}

	// ---- jogadores ----

	public record PlayerView(UUID id, String name, Vec3 pos, float yaw, boolean self) {
	}

	private static final class RemotePlayer {
		String name;
		Vec3 from, to;
		float yaw;
		long receivedAt;
	}

	public static void onPlayers(String dimension, List<PlayerMarker> markers) {
		long now = System.currentTimeMillis();
		if (!dimension.equals(remoteDimension)) {
			REMOTE.clear();
			remoteDimension = dimension;
		}
		Map<UUID, RemotePlayer> seen = new HashMap<>();
		for (PlayerMarker m : markers) {
			RemotePlayer r = REMOTE.getOrDefault(m.id(), new RemotePlayer());
			Vec3 target = new Vec3(m.x(), m.y(), m.z());
			r.from = r.to == null ? target : current(r, now);
			r.to = target;
			r.name = m.name();
			r.yaw = m.yaw();
			r.receivedAt = now;
			seen.put(m.id(), r);
		}
		REMOTE.clear();
		REMOTE.putAll(seen);
	}

	private static Vec3 current(RemotePlayer r, long now) {
		double t = Math.min(1.0, (now - r.receivedAt) / (double) PLAYER_LERP_MS);
		return r.from.lerp(r.to, t);
	}

	/**
	 * Todos os jogadores da dimensão atual. Quem está no alcance do cliente usa a entidade (posição suave a cada frame);
	 * quem está longe usa o que o servidor mandou, interpolado.
	 */
	public static List<PlayerView> players(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		List<PlayerView> out = new ArrayList<>();
		if (mc.level == null || mc.player == null) return out;
		long now = System.currentTimeMillis();
		Map<UUID, Boolean> added = new HashMap<>();
		for (Player p : mc.level.players()) {
			if (p.isSpectator() && p != mc.player) continue;
			out.add(new PlayerView(p.getUUID(), p.getGameProfile().name(), p.getPosition(partialTick), p.getYRot(), p == mc.player));
			added.put(p.getUUID(), true);
		}
		if (mc.level.dimension().identifier().toString().equals(remoteDimension)) {
			REMOTE.forEach((id, r) -> {
				if (!added.containsKey(id)) out.add(new PlayerView(id, r.name, current(r, now), r.yaw, false));
			});
		}
		return out;
	}

	// ---- mapas ----

	/** Área do mapa (centro e escala). Pede ao servidor na primeira vez; até a resposta chegar devolve null. */
	public static MapInfo mapInfo(int mapId) {
		MapInfo info = MAP_INFO.get(mapId);
		if (info == null && serverHasMod() && MAP_INFO_REQUESTED.add(mapId)) {
			ClientPlayNetworking.send(new MapInfoRequest(mapId));
		}
		return info;
	}

	public static void onMapInfo(MapInfo info) {
		MAP_INFO.put(info.mapId(), info);
	}

	// ---- áreas que o cliente está olhando ----

	/** Chamado a cada tick por quem está desenhando algo (holograma, tela do mapa). */
	public static void requestRegion(int minX, int minZ, int maxX, int maxZ) {
		if (pendingRegions.size() < HolomapNet.MAX_REGIONS) pendingRegions.add(new int[] {minX, minZ, maxX, maxZ});
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !serverHasMod()) {
			pendingRegions.clear();
			return;
		}
		int[] regions = new int[pendingRegions.size() * 4];
		for (int i = 0; i < pendingRegions.size(); i++) System.arraycopy(pendingRegions.get(i), 0, regions, i * 4, 4);
		pendingRegions.clear();

		ticksSinceView++;
		boolean changed = !java.util.Arrays.equals(regions, lastSentRegions);
		if (regions.length == 0 && !changed) return;
		if (changed || ticksSinceView >= VIEW_REFRESH_TICKS) {
			ClientPlayNetworking.send(new ViewRequest(mc.level.dimension().identifier().toString(), regions));
			lastSentRegions = regions;
			ticksSinceView = 0;
		}
	}

	public static void clear() {
		REMOTE.clear();
		remoteDimension = "";
		MAP_INFO.clear();
		MAP_INFO_REQUESTED.clear();
		pendingRegions.clear();
		lastSentRegions = new int[0];
	}
}
