package io.openems.edge.scheduling.algorithm;

/**
 * One slot's external, price/capacity context — the "array of time slots
 * with energy cost and available power for each slot" input to Create Plan.
 *
 * @param energyPriceEurPerKwh the forecasted/known energy price for this slot
 * @param availablePowerW      the power budget assigned to this resource for
 *                                 this slot (by the DP Controller's
 *                                 Tier-1/Tier-2 arbitration), in watts
 */
public record TimeSlot(double energyPriceEurPerKwh, double availablePowerW) {
}
