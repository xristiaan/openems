package io.openems.edge.scheduling.algorithm;

/**
 * Everything a concrete Plan-able Controller supplies about its own device
 * type, bundled for the generic engine in this package: its mode set, state
 * bucketing, mode-switching rules, and physics. A Plan-able Controller's own
 * {@code Modes}/{@code Physical Limits}/{@code Mode Switching Matrix}/
 * {@code State-Transition Function} attributes all live here.
 *
 * @param <M>                     the controller's own mode type
 * @param modes                   every mode this device type/instance
 *                                    supports
 * @param buckets                 the state discretization for DP table
 *                                    indexing
 * @param switchingMatrix         allowed transitions, their cost, and each
 *                                    mode's minimum dwell time
 * @param stateTransitionFunction the device's physics
 * @param targetSetpointResolver  which target each mode's physics seeks,
 *                                    per slot
 * @param terminalValue           what the state is still worth at the end of
 *                                    the horizon
 */
public record PlanableResourceModel<M extends Enum<M>>(//
		M[] modes, //
		StateBuckets buckets, //
		ModeSwitchingMatrix<M> switchingMatrix, //
		StateTransitionFunction<M> stateTransitionFunction, //
		TargetSetpointResolver<M> targetSetpointResolver, //
		TerminalValue terminalValue) {

	/**
	 * A model whose state is worth nothing after the last slot.
	 *
	 * @param modes                   see the record
	 * @param buckets                 see the record
	 * @param switchingMatrix         see the record
	 * @param stateTransitionFunction see the record
	 * @param targetSetpointResolver  see the record
	 */
	public PlanableResourceModel(M[] modes, StateBuckets buckets, ModeSwitchingMatrix<M> switchingMatrix,
			StateTransitionFunction<M> stateTransitionFunction, TargetSetpointResolver<M> targetSetpointResolver) {
		this(modes, buckets, switchingMatrix, stateTransitionFunction, targetSetpointResolver, TerminalValue.NONE);
	}
}
