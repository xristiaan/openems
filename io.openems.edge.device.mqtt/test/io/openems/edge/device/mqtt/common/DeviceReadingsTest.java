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

	@Test
	void anyFieldCanBeReadByType() {
		var readings = new DeviceReadings("measure_power", null, false, Duration.ofSeconds(120));
		readings.onMessage("{\"measure_temperature\": 19.5, \"onoff\": true, \"thermostat_mode\": \"heat\"}", T0);
		readings.onMessage("{\"target_temperature\": 20}", T0.plusSeconds(5));

		var now = T0.plusSeconds(5);
		assertEquals(19.5, readings.number("measure_temperature", now));
		assertEquals(20.0, readings.number("target_temperature", now));
		assertTrue(readings.bool("onoff", now));
		assertEquals("heat", readings.text("thermostat_mode", now));
		assertNull(readings.number("thermostat_mode", now), "wrong type");
		assertNull(readings.text("onoff", now), "wrong type");
		assertNull(readings.bool("missing", now));
		assertNull(readings.text("thermostat_mode", T0.plusSeconds(200)), "stale");
	}
}
