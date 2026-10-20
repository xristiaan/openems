package io.openems.edge.simulator.replay.tariff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

import org.junit.jupiter.api.Test;

import io.openems.edge.simulator.replay.ReplayData;
import io.openems.edge.simulator.replay.ReplayData.Slot;

class TimeOfUseTariffReplayTest {

	private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
	private static final DoubleBinaryOperator SPOT = (x, y) -> x;
	private static final Double[] NO_SURCHARGE = new Double[0];

	private static ReplayData flatDays(double... pricesEurPerKwh) {
		var days = new ArrayList<List<Slot>>();
		for (var price : pricesEurPerKwh) {
			var day = new ArrayList<Slot>();
			for (var i = 0; i < 96; i++) {
				day.add(new Slot(price, null, null));
			}
			days.add(day);
		}
		return new ReplayData(AMSTERDAM, LocalDate.parse("2026-10-19"), days);
	}

	@Test
	void beforeThePublicationHourOnlyTodaysPricesAreKnown() {
		// 12:00 local: 48 quarters left until midnight.
		var prices = TimeOfUseTariffReplayImpl.pricesEurPerMwh(flatDays(0.10, 0.20),
				Instant.parse("2026-10-19T10:00:00Z"), 13, SPOT, NO_SURCHARGE);

		assertEquals(48, prices.length);
		assertEquals(100.0, prices[0], 1e-9);
	}

	@Test
	void fromThePublicationHourTomorrowsPricesAreKnownToo() {
		// 13:00 local: 44 quarters today + 96 tomorrow.
		var prices = TimeOfUseTariffReplayImpl.pricesEurPerMwh(flatDays(0.10, 0.20),
				Instant.parse("2026-10-19T11:00:00Z"), 13, SPOT, NO_SURCHARGE);

		assertEquals(44 + 96, prices.length);
		assertEquals(200.0, prices[44], 1e-9);
	}

	@Test
	void theFormulaGetsTheSpotPriceAndTheSurchargeOfEachSlot() {
		var buy = PriceFormula.parse("(x + y + 130) * 1.21");
		var prices = TimeOfUseTariffReplayImpl.pricesEurPerMwh(flatDays(-0.05),
				Instant.parse("2026-10-19T10:00:00Z"), 13, buy::apply, new Double[] { 10.0, null });

		assertEquals((-50 + 10 + 130) * 1.21, prices[0], 1e-9);
		assertEquals((-50 + 0 + 130) * 1.21, prices[1], 1e-9, "no surcharge: y = 0");
		assertEquals((-50 + 0 + 130) * 1.21, prices[2], 1e-9, "beyond the schedule: y = 0");
	}

	@Test
	void aSellFormulaCanTakeAShareOfTheSpotPriceMinusAFee() {
		var sell = PriceFormula.parse("0.5 * x - 20");
		var prices = TimeOfUseTariffReplayImpl.pricesEurPerMwh(flatDays(0.10), Instant.parse("2026-10-19T10:00:00Z"),
				13, sell::apply, NO_SURCHARGE);

		assertEquals(0.5 * 100 - 20, prices[0], 1e-9);
	}

	@Test
	void anInvalidExpressionIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> PriceFormula.parse("x + z"));
		assertThrows(IllegalArgumentException.class, () -> PriceFormula.parse("(x"));
	}

	@Test
	void pricesStopAtTheFirstMissingOne() {
		var day = new ArrayList<Slot>();
		for (var i = 0; i < 96; i++) {
			day.add(new Slot(i < 50 ? 0.1 : null, null, null));
		}
		var replay = new ReplayData(AMSTERDAM, LocalDate.parse("2026-10-19"), List.of(day));

		// 12:00 local = quarter 48: quarters 48 and 49 have prices.
		var prices = TimeOfUseTariffReplayImpl.pricesEurPerMwh(replay, Instant.parse("2026-10-19T10:00:00Z"), 13,
				SPOT, NO_SURCHARGE);

		assertEquals(2, prices.length);
	}
}
