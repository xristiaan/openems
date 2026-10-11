package io.openems.edge.scheduling.algorithm;

/**
 * The per-slot target this resource is scored against for deviation cost —
 * "a target list of N set points or N min, max intervals for a comfort
 * band," from the Target definition. A point target and a deadline both
 * reduce to this same shape: a point target is {@code min == max}; a
 * deadline is an unconstrained band ({@link #unconstrained()}) everywhere
 * except the deadline slot itself, where it narrows to a point (or near-
 * point) target with a large {@code weightPerUnitPerSlot} — graded, not
 * boolean, so a 79% shortfall costs less than a 45% one.
 *
 * <p>
 * This is deliberately the <em>deviation-cost</em> target, used uniformly
 * for every candidate mode in a slot — separate from whatever target a
 * specific mode's physics aims for (see {@link TargetSetpointResolver}),
 * since a device can be deliberately run off-schedule (e.g. an explicit
 * Set-Point override) without that being scored as a "deviation."
 *
 * @param min                   lower bound of the acceptable band
 * @param max                   upper bound of the acceptable band
 * @param weightPerUnitPerSlot  cost per unit of deviation per slot outside
 *                                  [min, max] — the same mechanism serves
 *                                  both the Power Calculation Weights'
 *                                  "deviation cost" and "missed deadline
 *                                  cost," differing only in which value is
 *                                  used for a given slot
 */
public record SlotTarget(double min, double max, double weightPerUnitPerSlot) {

	/**
	 * A point target: the state should be exactly {@code value}.
	 *
	 * @param value               the target value
	 * @param weightPerUnitPerSlot cost per unit of deviation per slot
	 * @return the point target
	 */
	public static SlotTarget point(double value, double weightPerUnitPerSlot) {
		return new SlotTarget(value, value, weightPerUnitPerSlot);
	}

	/**
	 * No constraint at all this slot — the "unconstrained until the deadline
	 * slot" part of a deadline-type resource's target list.
	 *
	 * @return the unconstrained target
	 */
	public static SlotTarget unconstrained() {
		return new SlotTarget(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0);
	}

	/**
	 * How far outside [min, max] the given value is (0 if inside).
	 *
	 * @param value the value to score, typically avg(startState, endState)
	 * @return the deviation, always &gt;= 0
	 */
	public double deviationOf(double value) {
		if (value < this.min) {
			return this.min - value;
		}
		if (value > this.max) {
			return value - this.max;
		}
		return 0;
	}

	/**
	 * Deviation cost for this slot: {@link #deviationOf(double)} times the
	 * configured weight.
	 *
	 * @param value the value to score, typically avg(startState, endState)
	 * @return the cost
	 */
	public double costOf(double value) {
		return this.deviationOf(value) * this.weightPerUnitPerSlot;
	}
}
