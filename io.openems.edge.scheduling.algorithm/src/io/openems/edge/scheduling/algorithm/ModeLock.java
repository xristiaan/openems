package io.openems.edge.scheduling.algorithm;

/**
 * Shared remaining-min-runtime bookkeeping, used identically by
 * {@link PlanTableBuilder}'s backward pass and {@link ExecutionPlanCalculator}'s
 * forward walk so the two can never drift apart on what "lock" means.
 */
final class ModeLock {

	private ModeLock() {
	}

	/**
	 * The remaining forced-dwell count after running {@code candidate} for
	 * one slot, having entered that slot with {@code lock} remaining in
	 * {@code priorMode}.
	 *
	 * @param <M>       the controller's own mode type
	 * @param matrix    the mode-switching matrix (for {@code minSlotsInMode})
	 * @param priorMode the mode in force entering this slot
	 * @param lock      remaining forced-dwell slots entering this slot (0 if
	 *                      free)
	 * @param candidate the mode actually run this slot
	 * @return the remaining forced-dwell count for the next slot
	 */
	static <M extends Enum<M>> int next(ModeSwitchingMatrix<M> matrix, M priorMode, int lock, M candidate) {
		if (candidate.equals(priorMode)) {
			// Continuing the same mode: either still counting down a forced
			// dwell, or already free (0 stays 0 — staying doesn't impose a
			// fresh lock on an already-free device).
			return lock > 0 ? lock - 1 : 0;
		}
		// A genuine switch always requires lock == 0 to have been a legal
		// candidate in the first place; entering `candidate` starts its own
		// minimum-dwell countdown, minus the slot just used to enter it.
		return Math.max(0, matrix.minSlotsInMode(candidate) - 1);
	}
}
