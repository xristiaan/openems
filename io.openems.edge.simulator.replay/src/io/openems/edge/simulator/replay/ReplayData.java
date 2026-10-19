package io.openems.edge.simulator.replay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Recorded test days (the {@code days/YYYY-MM-DD.json} files written by
 * {@code test-data/fetch.mjs}), replayed against the real clock.
 *
 * <p>
 * The first test day is played on the local day the replay starts, the second
 * one the day after, and so on, looping. Within a day, slots follow the real
 * local time of day: at 10:00 the 10:00 slot of the test day plays. On a day
 * whose length differs from the test day's (DST), the last slot is held or the
 * tail is skipped.
 *
 * <p>
 * Plain Java, no OpenEMS dependencies, so it is unit-testable on its own.
 */
public class ReplayData {

	public static final Duration SLOT = Duration.ofMinutes(15);

	/**
	 * One 15-minute slot of a test day. Fields are null where the test day has
	 * no data.
	 */
	public record Slot(Double priceEurPerKwh, Integer pvForecastW, Integer pvActualW) {

		/** The PV production to simulate: the actual, else the forecast, else 0. */
		public int pvProducedW() {
			return this.pvActualW != null ? this.pvActualW : this.pvForecastW != null ? this.pvForecastW : 0;
		}

		/** The PV forecast to give the planner: the forecast, else the actual, else 0. */
		public int pvPredictedW() {
			return this.pvForecastW != null ? this.pvForecastW : this.pvActualW != null ? this.pvActualW : 0;
		}
	}

	private final ZoneId zone;
	private final LocalDate startDate;
	private final List<List<Slot>> days;

	/**
	 * @param zone      the local time zone of the test days
	 * @param startDate the real local date on which the first test day plays
	 * @param days      the test days in play order, each a list of slots
	 */
	public ReplayData(ZoneId zone, LocalDate startDate, List<List<Slot>> days) {
		if (days.isEmpty() || days.stream().anyMatch(List::isEmpty)) {
			throw new IllegalArgumentException("at least one non-empty test day is required");
		}
		this.zone = zone;
		this.startDate = startDate;
		this.days = List.copyOf(days);
	}

	/**
	 * Loads test days from a directory.
	 *
	 * @param directory the {@code days/} directory
	 * @param dates     the dates to play, in order (e.g. "2026-10-20"); empty
	 *                  plays every file in the directory, oldest first
	 * @param zone      the local time zone
	 * @param startDate the real local date on which the first test day plays
	 * @return the replay
	 * @throws IOException if a file can't be read
	 */
	public static ReplayData load(Path directory, String[] dates, ZoneId zone, LocalDate startDate)
			throws IOException {
		var files = new ArrayList<Path>();
		if (dates.length == 0) {
			try (Stream<Path> list = Files.list(directory)) {
				list.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json")) //
						.sorted() //
						.forEach(files::add);
			}
		} else {
			for (var date : dates) {
				files.add(directory.resolve(date.trim() + ".json"));
			}
		}
		if (files.isEmpty()) {
			throw new IOException("no test days found in " + directory);
		}

		var days = new ArrayList<List<Slot>>();
		for (var file : files) {
			days.add(parseDay(JsonParser.parseString(Files.readString(file)).getAsJsonObject()));
		}
		return new ReplayData(zone, startDate, days);
	}

	static List<Slot> parseDay(JsonObject day) {
		var slots = new ArrayList<Slot>();
		for (var element : day.getAsJsonArray("slots")) {
			var slot = element.getAsJsonObject();
			slots.add(new Slot(//
					doubleOrNull(slot.get("priceEurPerKwh")), //
					intOrNull(slot.get("pvForecastW")), //
					intOrNull(slot.get("pvActualW"))));
		}
		return slots;
	}

	private static Double doubleOrNull(JsonElement e) {
		return e == null || e.isJsonNull() ? null : e.getAsDouble();
	}

	private static Integer intOrNull(JsonElement e) {
		return e == null || e.isJsonNull() ? null : e.getAsInt();
	}

	/**
	 * The slot playing at the given instant.
	 *
	 * @param time the instant
	 * @return the slot
	 */
	public Slot slotAt(Instant time) {
		var date = time.atZone(this.zone).toLocalDate();
		var day = this.dayFor(date);
		var index = (int) Duration.between(midnight(date), time).dividedBy(SLOT);
		return day.get(Math.min(index, day.size() - 1));
	}

	/**
	 * Consecutive slots starting with the one playing at {@code from}.
	 *
	 * @param from  the first slot's instant
	 * @param count the number of slots
	 * @return the slots
	 */
	public List<Slot> slotsFrom(Instant from, int count) {
		var start = slotStart(from);
		var slots = new ArrayList<Slot>(count);
		for (var i = 0; i < count; i++) {
			slots.add(this.slotAt(start.plus(SLOT.multipliedBy(i))));
		}
		return slots;
	}

	/**
	 * The start of the slot containing the instant: rounded down to 15 minutes.
	 *
	 * @param time the instant
	 * @return the slot start
	 */
	public static Instant slotStart(Instant time) {
		var seconds = time.getEpochSecond();
		return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, SLOT.toSeconds()));
	}

	/**
	 * The start of the next local day after the given instant.
	 *
	 * @param time the instant
	 * @return the next local midnight
	 */
	public Instant nextMidnight(Instant time) {
		return this.midnight(time.atZone(this.zone).toLocalDate().plusDays(1));
	}

	/**
	 * The local midnight starting the given date.
	 *
	 * @param date the local date
	 * @return the instant
	 */
	public Instant midnight(LocalDate date) {
		return date.atStartOfDay(this.zone).toInstant();
	}

	public ZoneId zone() {
		return this.zone;
	}

	private List<Slot> dayFor(LocalDate date) {
		var n = this.days.size();
		var k = (int) Math.floorMod(ChronoUnit.DAYS.between(this.startDate, date), (long) n);
		return this.days.get(k);
	}
}
