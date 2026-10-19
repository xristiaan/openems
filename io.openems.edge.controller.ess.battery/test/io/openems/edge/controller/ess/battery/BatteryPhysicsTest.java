package io.openems.edge.controller.ess.battery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BatteryPhysicsTest {

	private static final double CAPACITY_WH = 10_000;

	private static BatteryPhysics physics() {
		return new BatteryPhysics(new BatteryPhysicsConfig(//
				CAPACITY_WH, //
				0.20, 0.80, //
				0.10, 0.90, //
				0.25, 0.25, //
				0.50, 0.50, //
				0.25, 0.50, //
				0.50, 1.00, //
				0.95, 0.95, //
				0.10, 2.0));
	}

	@Test
	void chargingIncreasesStateAndCostsEnergyAndDegradation() {
		var result = physics().apply(0.50, BatteryMode.ON, 0, Double.MAX_VALUE, 0.80);

		// 0.25C on a 10kWh battery is 2500W; 900s-capped, 0.95 charge
		// efficiency: energy used (drawn from grid) should be positive.
		assertTrue(result.energyUsedWh() > 0, "charging should consume positive energy");
		assertTrue(result.nextState() > 0.50, "SoC should increase while charging");
		assertTrue(result.deviceCostEur() > 0, "degradation cost should be positive");
	}

	@Test
	void dischargingDecreasesStateAndScoresAsABenefit() {
		var result = physics().apply(0.50, BatteryMode.BOOST, 0, -Double.MAX_VALUE, 0.10);

		assertTrue(result.energyUsedWh() < 0, "discharging should score as negative energy cost (a benefit)");
		assertTrue(result.nextState() < 0.50, "SoC should decrease while discharging");
		assertTrue(result.deviceCostEur() > 0, "degradation cost is always positive regardless of direction");
	}

	@Test
	void withoutASetpointAPositiveBudgetIsAbsorbedForTheWholeSlot() {
		// 1kW of surplus for 15 minutes: 250Wh drawn, 0.95 of it stored.
		var result = physics().apply(0.50, BatteryMode.ON, 0, 1_000, null);

		assertEquals(250, result.energyUsedWh(), 1e-9);
		assertEquals(0.50 + 237.5 / CAPACITY_WH, result.nextState(), 1e-9);
	}

	@Test
	void withoutASetpointANegativeBudgetIsProvidedForTheWholeSlot() {
		// 1kW of shortfall for 15 minutes: 250Wh delivered, 250/0.95 taken out.
		var result = physics().apply(0.50, BatteryMode.ON, 0, -1_000, null);

		assertEquals(-250, result.energyUsedWh(), 1e-9);
		assertEquals(0.50 - 250 / 0.95 / CAPACITY_WH, result.nextState(), 1e-9);
	}

	@Test
	void aZeroBudgetMeansIdleRegardlessOfMode() {
		var result = physics().apply(0.50, BatteryMode.BOOST, 0, 0, null);

		assertEquals(0, result.energyUsedWh());
		assertEquals(0.50, result.nextState());
		assertEquals(0, result.deviceCostEur());
	}

	@Test
	void aSetpointAgainstTheBudgetsDirectionMeansIdle() {
		// Surplus to absorb, but the set-point asks to go down.
		var result = physics().apply(0.50, BatteryMode.ON, 0, 2_000, 0.30);

		assertEquals(0, result.energyUsedWh());
		assertEquals(0.50, result.nextState());
	}

	@Test
	void forcedWithoutASetpointBehavesLikeOn() {
		var forced = physics().apply(0.50, BatteryMode.FORCED, 0, -Double.MAX_VALUE, null);
		var on = physics().apply(0.50, BatteryMode.ON, 0, -Double.MAX_VALUE, null);

		assertEquals(on.energyUsedWh(), forced.energyUsedWh(), 1e-9);
		assertEquals(on.nextState(), forced.nextState(), 1e-9);
	}

	@Test
	void realTimeFollowingBringsTheGridToZeroWithinTheLimits() {
		// Importing 800W while idle: discharge 800W.
		assertEquals(800, BatteryPhysics.followResidualW(0, 800, 2_500, 2_500));
		// Exporting 3kW while charging 1kW: charge 4kW, capped at 2.5kW.
		assertEquals(-2_500, BatteryPhysics.followResidualW(-1_000, -3_000, 2_500, 2_500));
		// Shortfall, but discharging isn't allowed (bottom of the band).
		assertEquals(0, BatteryPhysics.followResidualW(0, 800, 2_500, 0));
	}

	@Test
	void theRealTimeLimitIsZeroAtTheBandEdge() {
		assertEquals(0, physics().maxChargeW(BatteryMode.ON, 0.80), 1e-9);
		assertEquals(2_500, physics().maxDischargeW(BatteryMode.ON, 0.80), 1e-9);
		assertEquals(5_000, physics().maxChargeW(BatteryMode.BOOST, 0.80), 1e-9, "BOOST uses the safe band");
		assertEquals(0, physics().maxChargeW(BatteryMode.OFF, 0.50), 1e-9);
	}

	@Test
	void offModeIsAlwaysIdleEvenWithATarget() {
		var result = physics().apply(0.50, BatteryMode.OFF, 0, Double.MAX_VALUE, 0.90);

		assertEquals(0, result.energyUsedWh());
		assertEquals(0.50, result.nextState());
	}

	@Test
	void onModeCannotBePushedPastItsOwnNormalBandEvenWhenAskedTo() {
		// Asking ON (NORMAL band caps at 0.80) to reach 0.95 should stop
		// exactly at 0.80, not overshoot toward the requested target.
		var result = physics().apply(0.75, BatteryMode.ON, 0, Double.MAX_VALUE, 0.95);

		assertEquals(0.80, result.nextState(), 1e-9);
	}

	@Test
	void onModeAlreadyPastItsOwnBandCannotMoveFurtherInThatDirection() {
		// Already above the NORMAL band's 0.80 ceiling (e.g. left over from
		// a BOOST slot) and still asked to charge further: must report zero
		// remaining room, not some positive distance measured from the
		// wrong side of the edge.
		var result = physics().apply(0.85, BatteryMode.ON, 0, Double.MAX_VALUE, 0.90);

		assertEquals(0.85, result.nextState(), 1e-9);
		assertEquals(0, result.energyUsedWh());
	}

	@Test
	void boostCRateDoublesTheDegradationCost() {
		// ON charges at 0.25C (below the 0.5C boost threshold); BOOST
		// charges at 0.50C (at the threshold) for the same SoC move.
		var on = physics().apply(0.50, BatteryMode.ON, 0, Double.MAX_VALUE, 0.51);
		var boost = physics().apply(0.50, BatteryMode.BOOST, 0, Double.MAX_VALUE, 0.51);

		// Boost moves roughly twice as much energy (double the C-rate) in
		// the same capped 900s, so compare degradation cost per Wh moved.
		var onCostPerWh = on.deviceCostEur() / Math.abs(on.energyUsedWh());
		var boostCostPerWh = boost.deviceCostEur() / Math.abs(boost.energyUsedWh());
		assertEquals(onCostPerWh * 2, boostCostPerWh, 1e-9);
	}

	@Test
	void availablePowerBudgetClipsTheOperatingPower() {
		// 0.25C on 10kWh is 2500W; capping the budget to 500W should result
		// in proportionally less energy moved than the uncapped case.
		var uncapped = physics().apply(0.50, BatteryMode.ON, 0, Double.MAX_VALUE, 0.80);
		var capped = physics().apply(0.50, BatteryMode.ON, 0, 500, 0.80);

		assertTrue(capped.energyUsedWh() < uncapped.energyUsedWh());
	}
}
