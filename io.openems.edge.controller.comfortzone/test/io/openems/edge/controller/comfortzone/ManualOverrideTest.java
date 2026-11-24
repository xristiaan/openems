package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.comfortzone.ManualOverride.Readback;
import io.openems.edge.controller.comfortzone.ManualOverride.Sent;
import io.openems.edge.hvac.api.HvacMode;

class ManualOverrideTest {

	private static final Instant T0 = Instant.parse("2026-11-23T12:00:00Z");
	private static final Instant NEXT_STEP = Instant.parse("2026-11-23T20:00:00Z");
	private static final Sent HEAT_19 = new Sent(true, HvacMode.HEAT, 190);

	// 0.5 °C tolerance, 60 s settling, no maximum
	private static ManualOverride override() {
		return new ManualOverride(5, Duration.ofSeconds(60), null);
	}

	private static Readback device(boolean on, int setpointDeci) {
		return new Readback(on, on ? HvacMode.HEAT : HvacMode.OFF, setpointDeci);
	}

	private static Instant at(int seconds) {
		return T0.plusSeconds(seconds);
	}

	@Test
	void aChangeAfterTheDeviceShowedTheCommandIsFollowedUntilTheNextStep() {
		var override = override();
		assertFalse(override.hold(at(0), HEAT_19, device(true, 160), NEXT_STEP), "not yet confirmed");
		assertFalse(override.hold(at(5), HEAT_19, device(true, 190), NEXT_STEP), "the device shows it");
		assertFalse(override.hold(at(30), HEAT_19, device(true, 220), NEXT_STEP), "still settling");

		assertTrue(override.hold(at(60), HEAT_19, device(true, 220), NEXT_STEP));
		assertTrue(override.active());
		assertEquals(NEXT_STEP, override.until());
		assertEquals(22.0, override.targetC(5));

		assertTrue(override.hold(NEXT_STEP.minusSeconds(1), HEAT_19, device(true, 220), NEXT_STEP));
		assertFalse(override.hold(NEXT_STEP, HEAT_19, device(true, 220), NEXT_STEP), "the next step ends it");
		assertFalse(override.active());
	}

	@Test
	void roundingWithinTheToleranceIsNoChange() {
		var override = override();
		override.hold(at(0), new Sent(true, HvacMode.HEAT, 193), device(true, 195), NEXT_STEP);
		assertFalse(override.hold(at(120), new Sent(true, HvacMode.HEAT, 193), device(true, 195), NEXT_STEP));
		assertFalse(override.active());
	}

	@Test
	void aCommandTheDeviceNeverShowedIsNoManualChange() {
		var override = override();
		override.hold(at(0), HEAT_19, device(true, 160), NEXT_STEP);
		assertFalse(override.hold(at(600), HEAT_19, device(true, 160), NEXT_STEP), "failed or not taken");
		assertFalse(override.hold(at(600), HEAT_19, new Readback(null, null, null), NEXT_STEP), "unknown");
	}

	@Test
	void switchingOffByHandIsFollowedWithTheMinimumAsTarget() {
		var override = override();
		override.hold(at(0), HEAT_19, device(true, 190), NEXT_STEP);
		assertTrue(override.hold(at(100), HEAT_19, device(false, 190), NEXT_STEP));
		assertEquals(5.0, override.targetC(5));
	}

	@Test
	void reversingTheChangeEndsTheOverride() {
		var override = override();
		override.hold(at(0), HEAT_19, device(true, 190), NEXT_STEP);
		assertTrue(override.hold(at(100), HEAT_19, device(true, 220), NEXT_STEP));
		assertFalse(override.hold(at(200), HEAT_19, device(true, 190), NEXT_STEP));
		assertFalse(override.active());
		assertNull(override.targetC(5));
	}

	@Test
	void theMaximumDurationEndsItEarlier() {
		var override = new ManualOverride(5, Duration.ofSeconds(60), Duration.ofHours(2));
		override.hold(at(0), HEAT_19, device(true, 190), NEXT_STEP);
		override.hold(at(100), HEAT_19, device(true, 220), NEXT_STEP);
		assertEquals(at(100).plus(Duration.ofHours(2)), override.until());

		var withoutSchedule = override();
		withoutSchedule.hold(at(0), HEAT_19, device(true, 190), null);
		withoutSchedule.hold(at(100), HEAT_19, device(true, 220), null);
		assertTrue(withoutSchedule.active());
		assertNull(withoutSchedule.until(), "no schedule, no maximum: until reversed");
	}

	@Test
	void aNewPlannedCommandIsNotAManualChange() {
		var override = override();
		override.hold(at(0), HEAT_19, device(true, 190), NEXT_STEP);
		var heat21 = new Sent(true, HvacMode.HEAT, 210);
		assertFalse(override.hold(at(900), heat21, device(true, 190), NEXT_STEP));
		assertFalse(override.hold(at(1000), heat21, device(true, 190), NEXT_STEP), "the device hasn't caught up");
		assertFalse(override.active());
	}
}
