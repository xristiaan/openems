package io.openems.edge.scheduling.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A small, hand-verifiable scenario: a toy charger with two modes (ON adds
 * 20% state per slot, OFF adds nothing), six 15-minute slots with strictly
 * increasing prices (1..6 EUR/kWh), and a hard deadline target of 80% at the
 * last slot. Reaching 80% needs exactly 4 ON slots; since state only
 * depends on how many slots were ON (not which), the cost-minimizing plan
 * must pick the 4 <em>cheapest</em> slots to be ON — slots 0-3 — and leave
 * the two most expensive (4, 5) OFF.
 */
class PlanTableBuilderTest {

	private enum TestMode {
		OFF, ON
	}

	private static final double RATED_POWER_W = 1000;
	private static final double PERCENT_PER_FULL_POWER_SLOT = 20;

	private static StateTransitionFunction<TestMode> chargerPhysics() {
		return (currentState, mode, slot, availablePowerW, targetSetpoint) -> {
			if (mode == TestMode.OFF) {
				return new StateTransitionResult(0, currentState);
			}
			var pElec = Math.min(availablePowerW, RATED_POWER_W);
			var delta = pElec / RATED_POWER_W * PERCENT_PER_FULL_POWER_SLOT;
			var energyUsedWh = pElec * 0.25; // 15-minute slot
			return new StateTransitionResult(energyUsedWh, Math.min(100, currentState + delta));
		};
	}

	private static PlanableResourceModel<TestMode> model() {
		return model(TerminalValue.NONE);
	}

	private static PlanableResourceModel<TestMode> model(TerminalValue terminalValue) {
		ModeSwitchingMatrix<TestMode> freelySwitchable = new ModeSwitchingMatrix<>() {
			@Override
			public ModeTransition transition(TestMode priorMode, TestMode currentMode) {
				return ModeTransition.FREE;
			}

			@Override
			public int minSlotsInMode(TestMode mode) {
				return 1;
			}
		};
		return new PlanableResourceModel<>(TestMode.values(), new StateBuckets(0, 100, 20), freelySwitchable,
				chargerPhysics(), (mode, slot) -> null, terminalValue);
	}

	@Test
	void withoutATerminalValueNothingIsStoredForAfterTheHorizon() {
		var slots = List.of(new TimeSlot(1, 2000), new TimeSlot(2, 2000));
		var targets = List.of(SlotTarget.unconstrained(), SlotTarget.unconstrained());

		var table = PlanTableBuilder.build(model(), slots, targets);
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, 0, TestMode.OFF, 0);

		assertEquals(0, plan.slots().get(1).endState(), 1e-9);
	}

	@Test
	void aTerminalValueAboveThePriceStoresInTheCheapSlotOnly() {
		// 250 Wh per ON slot = 20 % state: worth 1.5 EUR/kWh after the horizon,
		// so charging pays at 1 EUR/kWh but not at 2.
		var slots = List.of(new TimeSlot(1, 2000), new TimeSlot(2, 2000));
		var targets = List.of(SlotTarget.unconstrained(), SlotTarget.unconstrained());
		TerminalValue worth = endState -> endState / PERCENT_PER_FULL_POWER_SLOT * 0.25 * 1.5;

		var table = PlanTableBuilder.build(model(worth), slots, targets);
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, 0, TestMode.OFF, 0);

		var modes = plan.slots().stream().map(PlannedSlot::mode).toList();
		assertEquals(List.of(TestMode.ON, TestMode.OFF), modes);
		assertEquals(20, plan.slots().get(1).endState(), 1e-9);
	}

	@Test
	void picksTheFourCheapestSlotsToReachTheDeadline() {
		var slots = List.of(//
				new TimeSlot(1, 2000), //
				new TimeSlot(2, 2000), //
				new TimeSlot(3, 2000), //
				new TimeSlot(4, 2000), //
				new TimeSlot(5, 2000), //
				new TimeSlot(6, 2000));
		var targets = List.of(//
				SlotTarget.unconstrained(), //
				SlotTarget.unconstrained(), //
				SlotTarget.unconstrained(), //
				SlotTarget.unconstrained(), //
				SlotTarget.unconstrained(), //
				SlotTarget.point(80, 1000));

		var table = PlanTableBuilder.build(model(), slots, targets);
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, 0, TestMode.OFF, 0);

		var modes = plan.slots().stream().map(PlannedSlot::mode).toList();
		assertEquals(List.of(TestMode.ON, TestMode.ON, TestMode.ON, TestMode.ON, TestMode.OFF, TestMode.OFF), modes);
		assertEquals(80, plan.slots().get(5).endState(), 1e-9);
	}

	@Test
	void leastSlackTimeMatchesTheHandComputedLatestStart() {
		// From 0%, reaching 80% at full power needs exactly 4 slots; with a
		// deadline at slot 5 (6 slots, 0-indexed), the latest start that
		// still allows 4 full-power slots through the deadline is slot 2
		// (slots 2,3,4,5).
		var latestSafeStart = LeastSlackTimeCalculator.latestSafeStart(chargerPhysics(), TestMode.ON, 0, 5, 80,
				state -> state >= 80 - 1e-9);
		assertEquals(2, latestSafeStart);

		var slack = LeastSlackTimeCalculator.slack(chargerPhysics(), TestMode.ON, 0, 0, 5, 80,
				state -> state >= 80 - 1e-9);
		assertEquals(2, slack);
	}
}
