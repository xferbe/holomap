package com.holomap.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.holomap.net.HolomapNet.Hello;
import com.holomap.net.HolomapNet.MapInfo;
import com.holomap.net.HolomapNet.TerrainBatch;
import com.holomap.net.HolomapNet.ViewRequest;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

class HolomapNetTest {
	private static RegistryFriendlyByteBuf buf() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	@Test
	void viewRequestRoundTrip() {
		RegistryFriendlyByteBuf buf = buf();
		int[] regions = {0, 0, 127, 127, -128, -128, -1, -1};
		ViewRequest.CODEC.encode(buf, new ViewRequest("minecraft:overworld", regions));
		ViewRequest back = ViewRequest.CODEC.decode(buf);
		assertEquals("minecraft:overworld", back.dimension());
		assertArrayEquals(regions, back.regions());
	}

	@Test
	void viewRequestKeepsAtMostFourRegions() {
		RegistryFriendlyByteBuf buf = buf();
		int[] many = new int[(HolomapNet.MAX_REGIONS + 2) * 4];
		for (int i = 0; i < many.length; i++) many[i] = i;
		ViewRequest.CODEC.encode(buf, new ViewRequest("minecraft:overworld", many));
		ViewRequest back = ViewRequest.CODEC.decode(buf);
		assertEquals(HolomapNet.MAX_REGIONS * 4, back.regions().length);
	}

	@Test
	void terrainBatchRejectsTooManyChunks() {
		RegistryFriendlyByteBuf buf = buf();
		buf.writeUtf("minecraft:overworld");
		buf.writeVarInt(HolomapNet.MAX_CHUNKS_PER_BATCH + 1);
		assertThrows(IllegalArgumentException.class, () -> TerrainBatch.CODEC.decode(buf));
	}

	@Test
	void helloAndMapInfoRoundTrip() {
		RegistryFriendlyByteBuf buf = buf();
		Hello.CODEC.encode(buf, new Hello(HolomapNet.PROTOCOL));
		MapInfo.CODEC.encode(buf, new MapInfo(7, "minecraft:the_nether", -320, 576, 2));
		assertEquals(HolomapNet.PROTOCOL, Hello.CODEC.decode(buf).protocol());
		assertEquals(new MapInfo(7, "minecraft:the_nether", -320, 576, 2), MapInfo.CODEC.decode(buf));
	}
}
