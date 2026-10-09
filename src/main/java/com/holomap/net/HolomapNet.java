package com.holomap.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

	public record PlayerMarker(UUID id, String name, double x, double y, double z, float yaw) {
	}

	/** Servidor → cliente: posição dos outros jogadores da mesma dimensão. */
	public record Players(String dimension, List<PlayerMarker> players) implements CustomPacketPayload {
		public static final Type<Players> TYPE = payloadType("players");
		public static final StreamCodec<RegistryFriendlyByteBuf, Players> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.dimension);
				buf.writeVarInt(p.players.size());
				for (PlayerMarker m : p.players) {
					buf.writeUUID(m.id());
					buf.writeUtf(m.name());
					buf.writeDouble(m.x());
					buf.writeDouble(m.y());
					buf.writeDouble(m.z());
					buf.writeFloat(m.yaw());
				}
			},
			buf -> {
				String dim = buf.readUtf();
				int n = buf.readVarInt();
				if (n > 1024) throw new IllegalArgumentException("Too many players: " + n);
				List<PlayerMarker> list = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					list.add(new PlayerMarker(buf.readUUID(), buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));
				}
				return new Players(dim, list);
			});

		@Override
		public Type<Players> type() {
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
		s2c.register(Players.TYPE, Players.CODEC);
		s2c.register(MapInfo.TYPE, MapInfo.CODEC);
	}
}
