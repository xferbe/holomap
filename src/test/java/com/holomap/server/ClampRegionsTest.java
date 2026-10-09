package com.holomap.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class ClampRegionsTest {
	@Test
	void swappedCornersAreOrdered() {
		assertArrayEquals(new int[] {0, 0, 127, 127}, HolomapServer.clampRegions(new int[] {127, 127, 0, 0}));
	}

	@Test
	void hugeRegionIsCutAroundItsCenter() {
		assertArrayEquals(new int[] {-1024, -1024, 1024, 1024},
			HolomapServer.clampRegions(new int[] {-100000, -100000, 100000, 100000}));
	}

	@Test
	void incompleteRegionIsDropped() {
		assertArrayEquals(new int[] {0, 0, 10, 10}, HolomapServer.clampRegions(new int[] {0, 0, 10, 10, 5, 5}));
	}

	@Test
	void extremeCoordinatesDoNotOverflow() {
		int[] r = HolomapServer.clampRegions(new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE});
		assertArrayEquals(new int[] {-1024, -1024, 1024, 1024}, r);
	}
}
