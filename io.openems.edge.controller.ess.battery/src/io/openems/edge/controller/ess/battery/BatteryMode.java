package io.openems.edge.controller.ess.battery;

/**
 * The Battery Controller's own Operation Modes — the SG-Ready-compatible
 * core (Off/On/Boost/Forced) plus Forced Boost, per the Set-Point
 * Translation Mapping in the Battery Controller specification.
 */
public enum BatteryMode {
	/** Fully disable operation; no intentional energy transfer. */
	OFF,
	/** Normal operation within the NORMAL SoC band. */
	ON,
	/** Like ON, but within the wider SAFE SoC band. */
	BOOST,
	/** Forced override within the NORMAL SoC band. */
	FORCED,
	/** Forced override within the wider SAFE SoC band. */
	FORCED_BOOST;
}
