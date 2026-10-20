package io.openems.edge.scheduling.algorithm;

/**
 * What a resource's state is still worth at the end of the planning
 * horizon — e.g. the energy left in a battery, which will save buying energy
 * after the last slot. Without it, the DP treats whatever is left at the end
 * as worthless, and e.g. a battery never stores energy it can only use after
 * the horizon.
 */
@FunctionalInterface
public interface TerminalValue {

	/** Nothing is worth anything after the last slot. */
	TerminalValue NONE = endState -> 0;

	/**
	 * The value of ending the horizon in this state.
	 *
	 * @param endState the state at the end of the last slot
	 * @return the value in Euro; higher is better
	 */
	double valueEur(double endState);
}
