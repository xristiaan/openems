package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.hvac.api.HvacMode;
import io.openems.edge.scheduling.algorithm.TimeSlot;

class ZonePhysicsTest {

	// A 40 m² office: C = 1.8 kWh/K, H = 50 W/K (time constant 36 h)
	static ZonePhysicsConfig office(HvacMode mode, double outdoorC, Efficiency efficiency) {
		return new ZonePhysicsConfig(mode, 1.8, 50, 1000, 2000, 5, 30, efficiency, new double[] { outdoorC });
	}

	// The same room with a device strong enough to reach a target within one slot
	static ZonePhysicsConfig strong(HvacMode mode, double outdoorC, Efficiency efficiency) {
		return new ZonePhysicsConfig(mode, 1.8, 50, 6000, 8000, 5, 30, efficiency, new double[] { outdoorC });
	}

	private static final Efficiency RESISTIVE = Efficiency.parse("", "");

	@Test
	void withoutHeatTheRoomCoolsTowardsOutside() {
		var physics = new ZonePhysics(office(HvacMode.HEAT, 0, RESISTIVE));
		var k = Math.exp(-50 * TimeSlot.DURATION_HOURS / 1800.0);

		assertEquals(20 * k, physics.endTemperature(20, 0, 0), 1e-9);
	}

	@Test
	void onHeatsExactlyToTheTargetWhenThePowerSuffices() {
		var physics = new ZonePhysics(strong(HvacMode.HEAT, 5, RESISTIVE));

		var result = physics.apply(19.5, ComfortZoneMode.ON, 0, Double.MAX_VALUE, 20.0);

		assertEquals(20, result.nextState(), 1e-9);
		// 0.5 K × 1.8 kWh/K plus the loss at ~15 K × 50 W for a quarter of an hour
		assertEquals(900 + 15 * 50 * 0.25, result.energyUsedWh(), 15);
	}

	@Test
	void thePowerIsCappedByTheModeAndTheBudget() {
		var physics = new ZonePhysics(office(HvacMode.HEAT, 0, RESISTIVE));

		var on = physics.apply(15, ComfortZoneMode.ON, 0, Double.MAX_VALUE, 20.0);
		var boost = physics.apply(15, ComfortZoneMode.BOOST, 0, Double.MAX_VALUE, 21.5);
		var budget = physics.apply(15, ComfortZoneMode.BOOST, 0, 500, 21.5);

		assertEquals(1000 * 0.25, on.energyUsedWh(), 1e-6, "ON: nominal power");
		assertEquals(2000 * 0.25, boost.energyUsedWh(), 1e-6, "BOOST: maximum power");
		assertEquals(500 * 0.25, budget.energyUsedWh(), 1e-6, "the budget caps both");
	}

	@Test
	void theCopDividesTheElectricalEnergy() {
		var heatPump = new ZonePhysics(office(HvacMode.HEAT, 0, Efficiency.parse("0/25=4", "")));
		var resistive = new ZonePhysics(office(HvacMode.HEAT, 0, RESISTIVE));

		var withCop = heatPump.apply(15, ComfortZoneMode.ON, 0, Double.MAX_VALUE, 20.0);
		var withoutCop = resistive.apply(15, ComfortZoneMode.ON, 0, Double.MAX_VALUE, 20.0);

		// both at 1000 W for the quarter; with a COP of 4 that is 4 kW of heat
		assertEquals(250, withCop.energyUsedWh(), 1e-6);
		assertEquals(250, withoutCop.energyUsedWh(), 1e-6);
		assertEquals(80 - 65 * Math.exp(-50 * 0.25 / 1800.0), withCop.nextState(), 1e-9);
		assertTrue(withCop.nextState() > withoutCop.nextState() + 0.4);
	}

	@Test
	void offOnlyProtectsAgainstFrost() {
		var physics = new ZonePhysics(office(HvacMode.HEAT, -10, RESISTIVE));

		var warm = physics.apply(18, ComfortZoneMode.OFF, 0, Double.MAX_VALUE, null);
		var cold = physics.apply(5, ComfortZoneMode.OFF, 0, Double.MAX_VALUE, null);

		assertEquals(0, warm.energyUsedWh(), 1e-9);
		assertEquals(5, cold.nextState(), 1e-9);
	}

	@Test
	void coolingWorksTheOtherWayRound() {
		var physics = new ZonePhysics(strong(HvacMode.COOL, 32, Efficiency.parse("", "35=3")));

		var result = physics.apply(26, ComfortZoneMode.ON, 0, Double.MAX_VALUE, 24.0);

		assertEquals(24, result.nextState(), 1e-9);
		assertTrue(result.energyUsedWh() > 0, "cooling uses energy too");
	}
}
