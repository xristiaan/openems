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
 * @param buyPriceEurPerKwh  the price of energy bought from the grid in this
 *                               slot
 * @param sellPriceEurPerKwh the price paid for energy sold to the grid in this
 *                               slot
 * @param restOfSiteW        the grid power of the rest of the site in this
 *                               slot — the base load and every other
 *                               resource's latest plan, without this
 *                               resource — in watts, import-positive
 * @param availablePowerW    the power budget assigned to this resource for
 *                               this slot (by the DP Controller's
 *                               Tier-1/Tier-2 arbitration), in watts —
 *                               consumption-positive; <em>negative</em> for
 *                               a producing resource (PV), meaning "produce
 *                               at most this much"
 */
public record TimeSlot(double buyPriceEurPerKwh, double sellPriceEurPerKwh, double restOfSiteW,
		double availablePowerW) {

	/** The length of a slot, in hours. */
	public static final double DURATION_HOURS = 0.25;

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
	 * price × export. Only where the resource moves the site across zero (from
	 * exporting to importing, or back) does each part of its energy get a
	 * different price; otherwise this is simply buy or sell price × energy.
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
		var price = gridWh >= 0 ? this.buyPriceEurPerKwh : this.sellPriceEurPerKwh;
		return gridWh / 1000.0 * price;
	}
}
