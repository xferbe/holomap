package com.holomap.terrain;

/** Nível de detalhe da maquete pela distância, com uma folga para não ficar trocando quando a câmera para no limite. */
public final class LodPolicy {
	/** Colunas por célula em cada nível. */
	public static final int[] LOD = {1, 2, 4, 8};
	/** Folga (blocos) em volta de cada limite. */
	public static final double HYSTERESIS = 1.5;

	private LodPolicy() {
	}

	/** Índice em {@link #LOD} para a distância, sem folga. {@code limits} são as 3 distâncias crescentes. */
	public static int index(double distance, double[] limits) {
		for (int i = 0; i < limits.length; i++) {
			if (distance <= limits[i]) return i;
		}
		return limits.length;
	}

	/**
	 * Índice para a distância, mantendo o anterior enquanto a câmera estiver dentro da folga de um limite.
	 * {@code previous} negativo = sem histórico.
	 */
	public static int index(double distance, double[] limits, int previous) {
		int raw = index(distance, limits);
		if (previous < 0) return raw;
		int near = index(Math.max(0, distance - HYSTERESIS), limits);
		int far = index(distance + HYSTERESIS, limits);
		return previous >= near && previous <= far ? previous : raw;
	}
}
