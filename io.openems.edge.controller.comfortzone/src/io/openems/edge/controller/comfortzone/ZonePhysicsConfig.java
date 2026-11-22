package io.openems.edge.controller.comfortzone;

import io.openems.edge.hvac.api.HvacMode;

/**
 * The physical parameters of a single-node Comfort Zone (no heating slab: an
 * air conditioner or radiators).
 *
 * @param hvacMode          heat or cool
 * @param capacityKwhPerK   the Thermal Capacity {@code C_r}, in kWh/°C
 * @param transmissionWPerK the TransmissionFactor {@code H_ro}, in W/°C
 * @param nominalPowerW     the nominal electrical power {@code P_nominal}
 *                              (OFF, ON, FORCED), in W
 * @param maxPowerW         the maximum electrical power {@code P_max} (BOOST,
 *                              FORCED_BOOST), in W
 * @param minC              the physical limit {@code T_min}: frost
 *                              protection when heating, in °C
 * @param maxC              the physical limit {@code T_max}: overheating
 *                              protection when cooling, in °C
 * @param efficiency        the COP / EER
 * @param outdoorC          the outdoor temperature forecast, one value per
 *                              slot, in °C
 */
public record ZonePhysicsConfig(HvacMode hvacMode, double capacityKwhPerK, double transmissionWPerK,
		double nominalPowerW, double maxPowerW, double minC, double maxC, Efficiency efficiency,
		double[] outdoorC) {

	/**
	 * Whether the zone is heated (else cooled).
	 *
	 * @return true when heating
	 */
	public boolean heating() {
		return this.hvacMode != HvacMode.COOL;
	}
}
