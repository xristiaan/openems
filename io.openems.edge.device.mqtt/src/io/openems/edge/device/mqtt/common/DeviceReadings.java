package io.openems.edge.device.mqtt.common;

import java.time.Duration;
import java.time.Instant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The latest readings of a device behind a platform bridge, from its state
 * topic: a JSON object such as {@code {"measure_power": 1200,
 * "measure_battery": 55}} (bridge-homey forwards Homey capability values
 * under their own names). Written on the MQTT thread, read on the OpenEMS
 * Cycle.
 *
 * <p>
 * A message updates only the fields it contains, and every message counts
 * as a sign of life. Once no message has arrived for the stale timeout, the
 * readings are stale: the Component reports a fault and stops presenting
 * old values as current.
 *
 * <p>
 * Plain Java, no OpenEMS dependencies, so it is unit-testable on its own.
 */
public class DeviceReadings {

	private final String powerField;
	private final String socField;
	private final boolean powerInverted;
	private final Duration staleAfter;

	private Double powerW;
	private Double soc;
	private Instant lastMessageAt;

	/**
	 * Creates the readings.
	 *
	 * @param powerField    the JSON field holding power, in W
	 * @param socField      the JSON field holding the SoC in %, or null if
	 *                      the device has none
	 * @param powerInverted true to flip the sign of the power reading
	 * @param staleAfter    the time without a message after which the
	 *                      readings are stale
	 */
	public DeviceReadings(String powerField, String socField, boolean powerInverted, Duration staleAfter) {
		this.powerField = powerField;
		this.socField = socField;
		this.powerInverted = powerInverted;
		this.staleAfter = staleAfter;
	}

	/**
	 * Handles a state message.
	 *
	 * @param json the message payload
	 * @param now  when it arrived
	 * @return false if the payload was not a JSON object
	 */
	public synchronized boolean onMessage(String json, Instant now) {
		JsonObject state;
		try {
			state = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return false;
		}
		this.lastMessageAt = now;
		var power = number(state, this.powerField);
		if (power != null) {
			this.powerW = this.powerInverted ? -power : power;
		}
		var soc = this.socField == null ? null : number(state, this.socField);
		if (soc != null) {
			this.soc = soc;
		}
		return true;
	}

	private static Double number(JsonObject state, String field) {
		var element = state.get(field);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return element.getAsDouble();
	}

	/**
	 * Whether the readings are stale: no message for the stale timeout, or
	 * none at all yet.
	 *
	 * @param now the current time
	 * @return true if stale
	 */
	public synchronized boolean isStale(Instant now) {
		return this.lastMessageAt == null || Duration.between(this.lastMessageAt, now).compareTo(this.staleAfter) > 0;
	}

	/**
	 * The power reading, or null if stale or not yet received.
	 *
	 * @param now the current time
	 * @return the power in W
	 */
	public synchronized Integer powerW(Instant now) {
		return this.isStale(now) || this.powerW == null ? null : (int) Math.round(this.powerW);
	}

	/**
	 * The SoC reading, or null if stale or not yet received.
	 *
	 * @param now the current time
	 * @return the SoC in %, 0-100
	 */
	public synchronized Integer soc(Instant now) {
		return this.isStale(now) || this.soc == null ? null
				: (int) Math.round(Math.max(0, Math.min(100, this.soc)));
	}
}
