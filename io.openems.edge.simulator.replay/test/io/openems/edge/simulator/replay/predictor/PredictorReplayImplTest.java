package io.openems.edge.simulator.replay.predictor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.simulator.replay.ReplayData;
import io.openems.edge.simulator.replay.ReplayData.Slot;

class PredictorReplayImplTest {

	private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");

	private static ReplayData replay(Slot... slots) {
		return new ReplayData(AMSTERDAM, LocalDate.parse("2026-11-22"), List.of(List.of(slots)));
	}

	@Test
	void predictsPvScaled() {
		var replay = replay(new Slot(null, 1000, null), new Slot(null, null, 400));

		var predicted = PredictorReplayImpl.predicted(replay, Instant.parse("2026-11-21T23:00:00Z"), 2,
				Quantity.PV_POWER, 2.0);

		assertArrayEquals(new Integer[] { 2000, 800 }, predicted);
	}

	@Test
	void predictsTheOutdoorTemperatureInDeciDegreesUnscaled() {
		var replay = replay(new Slot(null, null, null, -1.4, 0.0), new Slot(null, null, null, null, 2.0),
				new Slot(null, null, null));

		var predicted = PredictorReplayImpl.predicted(replay, Instant.parse("2026-11-21T23:00:00Z"), 3,
				Quantity.OUTDOOR_TEMPERATURE, 2.0);

		assertArrayEquals(new Integer[] { -14, 20, null }, predicted);
	}
}
