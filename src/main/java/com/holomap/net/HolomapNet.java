package com.holomap.net;

import java.util.ArrayList;
import java.util.List;

import com.holomap.Holomap;
import com.holomap.terrain.ChunkSummary;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Todos os pacotes do mod. Se o servidor não tiver o mod, o cliente só não recebe nada daqui. */
public final class HolomapNet {
	public static final int MAX_REGIONS = 4;
	public static final int MAX_CHUNKS_PER_BATCH = 8;
	/** Sobe sempre que algum pacote muda de formato. */
	public static final int PROTOCOL = 2;

	private HolomapNet() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
		return new CustomPacketPayload.Type<>(Holomap.id(path));
	}

	/** Cliente → servidor: "estou olhando mapas destas áreas, me mande o terreno". Cada região são 4 ints: minX, minZ, maxX, maxZ. */
	public record ViewRequest(String dimension, int[] regions) implements CustomPacketPayload {
		public static final Type<ViewRequest> TYPE = payloadType("view");
		public static final StreamCodec<RegistryFriendlyByteBuf, ViewRequest> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.dimension);
				buf.writeVarInt(p.regions.length);
				for (int v : p.regions) buf.writeInt(v);
			},
			buf -> {
				String dim = buf.readUtf();
				int n = Math.min(buf.readVarInt(), MAX_REGIONS * 4);
				int[] regions = new int[n - n % 4];
				for (int i = 0; i < n; i++) {
					int v = buf.readInt();
					if (i < regions.length) regions[i] = v;
				}
				return new ViewRequest(dim, regions);
			});

		@Override
		public Type<ViewRequest> type() {
			return TYPE;
		}
	}

	/** Servidor → cliente: lote de chunks em 3D. */
	public record TerrainBatch(String dimension, long[] keys, List<ChunkSummary> chunks) implements CustomPacketPayload {
		public static final Type<TerrainBatch> TYPE = payloadType("terrain");
		public static final StreamCodec<RegistryFriendlyByteBuf, TerrainBatch> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.dimension);
				buf.writeVarInt(p.keys.length);
				for (int i = 0; i < p.keys.length; i++) {
					buf.writeLong(p.keys[i]);
					p.chunks.get(i).write(buf);
				}
			},
			buf -> {
				String dim = buf.readUtf();
				int n = buf.readVarInt();
				if (n > MAX_CHUNKS_PER_BATCH) throw new IllegalArgumentException("Too many chunks: " + n);
				long[] keys = new long[n];
				List<ChunkSummary> chunks = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					keys[i] = buf.readLong();
					chunks.add(ChunkSummary.read(buf));
				}
				return new TerrainBatch(dim, keys, chunks);
			});

		@Override
		public Type<TerrainBatch> type() {
			return TYPE;
		}
	}

	/**
	 * Servidor → cliente, ao entrar: versão do protocolo do mod no servidor. Com versões diferentes o cliente não
	 * conversa com o servidor e a maquete usa só o terreno carregado localmente.
	 */
	public record Hello(int protocol) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = payloadType("hello");
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeVarInt(p.protocol), buf -> new Hello(buf.readVarInt()));

		@Override
		public Type<Hello> type() {
			return TYPE;
		}
	}

	/** Cliente → servidor: "qual área este mapa cobre?". O centro do mapa não vem no pacote vanilla. */
	public record MapInfoRequest(int mapId) implements CustomPacketPayload {
		public static final Type<MapInfoRequest> TYPE = payloadType("map_info_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, MapInfoRequest> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeVarInt(p.mapId), buf -> new MapInfoRequest(buf.readVarInt()));

		@Override
		public Type<MapInfoRequest> type() {
			return TYPE;
		}
	}

	public record MapInfo(int mapId, String dimension, int centerX, int centerZ, int scale) implements CustomPacketPayload {
		public static final Type<MapInfo> TYPE = payloadType("map_info");
		public static final StreamCodec<RegistryFriendlyByteBuf, MapInfo> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.mapId);
				buf.writeUtf(p.dimension);
				buf.writeInt(p.centerX);
				buf.writeInt(p.centerZ);
				buf.writeByte(p.scale);
			},
			buf -> new MapInfo(buf.readVarInt(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readByte()));

		@Override
		public Type<MapInfo> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry<RegistryFriendlyByteBuf> c2s = PayloadTypeRegistry.serverboundPlay();
		PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c = PayloadTypeRegistry.clientboundPlay();
		c2s.register(ViewRequest.TYPE, ViewRequest.CODEC);
		c2s.register(MapInfoRequest.TYPE, MapInfoRequest.CODEC);
		s2c.register(TerrainBatch.TYPE, TerrainBatch.CODEC);
		s2c.register(Hello.TYPE, Hello.CODEC);
		s2c.register(MapInfo.TYPE, MapInfo.CODEC);
	}
}
