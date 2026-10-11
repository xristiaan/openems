package io.openems.edge.scheduling.algorithm;

import java.util.List;

/**
 * Calculate DP Table: the backward pass. "For each slot n, a row is created
 * for every bucket, prior mode, current mode combination... cost is
 * calculated in this order: (1) end state within power usage caps, (2) mean
 * deviation during the slot (avg(startState, endState) − target), (3)
 * energy actually used, (4) cost via Slot Cost + the already-computed cost
 * of slot n+1."
 *
 * <p>
 * No special terminal-cost function at the last slot: scoring every slot's
 * deviation on avg(startState, endState) — including the last — already
 * removes the end-of-horizon bias a begin-state-only convention would have,
 * so the last slot's cost-to-go is simply 0 (there is no slot N).
 */
public final class PlanTableBuilder<M extends Enum<M>> {

	private PlanTableBuilder() {
	}

	/**
	 * Builds the planning table via the backward pass described above.
	 *
	 * @param <M>    the controller's own mode type
	 * @param model  the device's modes, buckets, switching matrix and
	 *                   physics
	 * @param slots  one entry per slot: energy price and the power budget
	 *                   assigned to this resource
	 * @param targets one entry per slot: the deviation-cost target, same
	 *                   length as {@code slots}
	 * @return the planning table
	 */
	public static <M extends Enum<M>> PlanTable<M> build(PlanableResourceModel<M> model, List<TimeSlot> slots,
			List<SlotTarget> targets) {
		if (slots.size() != targets.size()) {
			throw new IllegalArgumentException("slots and targets must have the same length");
		}
		var slotCount = slots.size();
		var modes = model.modes();
		var buckets = model.buckets();
		var matrix = model.switchingMatrix();

		var maxLock = 0;
		for (var mode : modes) {
			maxLock = Math.max(maxLock, matrix.minSlotsInMode(mode) - 1);
		}

		var table = new PlanTable<M>(model, slotCount, maxLock);

		for (var slot = slotCount - 1; slot >= 0; slot--) {
			var timeSlot = slots.get(slot);
			var target = targets.get(slot);

			for (var bucket = 0; bucket < buckets.count(); bucket++) {
				var stateValue = buckets.valueOf(bucket);

				for (var priorMode : modes) {
					for (var lock = 0; lock <= maxLock; lock++) {
						M bestMode = null;
						var bestCost = Double.POSITIVE_INFINITY;

						for (var candidate : candidatesFor(modes, matrix, priorMode, lock)) {
							var total = costOf(model, timeSlot, target, table, slot, slotCount, stateValue, priorMode,
									lock, candidate);
							if (total < bestCost) {
								bestCost = total;
								bestMode = candidate;
							}
						}

						table.set(slot, bucket, priorMode, lock, bestMode, bestCost);
					}
				}
			}
		}

		return table;
	}

	private static <M extends Enum<M>> M[] candidatesFor(M[] modes, ModeSwitchingMatrix<M> matrix, M priorMode,
			int lock) {
		if (lock > 0) {
			// Locked in: the only legal choice is to continue the prior mode.
			var only = java.util.Arrays.copyOf(modes, 1);
			only[0] = priorMode;
			return only;
		}
		return java.util.Arrays.stream(modes) //
				.filter(candidate -> matrix.transition(priorMode, candidate).allowed()) //
				.toArray(size -> java.util.Arrays.copyOf(modes, size));
	}

	private static <M extends Enum<M>> double costOf(PlanableResourceModel<M> model, TimeSlot timeSlot,
			SlotTarget target, PlanTable<M> table, int slot, int slotCount, double stateValue, M priorMode, int lock,
			M candidate) {
		var targetSetpoint = model.targetSetpointResolver().resolve(candidate, slot);
		var result = model.stateTransitionFunction().apply(stateValue, candidate, slot, timeSlot.availablePowerW(),
				targetSetpoint);

		var meanState = (stateValue + result.nextState()) / 2.0;
		var deviationCost = target.costOf(meanState);
		var energyCostEur = result.energyUsedWh() / 1000.0 * timeSlot.energyPriceEurPerKwh();
		var switchingCost = model.switchingMatrix().transition(priorMode, candidate).cost();
		var slotCost = energyCostEur + deviationCost + switchingCost;

		var newLock = ModeLock.next(model.switchingMatrix(), priorMode, lock, candidate);
		var nextBucket = model.buckets().indexOf(result.nextState());
		var costToGo = slot == slotCount - 1 ? 0 : table.get(slot + 1, nextBucket, candidate, newLock).costToGo();

		return slotCost + costToGo;
	}
}
