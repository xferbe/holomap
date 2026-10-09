package com.holomap.terrain;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import net.minecraft.network.FriendlyByteBuf;

/**
 * As 16x16 colunas de um chunk em 3D: para cada coluna, a pilha de blocos do topo até o chão firme
 * (copa de árvore, o ar embaixo dela, tronco, telhado, paredes, água, fundo do lago...) e o bioma.
 * Abaixo da pilha a coluna é maciça, preenchida com o último bloco guardado.
 * É o que a maquete do mapa precisa para mostrar árvore como árvore e casa como casa.
 */
public final class ChunkSummary {
	public static final short UNKNOWN = Short.MIN_VALUE;
	/** Maior pilha guardada por coluna (água funda e árvore alta cabem com folga). */
	public static final int MAX_STACK = 96;

	/** Y do bloco mais alto da coluna, ou {@link #UNKNOWN}. */
	public final short[] top = new short[256];
	/** Id do bioma no registro sincronizado. */
	public final short[] biome = new short[256];
	/** Início da pilha de cada coluna em {@link #states}; a coluna i ocupa [offsets[i], offsets[i+1]). */
	public final int[] offsets = new int[257];
	/** Ids globais de BlockState ({@code Block.getId}), do topo para baixo. 0 é ar. */
	public int[] states = new int[0];
	/** Cresce sempre que o conteúdo muda; usado para saber o que reenviar/redesenhar. */
	public long version;

	public static int index(int localX, int localZ) {
		return (localZ << 4) | localX;
	}

	public int stackSize(int i) {
		return offsets[i + 1] - offsets[i];
	}

	/** Bloco em y (0 = ar acima do topo; abaixo da pilha, repete o último bloco). */
	public int stateAt(int i, int y) {
		int t = top[i];
		if (t == UNKNOWN || y > t) return 0;
		int size = stackSize(i);
		if (size == 0) return 0;
		int k = Math.min(t - y, size - 1);
		return states[offsets[i] + k];
	}

	/** Y do último bloco guardado; abaixo dele a coluna é maciça. */
	public int bottom(int i) {
		return top[i] - Math.max(stackSize(i), 1) + 1;
	}

	public boolean sameContent(ChunkSummary other) {
		return other != null
			&& Arrays.equals(top, other.top)
			&& Arrays.equals(biome, other.biome)
			&& Arrays.equals(offsets, other.offsets)
			&& Arrays.equals(states, other.states);
	}

	public void write(FriendlyByteBuf buf) {
		for (int i = 0; i < 256; i++) {
			buf.writeShort(top[i]);
			buf.writeShort(biome[i]);
			int size = stackSize(i);
			buf.writeVarInt(size);
			for (int k = 0; k < size; k++) buf.writeVarInt(states[offsets[i] + k]);
		}
	}

	public static ChunkSummary read(FriendlyByteBuf buf) {
		ChunkSummary s = new ChunkSummary();
		int[] tmp = new int[256 * 8];
		int n = 0;
		for (int i = 0; i < 256; i++) {
			s.top[i] = buf.readShort();
			s.biome[i] = buf.readShort();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_STACK) throw new IllegalArgumentException("Bad stack size " + size);
			s.offsets[i] = n;
			if (n + size > tmp.length) tmp = Arrays.copyOf(tmp, Math.max(tmp.length * 2, n + size));
			for (int k = 0; k < size; k++) tmp[n++] = buf.readVarInt();
		}
		s.offsets[256] = n;
		s.states = Arrays.copyOf(tmp, n);
		return s;
	}

	public void write(DataOutput out) throws IOException {
		for (int i = 0; i < 256; i++) {
			out.writeShort(top[i]);
			out.writeShort(biome[i]);
			int size = stackSize(i);
			out.writeShort(size);
			for (int k = 0; k < size; k++) out.writeInt(states[offsets[i] + k]);
		}
	}

	public static ChunkSummary read(DataInput in) throws IOException {
		ChunkSummary s = new ChunkSummary();
		int[] tmp = new int[256 * 8];
		int n = 0;
		for (int i = 0; i < 256; i++) {
			s.top[i] = in.readShort();
			s.biome[i] = in.readShort();
			int size = in.readShort();
			if (size < 0 || size > MAX_STACK) throw new IOException("Bad stack size " + size);
			s.offsets[i] = n;
			if (n + size > tmp.length) tmp = Arrays.copyOf(tmp, Math.max(tmp.length * 2, n + size));
			for (int k = 0; k < size; k++) tmp[n++] = in.readInt();
		}
		s.offsets[256] = n;
		s.states = Arrays.copyOf(tmp, n);
		return s;
	}
}
