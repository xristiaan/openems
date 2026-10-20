package io.openems.edge.scheduling.algorithm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeSlotTest {

	private static final double BUY = 0.25;
	private static final double SELL = 0.05;

	/**
	 * Energy of a power held for the whole 15-minute slot.
	 *
	 * @param powerW the power, in W
	 * @return the energy, in Wh
	 */
	private static double wh(double powerW) {
		return powerW * TimeSlot.DURATION_HOURS;
	}

	@Test
	void withOnePriceAndNothingElseOnSiteEnergyCostsPriceTimesEnergy() {
		var slot = new TimeSlot(0.20, 5_000);
		assertEquals(0.20 * 1.0, slot.energyCostEur(1_000), 1e-12);
		assertEquals(-0.20 * 1.0, slot.energyCostEur(-1_000), 1e-12);
	}

	@Test
	void consumingFromASurplusCostsOnlyTheSellPrice() {
		// The site exports 1 kW without the EV; the EV takes exactly that.
		var slot = new TimeSlot(BUY, SELL, -1_000, 11_000);
		assertEquals(SELL * 0.25, slot.energyCostEur(wh(1_000)), 1e-12);
	}

	@Test
	void consumingBeyondTheSurplusPaysTheBuyPriceForTheRest() {
		// 1 kW surplus, 3 kW consumed: 1 kW at the sell price, 2 kW bought.
		var slot = new TimeSlot(BUY, SELL, -1_000, 11_000);
		assertEquals((SELL * 1 + BUY * 2) * 0.25, slot.energyCostEur(wh(3_000)), 1e-12);
	}

	@Test
	void producingForTheSitesOwnConsumptionSavesTheBuyPrice() {
		// The rest of the site imports 2 kW; PV produces 2 kW.
		var slot = new TimeSlot(BUY, SELL, 2_000, -4_000);
		assertEquals(-BUY * 2 * 0.25, slot.energyCostEur(wh(-2_000)), 1e-12);
	}

	@Test
	void producingBeyondTheSitesConsumptionEarnsTheSellPriceForTheRest() {
		// 2 kW imported without PV, 3 kW produced: 2 kW saved at buy, 1 kW sold.
		var slot = new TimeSlot(BUY, SELL, 2_000, -4_000);
		assertEquals(-(BUY * 2 + SELL * 1) * 0.25, slot.energyCostEur(wh(-3_000)), 1e-12);
	}

	@Test
	void exportingAtANegativeSellPriceCostsMoney() {
		var slot = new TimeSlot(0.10, -0.05, 0, -4_000);
		assertEquals(0.05 * 1 * 0.25, slot.energyCostEur(wh(-1_000)), 1e-12);
	}
}
