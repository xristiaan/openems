package io.openems.edge.controller.comfortzone;

/**
 * The Comfort Zone Controller's Operation Modes — the same SG modes as every
 * other Plan-able Controller. The mode says how to operate, the target what to
 * aim for (see the Set-Point Translation Mapping in the specification).
 */
public enum ComfortZoneMode {
	/** No heating (cooling); only frost (overheating) protection. */
	OFF,
	/** Follow the schedule's target at nominal power. */
	ON,
	/** Store heat (coolness): target ± BoostOffset at maximum power. */
	BOOST,
	/** Follow the external set point at nominal power; like ON without one. */
	FORCED,
	/** Follow the external set point at maximum power; like BOOST without one. */
	FORCED_BOOST;

	/**
	 * Whether this mode may use the device's maximum power instead of its
	 * nominal power.
	 *
	 * @return true for BOOST and FORCED_BOOST
	 */
	public boolean isBoost() {
		return this == BOOST || this == FORCED_BOOST;
	}
}
