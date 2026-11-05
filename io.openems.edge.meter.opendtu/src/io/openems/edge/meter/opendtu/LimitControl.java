package io.openems.edge.meter.opendtu;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import com.google.gson.JsonObject;

import io.openems.common.bridge.http.api.BridgeHttp;
import io.openems.common.bridge.http.api.BridgeHttp.Endpoint;

/**
 * Decides when to send a power limit to the inverter, and builds the request.
 *
 * <p>
 * The limit always goes out as a non-persistent relative limit: a persistent
 * one is written to the inverter's flash on every change. Hoymiles inverters
 * drop a non-persistent limit when they restart (every morning), so a limit
 * the inverter no longer reports is sent again. Commands travel over the DTU's
 * radio link and take seconds to apply, so small changes are held back and
 * commands are spaced out.
 */
final class LimitControl {

	/** Smallest change in percent of the inverter's max power worth sending. */
	static final double MIN_CHANGE = 1.0;
	/** Shortest time between two commands. */
	static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
	/** How long the inverter may report a different limit before it is resent. */
	static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);

	/** OpenDTU's limit_type for a relative, non-persistent limit. */
	private static final int RELATIVE_NON_PERSISTENT = 1;

	private Double lastSent;
	private Instant lastSentAt;
	private boolean inFlight;
	private boolean failed;

	/**
	 * Converts a limit in watt to percent of the inverter's max power.
	 *
	 * @param watt     the limit; null for no limit
	 * @param maxPower the inverter's max power in watt
	 * @return the limit in percent, 0 to 100, rounded to 0.1
	 */
	static double toRelative(Integer watt, int maxPower) {
		if (watt == null) {
			return 100;
		}
		var percent = Math.max(0, Math.min(100, watt * 100.0 / maxPower));
		return Math.round(percent * 10) / 10.0;
	}

	/**
	 * Whether the target limit should be sent now.
	 *
	 * @param target   the wanted limit in percent
	 * @param reported the limit the inverter reports in percent; null if unknown
	 * @param now      the current time
	 * @return true to send the target now
	 */
	boolean shouldSend(double target, Double reported, Instant now) {
		if (this.inFlight) {
			return false;
		}
		if (this.lastSent == null) {
			if (reported != null && Math.abs(reported - target) < MIN_CHANGE) {
				// The inverter already has it: nothing to send, and nothing to wait for
				this.lastSent = target;
				this.lastSentAt = Instant.EPOCH;
				return false;
			}
			return true;
		}
		var since = Duration.between(this.lastSentAt, now);
		if (this.failed || Math.abs(target - this.lastSent) >= MIN_CHANGE) {
			return since.compareTo(MIN_INTERVAL) >= 0;
		}
		if (reported != null && Math.abs(reported - this.lastSent) >= MIN_CHANGE) {
			return since.compareTo(RESEND_INTERVAL) >= 0;
		}
		return false;
	}

	/**
	 * Records that the target is being sent.
	 *
	 * @param target the limit in percent
	 * @param now    the current time
	 */
	void sending(double target, Instant now) {
		this.inFlight = true;
		this.lastSent = target;
		this.lastSentAt = now;
	}

	/**
	 * Records the outcome of the last command.
	 *
	 * @param success whether OpenDTU accepted it
	 */
	void done(boolean success) {
		this.inFlight = false;
		this.failed = !success;
	}

	/**
	 * Builds the OpenDTU request that sets the limit.
	 *
	 * @param baseUrl  e.g. "http://192.168.1.201"
	 * @param serial   the inverter's serial number
	 * @param percent  the limit in percent
	 * @param username the OpenDTU admin user
	 * @param password the OpenDTU admin password
	 * @return the {@link Endpoint}
	 */
	static Endpoint request(String baseUrl, String serial, double percent, String username, String password) {
		var data = new JsonObject();
		data.addProperty("serial", serial);
		data.addProperty("limit_type", RELATIVE_NON_PERSISTENT);
		data.addProperty("limit_value", percent);
		var credentials = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
		return BridgeHttp.create(baseUrl + "/api/limit/config") //
				.setHeader("Authorization", "Basic " + credentials) //
				.setBodyFormEncoded(Map.of("data", data.toString())) //
				.build();
	}
}
