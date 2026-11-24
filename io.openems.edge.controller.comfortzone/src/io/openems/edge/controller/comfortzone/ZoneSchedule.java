package io.openems.edge.controller.comfortzone;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.openems.edge.scheduling.algorithm.TimeSlot;

/**
 * The zone's schedule (the Target Source): target temperatures from given
 * times of day, the same every day — e.g. {@code "07:00=20.5, 22:00=17"}:
 * 20.5 °C from 07:00, 17 °C from 22:00 until 07:00 the next day. Without
 * entries the default target holds all day.
 *
 * <p>
 * v1 takes the schedule from the configuration. Later it comes from the
 * thermostat or the Home Automation Platform (see the specification's Target
 * Source).
 */
public final class ZoneSchedule {

	private record Entry(LocalTime from, double targetC) {
	}

	private final List<Entry> entries;
	private final double defaultC;
	private final ZoneId zone;

	private ZoneSchedule(List<Entry> entries, double defaultC, ZoneId zone) {
		this.entries = entries;
		this.defaultC = defaultC;
		this.zone = zone;
	}

	/**
	 * Parses a schedule.
	 *
	 * @param schedule e.g. {@code "07:00=20.5, 22:00=17"}; empty for the default
	 *                     target all day
	 * @param defaultC the default target, in °C
	 * @param zone     the local time zone of the times
	 * @return the schedule
	 * @throws IllegalArgumentException if an entry can't be parsed
	 */
	public static ZoneSchedule parse(String schedule, double defaultC, ZoneId zone) {
		var entries = new ArrayList<Entry>();
		if (schedule != null) {
			for (var item : schedule.split(",")) {
				if (item.isBlank()) {
					continue;
				}
				var parts = item.trim().split("=");
				if (parts.length != 2) {
					throw new IllegalArgumentException("schedule entry must be HH:MM=°C, not [" + item.trim() + "]");
				}
				try {
					entries.add(new Entry(LocalTime.parse(parts[0].trim()), Double.parseDouble(parts[1].trim())));
				} catch (DateTimeParseException | NumberFormatException e) {
					throw new IllegalArgumentException("schedule entry must be HH:MM=°C, not [" + item.trim() + "]");
				}
			}
		}
		entries.sort(Comparator.comparing(Entry::from));
		return new ZoneSchedule(List.copyOf(entries), defaultC, zone);
	}

	/**
	 * The target at a given time.
	 *
	 * @param time the instant
	 * @return the target in °C
	 */
	public double targetAt(Instant time) {
		if (this.entries.isEmpty()) {
			return this.defaultC;
		}
		var localTime = time.atZone(this.zone).toLocalTime();
		// Before the first entry of the day, the last entry of the day before holds
		var target = this.entries.get(this.entries.size() - 1).targetC();
		for (var entry : this.entries) {
			if (!localTime.isBefore(entry.from())) {
				target = entry.targetC();
			}
		}
		return target;
	}

	/**
	 * The next step in the schedule: when the next entry starts.
	 *
	 * @param time the instant
	 * @return the first entry start after {@code time}, or null without entries
	 */
	public Instant nextChange(Instant time) {
		if (this.entries.isEmpty()) {
			return null;
		}
		var date = time.atZone(this.zone).toLocalDate();
		for (var day = 0; day <= 1; day++) {
			for (var entry : this.entries) {
				var start = date.plusDays(day).atTime(entry.from()).atZone(this.zone).toInstant();
				if (start.isAfter(time)) {
					return start;
				}
			}
		}
		return null; // not reached: tomorrow's first entry is always after time
	}

	/**
	 * The target per slot.
	 *
	 * @param start     the start of slot 0
	 * @param slotCount the number of slots
	 * @return one target per slot, in °C
	 */
	public double[] targets(Instant start, int slotCount) {
		var out = new double[slotCount];
		var slotSeconds = (long) (TimeSlot.DURATION_HOURS * 3600);
		for (var i = 0; i < slotCount; i++) {
			out[i] = this.targetAt(start.plusSeconds(i * slotSeconds));
		}
		return out;
	}
}
