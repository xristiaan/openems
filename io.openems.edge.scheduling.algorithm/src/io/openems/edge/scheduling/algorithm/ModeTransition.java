package io.openems.edge.scheduling.algorithm;

/**
 * One cell of a {@link ModeSwitchingMatrix}: "shows if a transition is
 * possible and what the cost" is.
 *
 * @param allowed whether this transition may be taken at all
 * @param cost    the switching cost if taken (0 for a free transition,
 *                    including staying in the same mode)
 */
public record ModeTransition(boolean allowed, double cost) {

	/** A free, always-allowed transition (e.g. staying in the same mode). */
	public static final ModeTransition FREE = new ModeTransition(true, 0);

	/** A transition that is never allowed. */
	public static final ModeTransition FORBIDDEN = new ModeTransition(false, 0);
}
