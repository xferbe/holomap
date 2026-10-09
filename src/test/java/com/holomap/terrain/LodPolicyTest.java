package com.holomap.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LodPolicyTest {
	private static final double[] LIMITS = {8, 20, 40};

	@Test
	void distanceSelectsTheLevel() {
		assertEquals(0, LodPolicy.index(3, LIMITS));
		assertEquals(1, LodPolicy.index(8.5, LIMITS));
		assertEquals(2, LodPolicy.index(39, LIMITS));
		assertEquals(3, LodPolicy.index(100, LIMITS));
	}

	@Test
	void levelHoldsInsideTheMarginAroundALimit() {
		// parado logo depois do limite de 8: continua no detalhe que já tinha
		assertEquals(0, LodPolicy.index(8.4, LIMITS, 0));
		assertEquals(1, LodPolicy.index(7.6, LIMITS, 1));
	}

	@Test
	void levelChangesOnceOutsideTheMargin() {
		assertEquals(1, LodPolicy.index(10, LIMITS, 0));
		assertEquals(0, LodPolicy.index(6, LIMITS, 1));
		assertEquals(3, LodPolicy.index(60, LIMITS, 0), "a jump skips the history");
	}

	@Test
	void noHistoryUsesTheRawLevel() {
		assertEquals(1, LodPolicy.index(8.4, LIMITS, -1));
	}
}
