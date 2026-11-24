package io.openems.edge.predictor.weather;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.weather.api.QuarterlyWeatherSnapshot;

class PredictorWeatherImplTest {

	private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
	private static final Instant START = Instant.parse("2026-11-23T21:00:00Z");

	private static QuarterlyWeatherSnapshot at(String localTime, double temperature) {
		return new QuarterlyWeatherSnapshot(ZonedDateTime.parse("2026-11-23T" + localTime + "+01:00[Europe/Amsterdam]"),
				0, 0, 0, 0, temperature, 0, 0);
	}

	@Test
	void temperaturesArePlacedInTheirQuarterAsDeciDegrees() {
		var forecast = List.of(//
				at("22:30", -1.94), //
				at("22:00", -2.0), //
				at("21:45", 3.0), // before the start: ignored
				at("23:00", 5.0)); // past the end: ignored

		assertArrayEquals(new Integer[] { -20, null, -19, null }, PredictorWeatherImpl.deciDegrees(START, forecast, 4));
	}

	@Test
	void missingOrInvalidTemperaturesAreNull() {
		var forecast = List.of(at("22:00", Double.NaN), //
				new QuarterlyWeatherSnapshot(ZonedDateTime.of(2026, 11, 23, 22, 20, 0, 0, ZONE), 0, 0, 0, 0, 7.0, 0,
						0)); // not on a quarter

		assertArrayEquals(new Integer[] { null, null }, PredictorWeatherImpl.deciDegrees(START, forecast, 2));
	}
}
