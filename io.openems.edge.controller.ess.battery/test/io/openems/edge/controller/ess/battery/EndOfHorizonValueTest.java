package io.openems.edge.controller.ess.battery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.scheduling.algorithm.ExecutionPlanCalculator;
import io.openems.edge.scheduling.algorithm.PlanTableBuilder;
import io.openems.edge.scheduling.algorithm.PlanableResourceModel;
import io.openems.edge.scheduling.algorithm.SlotTarget;
import io.openems.edge.scheduling.algorithm.StateBuckets;
import io.openems.edge.scheduling.algorithm.TerminalValue;
import io.openems.edge.scheduling.algorithm.TimeSlot;

class EndOfHorizonValueTest {

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
				0.03, 2.0));
	}

	@Test
	void storedEnergyIsWorthTheAverageBuyPriceLessLossesAndWear() {
		var slots = List.of(new TimeSlot(0.20, 1_000), new TimeSlot(0.40, 1_000));

		var value = ControllerEssBatteryImpl.storedEnergyValueEurPerKwh(slots, 1.0, 0.95, 0.03);

		assertEquals(0.30 * 0.95 - 0.03, value, 1e-12);
	}

	@Test
	void factorZeroOrCheapEnergyMakesStoredEnergyWorthless() {
		var slots = List.of(new TimeSlot(0.02, 1_000));

		assertEquals(0, ControllerEssBatteryImpl.storedEnergyValueEurPerKwh(slots, 0, 0.95, 0.03), 1e-12);
		assertEquals(0, ControllerEssBatteryImpl.storedEnergyValueEurPerKwh(slots, 1.0, 0.95, 0.03), 1e-12,
				"0.02 * 0.95 is less than the wear");
	}

	/**
	 * One slot of a free PV surplus (it would be curtailed at a negative sell
	 * price), the battery half full.
	 *
	 * @param terminalValue what stored energy is worth after the slot
	 * @return the SoC at the end of the slot
	 */
	private static double endSocTakingAFreeSurplus(TerminalValue terminalValue) {
		var slots = List.of(new TimeSlot(0.10, -0.025, -3_000, 3_000, Double.MAX_VALUE, 3_000));
		var targets = Collections.nCopies(1, new SlotTarget(0.20, 0.80, 0.05));
		var model = new PlanableResourceModel<>(BatteryMode.values(), new StateBuckets(0, 1, 0.02),
				new BatteryModeSwitchingMatrix(), physics(), (mode, slot) -> null, terminalValue);

		var table = PlanTableBuilder.build(model, slots, targets);
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, 0.50, BatteryMode.OFF, 0);
		return plan.slots().get(0).endState();
	}

	@Test
	void withoutAnEndOfHorizonValueTheBatteryLeavesAFreeSurplus() {
		// Free energy, but charging still wears the battery, and nothing
		// stored is worth anything once the plan ends.
		assertEquals(0.50, endSocTakingAFreeSurplus(TerminalValue.NONE), 1e-9);
	}

	@Test
	void withAnEndOfHorizonValueTheBatteryStoresAFreeSurplus() {
		TerminalValue worth = endSoc -> endSoc * CAPACITY_WH / 1000.0 * 0.30;

		assertTrue(endSocTakingAFreeSurplus(worth) > 0.55);
	}
}
