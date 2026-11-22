package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ZoneScheduleTest {

	private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

	@Test
	void eachEntryHoldsUntilTheNext() {
		var schedule = ZoneSchedule.parse("22:00=17, 07:00=20.5", 20, AMSTERDAM);

		// CET: UTC+1
		assertEquals(20.5, schedule.targetAt(Instant.parse("2026-11-23T08:00:00Z")));
		assertEquals(17, schedule.targetAt(Instant.parse("2026-11-23T21:30:00Z")));
		assertEquals(17, schedule.targetAt(Instant.parse("2026-11-23T03:00:00Z")), "before 07:00: last night's");
	}

	@Test
	void withoutEntriesTheDefaultHolds() {
		assertEquals(20, ZoneSchedule.parse("", 20, AMSTERDAM).targetAt(Instant.parse("2026-11-23T03:00:00Z")));
	}

	@Test
	void targetsAreGivenPerSlot() {
		var schedule = ZoneSchedule.parse("07:00=20, 07:30=21", 18, AMSTERDAM);

		var targets = schedule.targets(Instant.parse("2026-11-23T06:00:00Z"), 3);

		assertEquals(20, targets[0]);
		assertEquals(20, targets[1]);
		assertEquals(21, targets[2]);
	}

	@Test
	void aBadEntryIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> ZoneSchedule.parse("7=20", 20, AMSTERDAM));
	}
}
