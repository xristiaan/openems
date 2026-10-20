package io.openems.edge.device.mqtt.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Decides when to send a {@code set-power} command to a device behind a
 * platform bridge (bridge-homey, bridge-home-assistant), builds the command
 * envelope, and tracks its acknowledgement.
 *
 * <p>
 * A platform round trip (MQTT, bridge, Homey flow) takes seconds, so not every
 * new value is sent: only a change of at least the deadband, and at most once
 * per minimum interval. An unchanged value is re-sent after the resend
 * interval, so a device that restarted or missed a command gets it again —
 * or already after the ack timeout, if the last command was never
 * acknowledged.
 *
 * <p>
 * Plain Java, no OpenEMS dependencies, so it is unit-testable on its own.
 */
public class CommandSender {

	private final String deviceId;
	private final String source;
	private final double deadbandW;
	private final Duration minInterval;
	private final Duration resendInterval;
	private final Duration ackTimeout;
	private final Supplier<String> commandIds;

	private Integer lastValueW;
	private Instant lastSentAt;
	private String pendingCommandId;
	private boolean lastFailed;
	private String lastFailure;

	/**
	 * Creates the sender.
	 *
	 * @param deviceId       the platform device the commands are for
	 * @param source         who sends them, e.g. the Component-ID
	 * @param deadbandW      the smallest change worth sending, in W
	 * @param minInterval    the shortest time between two commands
	 * @param resendInterval after this, an unchanged value is sent again
	 * @param ackTimeout     after this without an ack, a command counts as
	 *                       unacknowledged
	 */
	public CommandSender(String deviceId, String source, double deadbandW, Duration minInterval,
			Duration resendInterval, Duration ackTimeout) {
		this(deviceId, source, deadbandW, minInterval, resendInterval, ackTimeout,
				() -> UUID.randomUUID().toString());
	}

	CommandSender(String deviceId, String source, double deadbandW, Duration minInterval, Duration resendInterval,
			Duration ackTimeout, Supplier<String> commandIds) {
		this.deviceId = deviceId;
		this.source = source;
		this.deadbandW = deadbandW;
		this.minInterval = minInterval;
		this.resendInterval = resendInterval;
		this.ackTimeout = ackTimeout;
		this.commandIds = commandIds;
	}

	/**
	 * The command to publish now for the wanted value, if any.
	 *
	 * @param now      the current time
	 * @param valueW   the wanted value, in W
	 * @param fraction the same value as a fraction of the device's rating, for
	 *                 platforms that take a percentage; null if not known
	 * @return the command envelope as JSON, or empty if nothing is to be sent
	 */
	public synchronized Optional<String> next(Instant now, int valueW, Double fraction) {
		if (this.lastValueW != null) {
			var elapsed = Duration.between(this.lastSentAt, now);
			var changed = Math.abs(valueW - this.lastValueW) >= this.deadbandW;
			var due = changed ? elapsed.compareTo(this.minInterval) >= 0
					: elapsed.compareTo(this.resendInterval) >= 0 || this.unacknowledged(now);
			if (!due) {
				return Optional.empty();
			}
		}
		this.lastValueW = valueW;
		this.lastSentAt = now;
		this.pendingCommandId = this.commandIds.get();
		return Optional.of(this.envelope(now, valueW, fraction).toString());
	}

	private JsonObject envelope(Instant now, int valueW, Double fraction) {
		var payload = new JsonObject();
		payload.addProperty("deviceId", this.deviceId);
		payload.addProperty("command", "set-power");
		payload.addProperty("value", valueW);
		if (fraction != null) {
			payload.addProperty("fraction", fraction);
		}
		var envelope = new JsonObject();
		envelope.addProperty("commandId", this.pendingCommandId);
		envelope.addProperty("source", this.source);
		envelope.addProperty("type", "set-power");
		envelope.addProperty("reason", "openems-plan");
		envelope.addProperty("priority", 5);
		envelope.add("payload", payload);
		envelope.addProperty("issuedAt", now.toString());
		return envelope;
	}

	/**
	 * Handles a message from the ack topic; acks for other commands are
	 * ignored.
	 *
	 * @param json the ack, {@code {"commandId", "status": "ok"|"error",
	 *             "detail"?}}
	 */
	public synchronized void onAck(String json) {
		JsonObject ack;
		try {
			ack = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		var commandId = ack.has("commandId") ? ack.get("commandId").getAsString() : null;
		if (commandId == null || !commandId.equals(this.pendingCommandId)) {
			return;
		}
		this.pendingCommandId = null;
		this.lastFailed = !"ok".equals(ack.has("status") ? ack.get("status").getAsString() : null);
		this.lastFailure = this.lastFailed && ack.has("detail") ? ack.get("detail").getAsString() : null;
	}

	/**
	 * Whether the platform reported the last acknowledged command as failed.
	 *
	 * @return true if it failed
	 */
	public synchronized boolean lastCommandFailed() {
		return this.lastFailed;
	}

	/**
	 * The platform's reason for the last failure.
	 *
	 * @return the detail, or null
	 */
	public synchronized String lastFailure() {
		return this.lastFailure;
	}

	/**
	 * Whether the last command has waited longer than the ack timeout.
	 *
	 * @param now the current time
	 * @return true if it is still unacknowledged after the timeout
	 */
	public synchronized boolean unacknowledged(Instant now) {
		return this.pendingCommandId != null
				&& Duration.between(this.lastSentAt, now).compareTo(this.ackTimeout) > 0;
	}

	/**
	 * The last value sent.
	 *
	 * @return the value in W, or null before the first command
	 */
	public synchronized Integer lastValueW() {
		return this.lastValueW;
	}
}
