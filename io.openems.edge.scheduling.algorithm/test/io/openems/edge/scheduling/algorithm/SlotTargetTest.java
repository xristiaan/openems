package io.openems.edge.scheduling.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SlotTargetTest {

	@Test
	void aLinearBandCostsTheSameOnBothSides() {
		var band = new SlotTarget(0.2, 0.8, 0.05);

		assertEquals(0.05 * 0.1, band.costOf(0.1), 1e-12);
		assertEquals(0.05 * 0.1, band.costOf(0.9), 1e-12);
		assertEquals(0, band.costOf(0.5));
	}

	@Test
	void aQuadraticTargetGrowsFasterThanTheDeviation() {
		// Comfort Zone defaults per hour: below a = b = 0.10, above a = b = 0.05
		var target = SlotTarget.quadratic(20, 0.10, 0.10, 0.05, 0.05);

		assertEquals(0.20, target.costOf(19), 1e-12);
		assertEquals(3.00, target.costOf(15), 1e-12, "5 °C too cold: several euros an hour");
		assertEquals(0.10, target.costOf(21), 1e-12, "above the target is cheaper");
		assertEquals(0, target.costOf(20));
	}
}
