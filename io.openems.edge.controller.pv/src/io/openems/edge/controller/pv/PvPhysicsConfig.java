package io.openems.edge.controller.pv;

/**
 * Plain-Java snapshot of everything {@link PvPhysics} needs for one planning
 * cycle — decoupled from the OSGi {@code Config} annotation interface so the
 * physics stays unit-testable without OSGi, and so the forecast (read live
 * from the predictor each planning cycle, not configured) has a home.
 *
 * @param inverterRatedW       the inverter's AC rated output, in W
 * @param pFracMin             lowest controllable output, as a fraction of
 *                                 {@code inverterRatedW} (0-1)
 * @param pFracMax             highest controllable output, as a fraction of
 *                                 {@code inverterRatedW} (0-1)
 * @param curtailmentSupported whether the inverter accepts a power limit at
 *                                 all; if not, it always produces what the
 *                                 sun allows
 * @param forecastW            one entry per slot: the forecast PV power, in
 *                                 W, as positive magnitudes
 */
public record PvPhysicsConfig(//
		double inverterRatedW, //
		double pFracMin, double pFracMax, //
		boolean curtailmentSupported, //
		double[] forecastW) {

	/**
	 * The highest controllable output, {@code P_max}.
	 *
	 * @return {@code pFracMax · inverterRatedW}, in W
	 */
	public double pMaxW() {
		return this.pFracMax * this.inverterRatedW;
	}

	/**
	 * The lowest controllable output, {@code P_min}.
	 *
	 * @return {@code pFracMin · inverterRatedW}, in W
	 */
	public double pMinW() {
		return this.pFracMin * this.inverterRatedW;
	}

	/**
	 * What the sun allows in a slot, {@code P_avail}.
	 *
	 * @param slot the slot index
	 * @return {@code min(P_forecast[slot], inverterRatedW)}, in W; 0 beyond
	 *             the end of the forecast
	 */
	public double availableW(int slot) {
		if (slot < 0 || slot >= this.forecastW.length) {
			return 0;
		}
		return Math.max(0, Math.min(this.forecastW[slot], this.inverterRatedW));
	}
}
