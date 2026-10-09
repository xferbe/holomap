package com.holomap.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

class ChunkSummaryTest {
	/** Coluna 0: topo em y=70 com pilha [folha, ar, tronco, grama]; coluna 1: desconhecida; o resto: só pedra em y=64. */
	private static ChunkSummary sample() {
		ChunkSummary s = new ChunkSummary();
		int[] states = new int[4 + 254];
		int n = 0;
		s.offsets[0] = n;
		s.top[0] = 70;
		s.biome[0] = 3;
		states[n++] = 10;
		states[n++] = 0;
		states[n++] = 11;
		states[n++] = 12;
		s.offsets[1] = n;
		s.top[1] = ChunkSummary.UNKNOWN;
		for (int i = 2; i < 256; i++) {
			s.offsets[i] = n;
			s.top[i] = 64;
			s.biome[i] = 1;
			states[n++] = 1;
		}
		s.offsets[256] = n;
		s.states = states;
		return s;
	}

	@Test
	void stackIsReadFromTheTopAndRepeatsTheLastBlockBelow() {
		ChunkSummary s = sample();
		assertEquals(0, s.stateAt(0, 71), "above the top is air");
		assertEquals(10, s.stateAt(0, 70));
		assertEquals(0, s.stateAt(0, 69));
		assertEquals(11, s.stateAt(0, 68));
		assertEquals(12, s.stateAt(0, 67));
		assertEquals(12, s.stateAt(0, 10), "below the stack the column is solid");
		assertEquals(67, s.bottom(0));
		assertEquals(0, s.stateAt(1, 64), "unknown column is air");
	}

	@Test
	void networkRoundTripKeepsEveryColumn() {
		ChunkSummary s = sample();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		s.write(buf);
		ChunkSummary back = ChunkSummary.read(buf);
		assertTrue(s.sameContent(back));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void diskRoundTripKeepsEveryColumn() throws IOException {
		ChunkSummary s = sample();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		s.write(new DataOutputStream(bytes));
		ChunkSummary back = ChunkSummary.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
		assertTrue(s.sameContent(back));
	}

	@Test
	void differentContentIsNotTheSame() {
		ChunkSummary a = sample(), b = sample();
		b.states[0] = 99;
		assertFalse(a.sameContent(b));
		assertFalse(a.sameContent(null));
	}

	@Test
	void oversizedStackFromTheNetworkIsRejected() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeShort(64);
		buf.writeShort(0);
		buf.writeVarInt(ChunkSummary.MAX_STACK + 1);
		assertThrows(IllegalArgumentException.class, () -> ChunkSummary.read(buf));
	}
}
