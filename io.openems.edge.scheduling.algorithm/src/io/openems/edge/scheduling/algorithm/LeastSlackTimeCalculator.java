package io.openems.edge.scheduling.algorithm;

import java.util.function.DoublePredicate;

/**
 * Least Slack Time: "used to prioritize the scheduling of various
 * plan-able controllers; the shorter the LST, the higher the priority."
 * Finds the latest slot a resource could still start running its highest-
 * power mode, ignoring cost and any allocator budget (physical capability
 * only), and still reach the target by {@code deadlineSlot}. Slack is the
 * remaining headroom before that becomes the device's only option.
 *
 * <p>
 * This is a feasibility heuristic, not a cost-optimal plan — it reuses the
 * same {@link StateTransitionFunction} physics as {@link PlanTableBuilder},
 * just run once per candidate start slot instead of built into a full DP
 * table, since no mode/cost tradeoff is being evaluated here. Generalizes
 * past deadline resources: for a continuous-schedule resource (Comfort
 * Zone), {@code deadlineSlot}/{@code targetSetpoint}/{@code reached} are
 * simply the next set-point in the schedule, not a true deadline.
 */
public final class LeastSlackTimeCalculator {

	private LeastSlackTimeCalculator() {
	}

	/**
	 * Finds the latest slot this resource could still start its catch-up run
	 * and reach the target by the deadline.
	 *
	 * @param <M>              the controller's own mode type
	 * @param stateTransitionFunction the device's physics
	 * @param highestPowerMode the mode to assume while urgently catching up
	 * @param currentState     the real, measured current state
	 * @param deadlineSlot     the slot by which the target must be met
	 * @param targetSetpoint   the concrete value to physically aim for while
	 *                             catching up (fed to the State-Transition
	 *                             Function as its target)
	 * @param reached          tests whether a final state counts as having
	 *                             met the target (e.g. {@code s -> s >= 80}
	 *                             for an EV's 80%-by-deadline target, or an
	 *                             exact-match tolerance for a point target)
	 * @return the latest slot index that can still start and make the
	 *             deadline, or -1 if even starting now (slot 0) cannot make
	 *             it (already critical — slack is negative)
	 */
	public static <M> int latestSafeStart(StateTransitionFunction<M> stateTransitionFunction, M highestPowerMode,
			double currentState, int deadlineSlot, double targetSetpoint, DoublePredicate reached) {
		for (var start = deadlineSlot; start >= 0; start--) {
			if (reachesTargetFrom(stateTransitionFunction, highestPowerMode, currentState, start, deadlineSlot,
					targetSetpoint, reached)) {
				return start;
			}
		}
		return -1;
	}

	/**
	 * Computes how many slots of headroom remain before this resource must
	 * start its catch-up run to still meet the deadline.
	 *
	 * @param <M>              the controller's own mode type
	 * @param stateTransitionFunction the device's physics
	 * @param highestPowerMode the mode to assume while urgently catching up
	 * @param currentState     the real, measured current state
	 * @param now              the current slot index
	 * @param deadlineSlot     the slot by which the target must be met
	 * @param targetSetpoint   the concrete value to physically aim for
	 * @param reached          tests whether a final state meets the target
	 * @return {@code latestSafeStart(...) - now}, in slots — the lower, the
	 *             more urgent; negative means already critical
	 */
	public static <M> int slack(StateTransitionFunction<M> stateTransitionFunction, M highestPowerMode,
			double currentState, int now, int deadlineSlot, double targetSetpoint, DoublePredicate reached) {
		return latestSafeStart(stateTransitionFunction, highestPowerMode, currentState, deadlineSlot, targetSetpoint,
				reached) - now;
	}

	private static <M> boolean reachesTargetFrom(StateTransitionFunction<M> stateTransitionFunction,
			M highestPowerMode, double currentState, int start, int deadlineSlot, double targetSetpoint,
			DoublePredicate reached) {
		var state = currentState;
		for (var slot = start; slot <= deadlineSlot; slot++) {
			// No allocator budget cap while testing worst-case urgency —
			// physical capability only.
			var result = stateTransitionFunction.apply(state, highestPowerMode, slot, Double.MAX_VALUE,
					targetSetpoint);
			state = result.nextState();
		}
		return reached.test(state);
	}
}
