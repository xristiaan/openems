package io.openems.edge.device.mqtt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class DeviceReadingsTest {

	private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

	private static DeviceReadings battery() {
		// Charge-positive power from the platform, flipped to OpenEMS's
		// discharge-positive; stale after 120s.
		return new DeviceReadings("measure_power", "measure_battery", true, Duration.ofSeconds(120));
	}

	@Test
	void powerAndSocAreReadFromTheirFields() {
		var readings = battery();
		readings.onMessage("{\"measure_power\": 1500, \"measure_battery\": 55.4}", T0);

		assertEquals(-1_500, readings.powerW(T0), "charging 1.5kW is -1500W in OpenEMS");
		assertEquals(55, readings.soc(T0));
	}

	@Test
	void aMessageOnlyUpdatesTheFieldsItContains() {
		var readings = battery();
		readings.onMessage("{\"measure_power\": 1500, \"measure_battery\": 55}", T0);
		readings.onMessage("{\"measure_power\": -800}", T0.plusSeconds(10));

		assertEquals(800, readings.powerW(T0.plusSeconds(10)));
		assertEquals(55, readings.soc(T0.plusSeconds(10)));
	}

	@Test
	void readingsAreStaleBeforeTheFirstMessageAndAfterTheTimeout() {
		var readings = battery();
		assertTrue(readings.isStale(T0));

		readings.onMessage("{\"measure_power\": 100}", T0);
		assertFalse(readings.isStale(T0.plusSeconds(120)));
		assertTrue(readings.isStale(T0.plusSeconds(121)));
		assertNull(readings.powerW(T0.plusSeconds(121)), "no old values presented as current");
	}

	@Test
	void invalidPayloadsAndNonNumericFieldsAreIgnored() {
		var readings = battery();
		assertFalse(readings.onMessage("not json", T0));
		assertTrue(readings.isStale(T0), "garbage is not a sign of life");

		readings.onMessage("{\"measure_power\": \"unknown\", \"measure_battery\": 120}", T0);
		assertNull(readings.powerW(T0));
		assertEquals(100, readings.soc(T0), "clamped to 0-100");
	}
}
