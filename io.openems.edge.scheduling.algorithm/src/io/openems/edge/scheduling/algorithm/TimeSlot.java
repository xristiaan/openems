package io.openems.edge.scheduling.algorithm;

/**
 * One slot's external, price/capacity context — the "array of time slots
 * with energy cost and available power for each slot" input to Create Plan.
 *
 * <p>
 * A resource's energy is costed as the change it makes to the site's grid
 * bill ({@link #energyCostEur}): with different buy and sell prices, what a
 * kWh is worth depends on whether the rest of the site imports or exports in
 * that slot. Consuming from a surplus costs only the sell price given up;
 * producing for the site's own consumption saves the buy price.
 *
 * <p>
 * Part of a surplus may be <em>curtailable</em>: production another resource
 * (PV) could curtail instead of exporting. The bill assumes it will be
 * curtailed whenever exporting it doesn't pay — at a sell price ≤ 0, or
 * beyond the export capacity — so taking that energy costs nothing, rather
 * than looking like a saving on a negative sell price.
 *
 * @param buyPriceEurPerKwh      the price of energy bought from the grid in
 *                                   this slot
 * @param sellPriceEurPerKwh     the price paid for energy sold to the grid
 *                                   in this slot
 * @param restOfSiteW            the grid power of the rest of the site in
 *                                   this slot — the base load and every
 *                                   other resource's latest plan (producers
 *                                   at their forecast), without this
 *                                   resource — in watts, import-positive
 * @param curtailableW           how much of the rest of the site's
 *                                   production could be curtailed, in watts
 *                                   (≥ 0)
 * @param exportCapacityW        how much the site may export, in watts (≥ 0;
 *                                   {@link Double#MAX_VALUE} for no limit)
 * @param availablePowerW        the power budget assigned to this resource
 *                                   for this slot (by the DP Controller's
 *                                   Tier-1/Tier-2 arbitration), in watts —
 *                                   consumption-positive; <em>negative</em>
 *                                   for a producing resource (PV), meaning
 *                                   "produce at most this much"
 */
public record TimeSlot(double buyPriceEurPerKwh, double sellPriceEurPerKwh, double restOfSiteW,
		double curtailableW, double exportCapacityW, double availablePowerW) {

	/** The length of a slot, in hours. */
	public static final double DURATION_HOURS = 0.25;

	/**
	 * A slot with nothing curtailable and no export limit.
	 *
	 * @param buyPriceEurPerKwh  see the record
	 * @param sellPriceEurPerKwh see the record
	 * @param restOfSiteW        see the record
	 * @param availablePowerW    see the record
	 */
	public TimeSlot(double buyPriceEurPerKwh, double sellPriceEurPerKwh, double restOfSiteW,
			double availablePowerW) {
		this(buyPriceEurPerKwh, sellPriceEurPerKwh, restOfSiteW, 0, Double.MAX_VALUE, availablePowerW);
	}

	/**
	 * A slot with one price for buying and selling and nothing else on site —
	 * where costing energy at that price is exact.
	 *
	 * @param energyPriceEurPerKwh the energy price
	 * @param availablePowerW      see the record's {@code availablePowerW}
	 */
	public TimeSlot(double energyPriceEurPerKwh, double availablePowerW) {
		this(energyPriceEurPerKwh, energyPriceEurPerKwh, 0, availablePowerW);
	}

	/**
	 * The cost of a resource's energy in this slot: the site's grid bill with
	 * it, minus the bill without it — {@code f(N + E) - f(N)} with {@code N} the
	 * rest of the site's grid energy and {@code f} buy price × import minus sell
	 * price × export, where curtailable production is curtailed rather than
	 * exported when that doesn't pay. Only where the resource moves the site
	 * across zero (from exporting to importing, or back), or into or out of
	 * curtailable export, does each part of its energy get a different price;
	 * otherwise this is simply buy or sell price × energy.
	 *
	 * @param energyUsedWh the resource's energy in this slot, in Wh —
	 *                         consumption-positive, negative for production
	 * @return the cost in Euro; negative is a saving or income
	 */
	public double energyCostEur(double energyUsedWh) {
		var restWh = this.restOfSiteW * DURATION_HOURS;
		return this.gridBillEur(restWh + energyUsedWh) - this.gridBillEur(restWh);
	}

	private double gridBillEur(double gridWh) {
		if (gridWh >= 0) {
			return gridWh / 1000.0 * this.buyPriceEurPerKwh;
		}
		var surplusWh = -gridWh;
		var curtailableWh = Math.min(surplusWh, this.curtailableW * DURATION_HOURS);
		var mustExportWh = surplusWh - curtailableWh;
		// Curtailable production is exported only where it pays, and only as
		// far as the export capacity left over allows.
		var exportedCurtailableWh = this.sellPriceEurPerKwh > 0
				? Math.min(curtailableWh, Math.max(0, this.exportCapacityW * DURATION_HOURS - mustExportWh))
				: 0;
		return -(mustExportWh + exportedCurtailableWh) / 1000.0 * this.sellPriceEurPerKwh;
	}
}
