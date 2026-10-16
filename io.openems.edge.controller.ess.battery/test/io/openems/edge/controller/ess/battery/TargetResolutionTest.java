package io.openems.edge.controller.ess.battery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.scheduling.algorithm.SlotTarget;

class TargetResolutionTest {

	private static final SlotTarget NORMAL_BAND = new SlotTarget(0.20, 0.80, 0.05);

	@Test
	void offHasNoTargetEvenWithASetpoint() {
		assertNull(ControllerEssBatteryImpl.resolveTarget(BatteryMode.OFF, NORMAL_BAND, 0.70));
	}

	@Test
	void withoutASetpointEveryOtherModeSeeksTheMiddleOfTheBand() {
		for (var mode : new BatteryMode[] { BatteryMode.ON, BatteryMode.BOOST, BatteryMode.FORCED,
				BatteryMode.FORCED_BOOST }) {
			assertEquals(0.50, ControllerEssBatteryImpl.resolveTarget(mode, NORMAL_BAND, null), 1e-9, mode.name());
		}
	}

	@Test
	void anExternalSetpointIsHonouredInOnAndEveryOtherModeWithATarget() {
		for (var mode : new BatteryMode[] { BatteryMode.ON, BatteryMode.BOOST, BatteryMode.FORCED,
				BatteryMode.FORCED_BOOST }) {
			assertEquals(0.70, ControllerEssBatteryImpl.resolveTarget(mode, NORMAL_BAND, 0.70), 1e-9, mode.name());
		}
	}
}
