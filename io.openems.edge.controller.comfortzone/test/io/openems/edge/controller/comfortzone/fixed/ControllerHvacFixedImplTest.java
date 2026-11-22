package io.openems.edge.controller.comfortzone.fixed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ControllerHvacFixedImplTest {

	@Test
	void theSetpointIsWrittenInDeciDegrees() {
		assertEquals(205, ControllerHvacFixedImpl.toDeciDegrees(20.5));
		assertEquals(180, ControllerHvacFixedImpl.toDeciDegrees(18.0));
	}
}
