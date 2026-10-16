package io.openems.edge.scheduling.algorithm;

/**
 * "Calculates the end state and energy used within one time slot, given
 * current state, operation mode, target set-point (optional parameter) and
 * power budget." This is the one piece of physics every concrete Plan-able
 * Controller supplies — everything else in this package (the DP table,
 * Slot Cost, Least Slack Time, the forward walk) is generic over it.
 *
 * @param <M> the controller's own mode type (an enum, so transitions can be
 *                looked up in a {@link ModeSwitchingMatrix})
 */
public interface StateTransitionFunction<M> {

	/**
	 * Run {@code mode} for one slot starting from {@code currentState}.
	 *
	 * @param currentState    the state at the start of the slot
	 * @param mode            the mode to run
	 * @param slot            the slot index, for modes whose physics depends
	 *                            on slot-specific exogenous input (e.g. an
	 *                            inferred {@code T_scheduleTarget(slot)})
	 * @param availablePowerW the power budget for this slot, in watts —
	 *                            the mode's own rated power is clipped to
	 *                            this; negative for a producing resource
	 *                            (its production is clipped to the
	 *                            magnitude); pass {@link Double#MAX_VALUE} to mean
	 *                            "no budget cap, physical limit only" (used
	 *                            by {@link LeastSlackTimeCalculator})
	 * @param targetSetpoint  the target this mode's own physics should seek,
	 *                            or {@code null} for a mode with no target
	 *                            (e.g. Off) — resolved per-mode by the
	 *                            caller, see {@link TargetSetpointResolver}
	 * @return the resulting state and energy used
	 */
	StateTransitionResult apply(double currentState, M mode, int slot, double availablePowerW, Double targetSetpoint);
}
