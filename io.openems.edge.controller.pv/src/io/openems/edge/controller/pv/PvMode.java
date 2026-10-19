package io.openems.edge.controller.pv;

/**
 * The PV Controller's Operation Modes — the four Smart Grid modes, of which
 * effectively three differ for PV (BOOST is an alias of ON), per the
 * Set-Point Translation Mapping in the PV Controller specification.
 */
public enum PvMode {
	/** Output as close to zero as the inverter allows ({@code P_min}). */
	OFF,
	/** Maximum output, or capped at the optional set-point if one is given. */
	ON,
	/** Alias of ON — PV already produces at the maximum available. */
	BOOST,
	/** Output capped at the set-point (required; falls back to ON without one). */
	FORCED;
}
