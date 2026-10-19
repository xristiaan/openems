package io.openems.edge.controller.ess.battery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TargetResolutionTest {

	@Test
	void offHasNoTargetEvenWithASetpoint() {
		assertNull(ControllerEssBatteryImpl.resolveTarget(BatteryMode.OFF, 0.70));
	}

	@Test
	void withoutASetpointNoModeHasATarget() {
		for (var mode : BatteryMode.values()) {
			assertNull(ControllerEssBatteryImpl.resolveTarget(mode, null), mode.name());
		}
	}

	@Test
	void anExternalSetpointIsHonouredInOnAndEveryOtherModeWithATarget() {
		for (var mode : new BatteryMode[] { BatteryMode.ON, BatteryMode.BOOST, BatteryMode.FORCED,
				BatteryMode.FORCED_BOOST }) {
			assertEquals(0.70, ControllerEssBatteryImpl.resolveTarget(mode, 0.70), 1e-9, mode.name());
		}
	}
}
