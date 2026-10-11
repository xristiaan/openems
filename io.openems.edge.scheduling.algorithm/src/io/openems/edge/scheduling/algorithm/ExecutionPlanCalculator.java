package io.openems.edge.scheduling.algorithm;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculate Execution Plan: the forward walk. Starts from the real measured
 * current state and follows the table's {@code bestMode} at each step,
 * always advancing the <em>true continuous</em> state returned by the
 * State-Transition Function — never the rounded bucket — so rounding error
 * never compounds across slots. The bucket is used only to look up each
 * slot's table cell.
 */
public final class ExecutionPlanCalculator {

	private ExecutionPlanCalculator() {
	}

	/**
	 * Walks the table forward from the current real state to produce an
	 * execution plan.
	 *
	 * @param <M>            the controller's own mode type
	 * @param table          the planning table from {@link PlanTableBuilder}
	 * @param slots          the same per-slot energy price/power budget used
	 *                           to build {@code table}
	 * @param currentState   the real, measured current state (not a bucket
	 *                           value)
	 * @param currentMode    the mode the device is actually in right now
	 * @param lockRemaining  the device's actual remaining forced-dwell count
	 *                           right now (0 if free to switch)
	 * @return the execution plan
	 */
	public static <M extends Enum<M>> ExecutionPlan<M> walk(PlanTable<M> table, List<TimeSlot> slots,
			double currentState, M currentMode, int lockRemaining) {
		var model = table.model();
		var plannedSlots = new ArrayList<PlannedSlot<M>>(table.slotCount());

		var state = currentState;
		var priorMode = currentMode;
		var lock = lockRemaining;

		for (var slot = 0; slot < table.slotCount(); slot++) {
			var bucket = model.buckets().indexOf(state);
			var cell = table.get(slot, bucket, priorMode, lock);
			var mode = cell.bestMode();

			var targetSetpoint = model.targetSetpointResolver().resolve(mode, slot);
			var result = model.stateTransitionFunction().apply(state, mode, slot, slots.get(slot).availablePowerW(),
					targetSetpoint);

			plannedSlots.add(new PlannedSlot<>(mode, targetSetpoint, result.energyUsedWh(), result.nextState()));

			lock = ModeLock.next(model.switchingMatrix(), priorMode, lock, mode);
			priorMode = mode;
			state = result.nextState();
		}

		return new ExecutionPlan<>(plannedSlots);
	}
}
