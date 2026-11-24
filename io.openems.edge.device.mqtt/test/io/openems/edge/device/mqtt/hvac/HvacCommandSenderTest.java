package io.openems.edge.device.mqtt.hvac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import io.openems.edge.device.mqtt.hvac.HvacCommandSender.Wanted;
import io.openems.edge.hvac.api.HvacMode;

class HvacCommandSenderTest {

	private static final Instant T0 = Instant.parse("2026-11-22T21:00:00Z");

	private static HvacCommandSender sender() {
		var ids = new AtomicInteger();
		// 0.5 °C step, 10 s minimum interval, 60 s ack timeout
		return new HvacCommandSender("airco", "hvac0", 5, Duration.ofSeconds(10), Duration.ofSeconds(60),
				() -> "c" + ids.incrementAndGet());
	}

	// "verb value" per command, e.g. "set-setpoint 20.5"
	private static List<String> verbs(List<String> envelopes) {
		return envelopes.stream().map(json -> {
			var payload = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("payload");
			var value = payload.has("value") ? " " + payload.get("value").getAsString() : "";
			return payload.get("command").getAsString() + value;
		}).toList();
	}

	private static String ack(String commandId, String status) {
		return "{\"commandId\": \"" + commandId + "\", \"status\": \"" + status + "\"}";
	}

	@Test
	void onModeAndSetpointAreSentInOrder() {
		var sender = sender();
		var commands = sender.next(T0, new Wanted(true, HvacMode.HEAT, 201));

		assertEquals(List.of("on", "set-mode heat", "set-setpoint 20.0"), verbs(commands),
				"20.1 rounded to the 0.5 step");
		var envelope = JsonParser.parseString(commands.get(0)).getAsJsonObject();
		assertEquals("c1", envelope.get("commandId").getAsString());
		assertEquals("airco", envelope.getAsJsonObject("payload").get("deviceId").getAsString());
	}

	@Test
	void nothingIsRepeatedWhileTheWantedValuesStayTheSame() {
		var sender = sender();
		sender.next(T0, new Wanted(true, HvacMode.HEAT, 200));
		sender.onAck(ack("c1", "ok"));
		sender.onAck(ack("c2", "ok"));
		sender.onAck(ack("c3", "ok"));

		assertEquals(List.of(), sender.next(T0.plusSeconds(3600), new Wanted(true, HvacMode.HEAT, 200)),
				"a manual change on the device is not overwritten");
		assertEquals(List.of(), sender.next(T0.plusSeconds(3600), new Wanted(true, HvacMode.HEAT, 199)),
				"the same after rounding");
	}

	@Test
	void aChangeIsSentAfterTheMinimumInterval() {
		var sender = sender();
		sender.next(T0, new Wanted(true, HvacMode.HEAT, 200));

		assertEquals(List.of(), sender.next(T0.plusSeconds(5), new Wanted(true, HvacMode.HEAT, 210)));
		assertEquals(List.of("set-setpoint 21.0"),
				verbs(sender.next(T0.plusSeconds(10), new Wanted(true, HvacMode.HEAT, 210))));
	}

	@Test
	void offSendsOnlyTheOff() {
		var sender = sender();
		assertEquals(List.of("off"), verbs(sender.next(T0, new Wanted(false, HvacMode.HEAT, 200))));
		assertEquals(List.of("on", "set-mode heat", "set-setpoint 20.0"),
				verbs(sender.next(T0.plusSeconds(10), new Wanted(true, HvacMode.HEAT, 200))));
	}

	@Test
	void aValueNobodyWantedIsSentAgainOnceWanted() {
		var sender = sender();
		sender.next(T0, new Wanted(true, HvacMode.HEAT, 200));
		sender.onAck(ack("c1", "ok"));
		sender.onAck(ack("c2", "ok"));
		sender.onAck(ack("c3", "ok"));

		assertEquals(List.of(), sender.next(T0.plusSeconds(60), new Wanted(null, null, null)),
				"a Controller following a manual change writes nothing");
		assertEquals(List.of("on", "set-mode heat", "set-setpoint 20.0"),
				verbs(sender.next(T0.plusSeconds(61), new Wanted(true, HvacMode.HEAT, 200))),
				"after the override: sent again, the device may differ");
	}

	@Test
	void unknownValuesAreNotSent() {
		var sender = sender();
		assertEquals(List.of("set-setpoint 19.5"), verbs(sender.next(T0, new Wanted(null, HvacMode.OFF, 195))));
	}

	@Test
	void aFailedOrUnacknowledgedCommandIsSentAgainAfterTheAckTimeout() {
		var sender = sender();
		sender.next(T0, new Wanted(true, null, null));
		sender.onAck("{\"commandId\": \"c1\", \"status\": \"error\", \"detail\": \"Homey unreachable\"}");
		assertTrue(sender.lastCommandFailed());
		assertEquals("Homey unreachable", sender.lastFailure());

		assertEquals(List.of(), sender.next(T0.plusSeconds(60), new Wanted(true, null, null)));
		assertEquals(List.of("on"), verbs(sender.next(T0.plusSeconds(61), new Wanted(true, null, null))));

		assertFalse(sender.unacknowledged(T0.plusSeconds(121)));
		assertTrue(sender.unacknowledged(T0.plusSeconds(122)), "c2 never acknowledged");
		assertEquals(List.of("on"), verbs(sender.next(T0.plusSeconds(122), new Wanted(true, null, null))));
		sender.onAck(ack("c3", "ok"));
		assertFalse(sender.lastCommandFailed());
		assertFalse(sender.unacknowledged(T0.plusSeconds(500)));
	}

	@Test
	void acksForOtherCommandsAndGarbageAreIgnored() {
		var sender = sender();
		sender.next(T0, new Wanted(true, null, null));
		sender.onAck(ack("other", "error"));
		sender.onAck("not json");
		assertFalse(sender.lastCommandFailed());
		assertTrue(sender.unacknowledged(T0.plusSeconds(61)));
	}
}
