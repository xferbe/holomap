package com.holomap.client;

import java.util.ArrayList;
import java.util.List;

import com.holomap.net.HolomapNet;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.MapInfoRequest;
import com.holomap.net.HolomapNet.ViewRequest;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** O que o cliente sabe vindo do servidor: a versão do mod lá, a área de cada mapa e o que pedir de terreno. */
public final class ClientState {
	/** Pedido de área é renovado a cada 1 s; o servidor esquece quem some por 5 s. */
	private static final int VIEW_REFRESH_TICKS = 20;
	/** Servidor com o mod que não mandou a versão até aqui é de uma versão antiga. */
	private static final int HELLO_TIMEOUT_TICKS = 100;

	private static final Int2ObjectOpenHashMap<MapInfo> MAP_INFO = new Int2ObjectOpenHashMap<>();
	private static final IntOpenHashSet MAP_INFO_REQUESTED = new IntOpenHashSet();

	private static final List<int[]> pendingRegions = new ArrayList<>();
	private static int[] lastSentRegions = new int[0];
	private static int ticksSinceView;
	private static int serverProtocol;
	private static int ticksConnected;
	private static boolean warned;

	private ClientState() {
	}

	/** O servidor tem o Holomap, na mesma versão de protocolo. Sem isso, a maquete usa só o terreno carregado aqui. */
	public static boolean serverHasMod() {
		return serverProtocol == HolomapNet.PROTOCOL && Minecraft.getInstance().getConnection() != null
			&& ClientPlayNetworking.canSend(ViewRequest.TYPE);
	}

	public static int serverProtocol() {
		return serverProtocol;
	}

	public static void onHello(int protocol) {
		serverProtocol = protocol;
		if (protocol != HolomapNet.PROTOCOL) warnVersion(Integer.toString(protocol));
	}

	private static void warnVersion(String serverVersion) {
		if (warned) return;
		warned = true;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.translatable("holomap.protocol_mismatch", serverVersion, HolomapNet.PROTOCOL));
		}
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

	/** Chamado a cada tick por quem está desenhando uma maquete. */
	public static void requestRegion(int minX, int minZ, int maxX, int maxZ) {
		if (pendingRegions.size() < HolomapNet.MAX_REGIONS) pendingRegions.add(new int[] {minX, minZ, maxX, maxZ});
	}

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			pendingRegions.clear();
			return;
		}
		if (++ticksConnected == HELLO_TIMEOUT_TICKS && serverProtocol == 0 && ClientPlayNetworking.canSend(ViewRequest.TYPE)) {
			warnVersion("< " + HolomapNet.PROTOCOL);
		}
		if (!serverHasMod()) {
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
		MAP_INFO.clear();
		MAP_INFO_REQUESTED.clear();
		pendingRegions.clear();
		lastSentRegions = new int[0];
		serverProtocol = 0;
		ticksConnected = 0;
		warned = false;
	}
}
