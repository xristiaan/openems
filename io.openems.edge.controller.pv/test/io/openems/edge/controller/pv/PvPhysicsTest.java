package io.openems.edge.controller.pv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PvPhysicsTest {

	private static final double RATED_W = 4_000;
	private static final double NO_BUDGET_CAP = -Double.MAX_VALUE;

	private static PvPhysics physics(boolean curtailmentSupported, double... forecastW) {
		return new PvPhysics(new PvPhysicsConfig(RATED_W, 0.05, 1.0, curtailmentSupported, forecastW));
	}

	@Test
	void onProducesWhatTheSunAllowsAsNegativeEnergy() {
		var result = physics(true, 2_000).apply(0, PvMode.ON, 0, NO_BUDGET_CAP, null);

		assertEquals(-2_000, result.nextState(), 1e-9, "nextState is the output power, production negative");
		assertEquals(-500, result.energyUsedWh(), 1e-9, "2kW for a quarter hour, production negative");
		assertEquals(0, result.deviceCostEur(), 1e-9);
	}

	@Test
	void productionIsCappedAtTheInverterRating() {
		var result = physics(true, 6_000).apply(0, PvMode.ON, 0, NO_BUDGET_CAP, null);

		assertEquals(-4_000, result.nextState(), 1e-9);
	}

	@Test
	void offCurtailsToTheMinimumControllablePower() {
		var result = physics(true, 3_000).apply(0, PvMode.OFF, 0, NO_BUDGET_CAP, -1_000.0);

		assertEquals(-200, result.nextState(), 1e-9, "P_min = 5% of 4kW; OFF ignores the set-point");
	}

	@Test
	void onHonoursAnOptionalSetpoint() {
		var result = physics(true, 3_000).apply(0, PvMode.ON, 0, NO_BUDGET_CAP, -1_500.0);

		assertEquals(-1_500, result.nextState(), 1e-9);
	}

	@Test
	void forcedWithoutASetpointBehavesLikeOn() {
		var forced = physics(true, 3_000).apply(0, PvMode.FORCED, 0, NO_BUDGET_CAP, null);
		var on = physics(true, 3_000).apply(0, PvMode.ON, 0, NO_BUDGET_CAP, null);

		assertEquals(on.nextState(), forced.nextState(), 1e-9);
	}

	@Test
	void theNegativeBudgetCapsProduction() {
		var result = physics(true, 3_000).apply(0, PvMode.ON, 0, -1_000, null);

		assertEquals(-1_000, result.nextState(), 1e-9);
	}

	@Test
	void aBudgetBelowTheMinimumStillProducesTheMinimum() {
		var result = physics(true, 3_000).apply(0, PvMode.ON, 0, 0, null);

		assertEquals(-200, result.nextState(), 1e-9, "the inverter can't go below P_min");
	}

	@Test
	void withoutCurtailmentSupportEveryModeProducesWhatTheSunAllows() {
		var result = physics(false, 3_000).apply(0, PvMode.OFF, 0, -1_000, -500.0);

		assertEquals(-3_000, result.nextState(), 1e-9);
	}

	@Test
	void noForecastBeyondTheEndOfTheArrayMeansNoProduction() {
		var result = physics(true, 3_000).apply(0, PvMode.ON, 5, NO_BUDGET_CAP, null);

		assertEquals(0, result.nextState(), 1e-9);
	}
}
