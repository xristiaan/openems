package io.openems.edge.scheduling.algorithm;

/**
 * The per-slot target this resource is scored against for deviation cost —
 * "a target list of N set points or N min, max intervals for a comfort
 * band," from the Target definition. A point target and a deadline both
 * reduce to this same shape: a point target is {@code min == max}; a
 * deadline is an unconstrained band ({@link #unconstrained()}) everywhere
 * except the deadline slot itself, where it narrows to a point (or near-
 * point) target with a large weight — graded, not boolean, so a 79%
 * shortfall costs less than a 45% one.
 *
 * <p>
 * The cost of a deviation {@code d} outside the band is
 * {@code linear · d + quadratic · d²}, set separately below {@code min} and
 * above {@code max}: linear for a battery's SoC band, quadratic for a Comfort
 * Zone, where small deviations are cheap and large ones very expensive.
 *
 * <p>
 * This is deliberately the <em>deviation-cost</em> target, used uniformly
 * for every candidate mode in a slot — separate from whatever target a
 * specific mode's physics aims for (see {@link TargetSetpointResolver}),
 * since a device can be deliberately run off-schedule (e.g. an explicit
 * Set-Point override) without that being scored as a "deviation."
 *
 * @param min             lower bound of the acceptable band
 * @param max             upper bound of the acceptable band
 * @param linearBelow     cost per unit of deviation per slot below
 *                            {@code min}
 * @param quadraticBelow  cost per unit² of deviation per slot below
 *                            {@code min}
 * @param linearAbove     cost per unit of deviation per slot above
 *                            {@code max}
 * @param quadraticAbove  cost per unit² of deviation per slot above
 *                            {@code max}
 */
public record SlotTarget(double min, double max, double linearBelow, double quadraticBelow, double linearAbove,
		double quadraticAbove) {

	/**
	 * A band with the same linear cost on both sides — the "deviation cost"
	 * and "missed deadline cost" of the Power Calculation Weights.
	 *
	 * @param min                  lower bound of the acceptable band
	 * @param max                  upper bound of the acceptable band
	 * @param weightPerUnitPerSlot cost per unit of deviation per slot outside
	 *                                 [min, max]
	 */
	public SlotTarget(double min, double max, double weightPerUnitPerSlot) {
		this(min, max, weightPerUnitPerSlot, 0, weightPerUnitPerSlot, 0);
	}

	/**
	 * A point target: the state should be exactly {@code value}.
	 *
	 * @param value                the target value
	 * @param weightPerUnitPerSlot cost per unit of deviation per slot
	 * @return the point target
	 */
	public static SlotTarget point(double value, double weightPerUnitPerSlot) {
		return new SlotTarget(value, value, weightPerUnitPerSlot);
	}

	/**
	 * A point target with a cost of {@code a · d + b · d²} per slot,
	 * separately below and above it.
	 *
	 * @param value  the target value
	 * @param aBelow linear cost per slot below the target
	 * @param bBelow quadratic cost per slot below the target
	 * @param aAbove linear cost per slot above the target
	 * @param bAbove quadratic cost per slot above the target
	 * @return the point target
	 */
	public static SlotTarget quadratic(double value, double aBelow, double bBelow, double aAbove, double bAbove) {
		return new SlotTarget(value, value, aBelow, bBelow, aAbove, bAbove);
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
	 * Deviation cost for this slot: {@code a · d + b · d²} with the weights of
	 * the side the value is on.
	 *
	 * @param value the value to score, typically avg(startState, endState)
	 * @return the cost
	 */
	public double costOf(double value) {
		if (value < this.min) {
			var d = this.min - value;
			return this.linearBelow * d + this.quadraticBelow * d * d;
		}
		if (value > this.max) {
			var d = value - this.max;
			return this.linearAbove * d + this.quadraticAbove * d * d;
		}
		return 0;
	}
}
