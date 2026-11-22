package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EfficiencyTest {

	@Test
	void theFittedCopMatchesTheDatasheetPoints() {
		var efficiency = Efficiency.parse("7/25=4.0, 2/25=3.2, -7/25=2.4", "");

		assertEquals(4.0, efficiency.cop(7, 25), 0.15);
		assertEquals(3.2, efficiency.cop(2, 25), 0.15);
		assertEquals(2.4, efficiency.cop(-7, 25), 0.15);
		assertTrue(efficiency.cop(-15, 25) < 2.4, "falls further outside the datasheet range");
		assertEquals(4.0, efficiency.cop(20, 25), 1e-9, "clamped at the best datasheet value");
	}

	@Test
	void withoutPointsTheEfficiencyIsOne() {
		var resistive = Efficiency.parse("", "");

		assertEquals(1.0, resistive.cop(-10, 25));
		assertEquals(1.0, resistive.eer(35));
	}

	@Test
	void theEerIsInterpolatedLinearly() {
		var efficiency = Efficiency.parse("", "35=3.2, 27=4.4");

		assertEquals(3.8, efficiency.eer(31), 1e-9);
		assertEquals(4.4, efficiency.eer(20), 1e-9);
		assertEquals(3.2, efficiency.eer(40), 1e-9);
	}

	@Test
	void aBadPointIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Efficiency.parse("7=4", ""));
		assertThrows(IllegalArgumentException.class, () -> Efficiency.parse("", "x=3"));
	}
}
