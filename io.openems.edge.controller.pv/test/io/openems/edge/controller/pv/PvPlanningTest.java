package io.openems.edge.controller.pv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.scheduling.algorithm.ExecutionPlanCalculator;
import io.openems.edge.scheduling.algorithm.PlanTableBuilder;
import io.openems.edge.scheduling.algorithm.PlanableResourceModel;
import io.openems.edge.scheduling.algorithm.SlotTarget;
import io.openems.edge.scheduling.algorithm.StateBuckets;
import io.openems.edge.scheduling.algorithm.TimeSlot;

/**
 * The PV physics run through the real DP core, the same way
 * {@link ControllerPvImpl#createPlan} wires it.
 */
class PvPlanningTest {

	private static final PvMode[] MODES = { PvMode.ON, PvMode.BOOST, PvMode.FORCED, PvMode.OFF };

	private static PvPhysicsConfig config(double... forecastW) {
		return new PvPhysicsConfig(4_000, 0.05, 1.0, true, forecastW);
	}

	private static List<PvMode> plannedModes(PvPhysicsConfig config, List<TimeSlot> slots) {
		var model = new PlanableResourceModel<>(MODES, new StateBuckets(0, 0, 1), new PvModeSwitchingMatrix(),
				new PvPhysics(config), (mode, slot) -> ControllerPvImpl.resolveTarget(mode, null));
		var targets = Collections.nCopies(slots.size(), SlotTarget.unconstrained());
		var table = PlanTableBuilder.build(model, slots, targets);
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, 0, PvMode.ON, 0);
		return plan.slots().stream().map(slot -> slot.mode()).toList();
	}

	@Test
	void curtailsAtNegativePricesAndProducesAtPositivePrices() {
		var config = config(3_000, 3_000, 0);
		var slots = List.of(//
				new TimeSlot(0.25, -3_000), //
				new TimeSlot(-0.10, -3_000), //
				new TimeSlot(0.25, 0)); // night: no production either way

		assertEquals(List.of(PvMode.ON, PvMode.OFF, PvMode.ON), plannedModes(config, slots));
	}

	@Test
	void forcedResolvesNoSetpointWithoutOneAndOffNeverHasOne() {
		assertNull(ControllerPvImpl.resolveTarget(PvMode.FORCED, null));
		assertNull(ControllerPvImpl.resolveTarget(PvMode.OFF, -1_000.0));
		assertEquals(-1_000.0, ControllerPvImpl.resolveTarget(PvMode.ON, -1_000.0));
	}

	@Test
	void aBudgetThatOnlyEqualsTheForecastDoesNotCurtailTheCommand() {
		var config = config(2_000);
		var physics = new PvPhysics(config);

		// Budget = −forecast (not binding): command P_max, so real production
		// above a too-low forecast isn't cut off.
		assertEquals(4_000, ControllerPvImpl.commandLimitW(physics, config, PvMode.ON, -2_000, null, 0), 1e-9);
		// Budget below the forecast (export limit): binding, command it.
		assertEquals(1_200, ControllerPvImpl.commandLimitW(physics, config, PvMode.ON, -1_200, null, 0), 1e-9);
	}
}
