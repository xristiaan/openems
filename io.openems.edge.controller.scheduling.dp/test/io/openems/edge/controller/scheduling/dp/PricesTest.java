package io.openems.edge.controller.scheduling.dp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;

class PricesTest {

	@Test
	void tariffPricesAreConvertedToEuroPerKwhAndTheFallbackFillsTheRest() {
		var tariff = TimeOfUsePrices.from(Instant.parse("2026-10-20T10:00:00Z"), 100.0, -50.0);
		var fallback = new double[] { 0.3, 0.3, 0.3, 0.4 };

		assertArrayEquals(new double[] { 0.1, -0.05, 0.3, 0.4 },
				ControllerSchedulingDpImpl.pricesEurPerKwh(tariff, fallback), 1e-9);
	}

	@Test
	void withoutSellPricesTheBuyPricesAreUsed() {
		var buy = new double[] { 0.25, 0.1 };

		assertArrayEquals(buy, ControllerSchedulingDpImpl.pricesEurPerKwh(TimeOfUsePrices.EMPTY_PRICES, buy), 1e-9);
	}
}
