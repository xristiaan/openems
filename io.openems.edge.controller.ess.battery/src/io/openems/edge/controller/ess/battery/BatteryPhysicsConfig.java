package io.openems.edge.controller.ess.battery;

/**
 * Plain-Java snapshot of everything {@link BatteryPhysics} needs — decoupled
 * from the OSGi {@code Config} annotation interface so the physics itself
 * stays unit-testable without OSGi, and so {@code nominalCapacityWh} (read
 * live from the real ESS each planning cycle, not configured) has a home.
 *
 * @param nominalCapacityWh          the battery's capacity, in Wh
 * @param socMinNormal               NORMAL band lower bound (SoC fraction)
 * @param socMaxNormal               NORMAL band upper bound (SoC fraction)
 * @param socMinSafe                 SAFE band lower bound (SoC fraction)
 * @param socMaxSafe                 SAFE band upper bound (SoC fraction)
 * @param cRateOnCharge              ON mode's charge C-rate
 * @param cRateOnDischarge           ON mode's discharge C-rate
 * @param cRateBoostCharge           BOOST mode's charge C-rate
 * @param cRateBoostDischarge        BOOST mode's discharge C-rate
 * @param cRateForcedCharge          FORCED mode's charge C-rate
 * @param cRateForcedDischarge       FORCED mode's discharge C-rate
 * @param cRateForcedBoostCharge     FORCED_BOOST mode's charge C-rate
 * @param cRateForcedBoostDischarge  FORCED_BOOST mode's discharge C-rate
 * @param chargeEfficiency           round-trip charge efficiency (0-1)
 * @param dischargeEfficiency        round-trip discharge efficiency (0-1)
 * @param degradationCostEurPerKwh   c_deg: degradation cost per kWh of
 *                                       throughput
 * @param degradationCostBoostMultiplier c_deg_boost: multiplier applied
 *                                       when the operating C-rate is at or
 *                                       above the boost threshold (0.5C)
 */
public record BatteryPhysicsConfig(//
		double nominalCapacityWh, //
		double socMinNormal, double socMaxNormal, //
		double socMinSafe, double socMaxSafe, //
		double cRateOnCharge, double cRateOnDischarge, //
		double cRateBoostCharge, double cRateBoostDischarge, //
		double cRateForcedCharge, double cRateForcedDischarge, //
		double cRateForcedBoostCharge, double cRateForcedBoostDischarge, //
		double chargeEfficiency, double dischargeEfficiency, //
		double degradationCostEurPerKwh, double degradationCostBoostMultiplier) {
}
