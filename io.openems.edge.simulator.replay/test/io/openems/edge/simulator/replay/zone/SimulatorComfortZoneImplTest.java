package io.openems.edge.simulator.replay.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.hvac.api.HvacMode;
import io.openems.edge.simulator.replay.zone.SimulatorComfortZoneImpl.Room;

class SimulatorComfortZoneImplTest {

	private static final Room OFFICE = new Room(1.8, 50, 2000, 3.5, 3.5, 0.5);

	@Test
	void offTheRoomLosesHeatToOutside() {
		// 15 K difference × 50 W/K = 750 W for an hour: 0.75 kWh / 1.8 kWh/K
		var step = SimulatorComfortZoneImpl.step(OFFICE, 20, 5, false, HvacMode.HEAT, 21, 3600);

		assertEquals(20 - 0.75 / 1.8, step.roomC(), 1e-9);
		assertEquals(0, step.electricalW());
	}

	@Test
	void theThermostatRunsAtFullPowerWellBelowTheSetpoint() {
		var step = SimulatorComfortZoneImpl.step(OFFICE, 19, 5, true, HvacMode.HEAT, 21, 1);

		assertEquals(2000, step.electricalW());
		assertTrue(step.roomC() > 19);
	}

	@Test
	void theThermostatIsProportionalNearTheSetpoint() {
		var step = SimulatorComfortZoneImpl.step(OFFICE, 20.75, 5, true, HvacMode.HEAT, 21, 1);

		assertEquals(1000, step.electricalW(), 1e-9);
	}

	@Test
	void coolingRunsAboveTheSetpoint() {
		var step = SimulatorComfortZoneImpl.step(OFFICE, 26, 30, true, HvacMode.COOL, 24, 1);

		assertEquals(2000, step.electricalW());
		assertTrue(step.roomC() < 26);
	}
}
