package com.holomap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/holomap.json}. Criado com os valores padrão na primeira vez; valor fora da faixa volta ao padrão.
 * As opções de cliente valem para quem vê a maquete; as de servidor, para quem abre o mundo.
 */
public final class HolomapConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static HolomapConfig current = new HolomapConfig();

	/** Cliente: multiplica a altura do relevo (1 = fiel ao mapa; 2 deixa terreno plano mais legível). */
	public double verticalScale = 1.0;
	/** Cliente: distâncias (blocos) em que a maquete passa de 1 para 2, 4 e 8 colunas por célula. */
	public double[] lodDistances = {8, 20, 40};
	/** Cliente: teto de memória de vídeo das maquetes; acima dele, as menos usadas saem primeiro. */
	public int gpuMemoryMb = 256;
	/** Servidor: chunks de terreno distante mandados por segundo a cada jogador. */
	public int chunksPerSecond = 40;

	public static HolomapConfig get() {
		return current;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("holomap.json");
		HolomapConfig cfg = new HolomapConfig();
		try {
			if (Files.exists(file)) cfg = parse(Files.readString(file, StandardCharsets.UTF_8));
			Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (IOException | JsonParseException e) {
			Holomap.LOGGER.warn("Could not read {}, using defaults", file, e);
		}
		current = cfg;
	}

	/** Lê o JSON e corrige o que estiver fora da faixa. Campos ausentes ficam com o padrão. */
	public static HolomapConfig parse(String json) {
		HolomapConfig cfg = GSON.fromJson(json, HolomapConfig.class);
		if (cfg == null) cfg = new HolomapConfig();
		cfg.sanitize();
		return cfg;
	}

	void sanitize() {
		HolomapConfig d = new HolomapConfig();
		if (!(verticalScale >= 0.25 && verticalScale <= 8)) verticalScale = d.verticalScale;
		if (!validDistances(lodDistances)) lodDistances = d.lodDistances;
		if (gpuMemoryMb < 32 || gpuMemoryMb > 8192) gpuMemoryMb = d.gpuMemoryMb;
		if (chunksPerSecond < 1 || chunksPerSecond > 400) chunksPerSecond = d.chunksPerSecond;
	}

	private static boolean validDistances(double[] v) {
		if (v == null || v.length != 3) return false;
		double prev = 0;
		for (double x : v) {
			if (!(x > prev && x <= 512)) return false;
			prev = x;
		}
		return true;
	}
}
