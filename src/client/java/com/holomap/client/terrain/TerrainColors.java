package com.holomap.client.terrain;

/** Sombra por relevo, transparência da água e cor por jogador. As cores dos blocos vêm das texturas ({@code BlockLooks}). */
public final class TerrainColors {
	public static final int UNKNOWN_DARK = 0xFF10161B;
	public static final int UNKNOWN_LIGHT = 0xFF151D23;

	private TerrainColors() {
	}

	/** Mais claro se a coluna sobe em relação à vizinha ao norte, mais escuro se desce (como o mapa vanilla). */
	public static float slope(int height, int northHeight) {
		if (northHeight == Short.MIN_VALUE) return 1f;
		int d = height - northHeight;
		if (d > 0) return d > 2 ? 1.14f : 1.07f;
		if (d < 0) return d < -2 ? 0.78f : 0.89f;
		return 1f;
	}

	/** Opacidade (0–255) da água por cima do fundo: rasa deixa ver a areia, funda fica azul. */
	public static int waterAlpha(int depth) {
		return Math.min(235, 120 + depth * 14);
	}

	public static int shade(int argb, float f) {
		int a = argb >>> 24;
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, (int) ((argb & 0xFF) * f));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	/** Cor estável por jogador, a partir do UUID. */
	public static int playerColor(java.util.UUID id) {
		float h = (Math.floorMod(id.hashCode(), 360)) / 60f;
		float s = 0.65f;
		float c = s, x = c * (1 - Math.abs(h % 2 - 1)), m = 1 - c;
		float r, g, b;
		switch ((int) h) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}
		return 0xFF000000 | ((int) ((r + m) * 255) << 16) | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
	}
}
