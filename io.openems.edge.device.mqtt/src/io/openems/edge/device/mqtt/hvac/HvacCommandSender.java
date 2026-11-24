package io.openems.edge.device.mqtt.hvac;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openems.edge.hvac.api.HvacMode;

/**
 * Decides which commands to send to a heating / cooling device behind a
 * platform bridge, builds their envelopes and tracks their acknowledgements.
 * Each of on/off, mode and set point is its own command ({@code on} /
 * {@code off}, {@code set-mode} "heat" | "cool", {@code set-setpoint} in °C).
 *
 * <p>
 * A command is sent only when the wanted value changes, at most once per
 * minimum interval — never just repeated, so a change made by hand on the
 * device is not overwritten as long as the wanted value stays the same. A
 * field that is not wanted (null) is forgotten: its next wanted value is sent
 * even if it equals the last one. A
 * command that failed or was not acknowledged within the ack timeout is sent
 * again. While the device is to be off, only the {@code off} is sent: on some
 * platforms (MELCloud) a mode switches the device on.
 *
 * <p>
 * Plain Java, no OpenEMS dependencies, so it is unit-testable on its own.
 */
public class HvacCommandSender {

	/** What a Controller wants; null fields are left as they are. */
	public record Wanted(Boolean on, HvacMode mode, Integer setpointDeci) {
	}

	private enum Field {
		ON, MODE, SETPOINT
	}

	private record Command(String verb, Object value) {
	}

	private final String deviceId;
	private final String source;
	private final int setpointStepDeci;
	private final Duration minInterval;
	private final Duration ackTimeout;
	private final Supplier<String> commandIds;

	private final Map<Field, Command> sent = new EnumMap<>(Field.class);
	private final Map<Field, Instant> sentAt = new EnumMap<>(Field.class);
	private final Map<Field, Boolean> failed = new EnumMap<>(Field.class);
	private final Map<String, Field> pending = new HashMap<>();
	private boolean lastFailed;
	private String lastFailure;

	/**
	 * Creates the sender.
	 *
	 * @param deviceId        the platform device the commands are for
	 * @param source          who sends them, e.g. the Component-ID
	 * @param setpointStepDeci the device's set point step in deci-degrees (5
	 *                        for 0.5 °C); set points are rounded to it
	 * @param minInterval     the shortest time between two commands of the same
	 *                        kind
	 * @param ackTimeout      after this without an ack, a command is sent again
	 */
	public HvacCommandSender(String deviceId, String source, int setpointStepDeci, Duration minInterval,
			Duration ackTimeout) {
		this(deviceId, source, setpointStepDeci, minInterval, ackTimeout, () -> UUID.randomUUID().toString());
	}

	HvacCommandSender(String deviceId, String source, int setpointStepDeci, Duration minInterval,
			Duration ackTimeout, Supplier<String> commandIds) {
		this.deviceId = deviceId;
		this.source = source;
		this.setpointStepDeci = Math.max(1, setpointStepDeci);
		this.minInterval = minInterval;
		this.ackTimeout = ackTimeout;
		this.commandIds = commandIds;
	}

	/**
	 * The commands to publish now, in order: on/off, mode, set point.
	 *
	 * @param now    the current time
	 * @param wanted what the Controller wants
	 * @return the command envelopes as JSON; empty if nothing is due
	 */
	public synchronized List<String> next(Instant now, Wanted wanted) {
		var result = new ArrayList<String>();
		if (wanted.on() != null) {
			this.sendIfDue(now, Field.ON, new Command(wanted.on() ? "on" : "off", null), result);
		} else {
			this.forget(Field.ON);
		}
		if (Boolean.FALSE.equals(wanted.on())) {
			return result;
		}
		if (wanted.mode() == HvacMode.HEAT || wanted.mode() == HvacMode.COOL) {
			this.sendIfDue(now, Field.MODE, new Command("set-mode", wanted.mode().name().toLowerCase(Locale.ROOT)),
					result);
		} else if (wanted.mode() == null) {
			this.forget(Field.MODE);
		}
		if (wanted.setpointDeci() != null) {
			this.sendIfDue(now, Field.SETPOINT, new Command("set-setpoint", this.toCelsius(wanted.setpointDeci())),
					result);
		} else {
			this.forget(Field.SETPOINT);
		}
		return result;
	}

	// Nobody wants this field now: the next wanted value is sent as new, even if
	// it equals the last one (the device may have been changed by hand meanwhile)
	private void forget(Field field) {
		this.sent.remove(field);
		this.sentAt.remove(field);
		this.failed.remove(field);
		this.pending.values().removeIf(field::equals);
	}

	private void sendIfDue(Instant now, Field field, Command command, List<String> result) {
		var last = this.sent.get(field);
		if (last != null) {
			var elapsed = Duration.between(this.sentAt.get(field), now);
			var retry = this.pending.containsValue(field) || this.failed.getOrDefault(field, false);
			var due = !last.equals(command) ? elapsed.compareTo(this.minInterval) >= 0
					: retry && elapsed.compareTo(this.ackTimeout) > 0;
			if (!due) {
				return;
			}
		}
		this.pending.values().remove(field);
		var commandId = this.commandIds.get();
		this.pending.put(commandId, field);
		this.sent.put(field, command);
		this.sentAt.put(field, now);
		this.failed.put(field, false);
		result.add(this.envelope(now, commandId, command).toString());
	}

	private double toCelsius(int deci) {
		var rounded = Math.round((double) deci / this.setpointStepDeci) * this.setpointStepDeci;
		return rounded / 10.0;
	}

	private JsonObject envelope(Instant now, String commandId, Command command) {
		var payload = new JsonObject();
		payload.addProperty("deviceId", this.deviceId);
		payload.addProperty("command", command.verb());
		if (command.value() instanceof Number number) {
			payload.addProperty("value", number);
		} else if (command.value() != null) {
			payload.addProperty("value", command.value().toString());
		}
		var envelope = new JsonObject();
		envelope.addProperty("commandId", commandId);
		envelope.addProperty("source", this.source);
		envelope.addProperty("type", command.verb());
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
		var field = commandId == null ? null : this.pending.remove(commandId);
		if (field == null) {
			return;
		}
		var ok = "ok".equals(ack.has("status") ? ack.get("status").getAsString() : null);
		this.failed.put(field, !ok);
		this.lastFailed = !ok;
		this.lastFailure = !ok && ack.has("detail") ? ack.get("detail").getAsString() : null;
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
	 * Whether a command has waited longer than the ack timeout.
	 *
	 * @param now the current time
	 * @return true if one is still unacknowledged after the timeout
	 */
	public synchronized boolean unacknowledged(Instant now) {
		return this.pending.values().stream()
				.anyMatch(field -> Duration.between(this.sentAt.get(field), now).compareTo(this.ackTimeout) > 0);
	}

	/**
	 * The last set point sent.
	 *
	 * @return the set point in °C, or null before the first one
	 */
	public synchronized Double lastSetpointC() {
		var command = this.sent.get(Field.SETPOINT);
		return command == null ? null : (Double) Objects.requireNonNull(command.value());
	}
}
