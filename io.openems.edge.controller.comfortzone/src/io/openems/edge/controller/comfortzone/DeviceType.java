package io.openems.edge.controller.comfortzone;

/**
 * How the device's power can be controlled — it decides the switching costs.
 */
public enum DeviceType {
	/** Inverter compressor, heat pump, water-based heating: switching is free. */
	MODULATING,
	/**
	 * Fixed-speed compressor: a start from OFF costs the Start Cost, and the
	 * minimum on / off times hold.
	 */
	ON_OFF;
}
