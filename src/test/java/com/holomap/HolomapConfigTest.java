package com.holomap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HolomapConfigTest {
	private static final double[] DEFAULT_DISTANCES = {8, 20, 40};

	@Test
	void emptyJsonGivesDefaults() {
		HolomapConfig cfg = HolomapConfig.parse("{}");
		assertEquals(1.0, cfg.verticalScale);
		assertArrayEquals(DEFAULT_DISTANCES, cfg.lodDistances);
		assertEquals(256, cfg.gpuMemoryMb);
		assertEquals(40, cfg.chunksPerSecond);
	}

	@Test
	void validValuesAreKept() {
		HolomapConfig cfg = HolomapConfig.parse(
			"{\"verticalScale\": 2, \"lodDistances\": [4, 16, 64], \"gpuMemoryMb\": 512, \"chunksPerSecond\": 100}");
		assertEquals(2.0, cfg.verticalScale);
		assertArrayEquals(new double[] {4, 16, 64}, cfg.lodDistances);
		assertEquals(512, cfg.gpuMemoryMb);
		assertEquals(100, cfg.chunksPerSecond);
	}

	@Test
	void outOfRangeValuesFallBackToDefaults() {
		HolomapConfig cfg = HolomapConfig.parse(
			"{\"verticalScale\": 0, \"lodDistances\": [20, 8, 40], \"gpuMemoryMb\": 1, \"chunksPerSecond\": -5}");
		assertEquals(1.0, cfg.verticalScale);
		assertArrayEquals(DEFAULT_DISTANCES, cfg.lodDistances);
		assertEquals(256, cfg.gpuMemoryMb);
		assertEquals(40, cfg.chunksPerSecond);
	}

	@Test
	void wrongNumberOfDistancesFallsBack() {
		assertArrayEquals(DEFAULT_DISTANCES, HolomapConfig.parse("{\"lodDistances\": [10]}").lodDistances);
	}
}
