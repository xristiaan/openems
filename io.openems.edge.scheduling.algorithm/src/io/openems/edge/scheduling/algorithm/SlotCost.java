package io.openems.edge.scheduling.algorithm;

/**
 * Slot Cost: "calculates the cost of a slot given energy used, energy price,
 * deviation cost... and switching cost" — plus {@code deviceCostEur}, the
 * non-energy cost a resource's own physics reports (wear/degradation).
 * Shared by {@link PlanTableBuilder} (deciding which mode is cheapest) and
 * {@link ExecutionPlanCalculator} (reporting the realized cost of whichever
 * mode the table actually picked), so the two can never drift apart on what
 * a slot's cost means.
 */
final class SlotCost {

	private SlotCost() {
	}

	/**
	 * Computes one slot's total cost for a candidate mode.
	 *
	 * @param <M>         the controller's own mode type
	 * @param timeSlot    this slot's energy price and power budget
	 * @param target      this slot's deviation-cost target
	 * @param matrix      the mode-switching matrix (for the switching cost
	 *                        between {@code priorMode} and {@code candidate})
	 * @param priorMode   the mode in force entering this slot
	 * @param candidate   the mode actually run this slot
	 * @param stateValue  the state at the start of the slot
	 * @param result      the State-Transition Function's result for
	 *                        {@code candidate}
	 * @return the slot's cost: the energy's cost ({@link TimeSlot#energyCostEur}), plus deviation
	 *             cost, plus switching cost, plus {@code deviceCostEur}
	 */
	static <M> double of(TimeSlot timeSlot, SlotTarget target, ModeSwitchingMatrix<M> matrix, M priorMode,
			M candidate, double stateValue, StateTransitionResult result) {
		var meanState = (stateValue + result.nextState()) / 2.0;
		var deviationCost = target.costOf(meanState);
		var energyCostEur = timeSlot.energyCostEur(result.energyUsedWh());
		var switchingCost = matrix.transition(priorMode, candidate).cost();
		return energyCostEur + deviationCost + switchingCost + result.deviceCostEur();
	}
}
