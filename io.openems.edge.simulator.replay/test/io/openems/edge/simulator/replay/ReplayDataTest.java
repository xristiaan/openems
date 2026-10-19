package io.openems.edge.simulator.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.simulator.replay.ReplayData.Slot;

class ReplayDataTest {

	private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
	private static final LocalDate START = LocalDate.parse("2026-10-19");

	/** A test day whose slot i has price i + offset and PV actual i. */
	private static List<Slot> day(int slots, double offset) {
		var day = new ArrayList<Slot>();
		for (var i = 0; i < slots; i++) {
			day.add(new Slot(i + offset, null, i));
		}
		return day;
	}

	private static ReplayData twoDays() {
		return new ReplayData(AMSTERDAM, START, List.of(day(96, 1000), day(96, 2000)));
	}

	@Test
	void theSlotFollowsTheLocalTimeOfDay() {
		// 10:00 local (CEST, UTC+2) is the 40th quarter of the day.
		var slot = twoDays().slotAt(Instant.parse("2026-10-19T08:00:00Z"));

		assertEquals(1040, slot.priceEurPerKwh());
	}

	@Test
	void eachRealDayPlaysTheNextTestDayAndTheListLoops() {
		var replay = twoDays();

		assertEquals(2040, replay.slotAt(Instant.parse("2026-10-20T08:00:00Z")).priceEurPerKwh());
		assertEquals(1040, replay.slotAt(Instant.parse("2026-10-21T08:00:00Z")).priceEurPerKwh());
	}

	@Test
	void slotsFromRunsAcrossMidnightIntoTheNextTestDay() {
		// 23:45 local, then 00:00 of the next day.
		var slots = twoDays().slotsFrom(Instant.parse("2026-10-19T21:45:00Z"), 2);

		assertEquals(1095, slots.get(0).priceEurPerKwh());
		assertEquals(2000, slots.get(1).priceEurPerKwh());
	}

	@Test
	void aShortTestDayHoldsItsLastSlot() {
		// A 92-slot (spring DST) test day played on a normal 96-slot day.
		var replay = new ReplayData(AMSTERDAM, START, List.of(day(92, 0)));

		assertEquals(91, replay.slotAt(Instant.parse("2026-10-19T21:45:00Z")).priceEurPerKwh());
	}

	@Test
	void producedFallsBackToTheForecastAndPredictedToTheActual() {
		assertEquals(500, new Slot(null, 500, null).pvProducedW());
		assertEquals(700, new Slot(null, null, 700).pvPredictedW());
		assertEquals(0, new Slot(null, null, null).pvProducedW());
		assertEquals(300, new Slot(null, 300, 700).pvPredictedW(), "the forecast wins when both are recorded");
	}

	@Test
	void slotStartRoundsDownToTheQuarter() {
		assertEquals(Instant.parse("2026-10-19T08:15:00Z"), ReplayData.slotStart(Instant.parse("2026-10-19T08:29:59Z")));
	}
}
