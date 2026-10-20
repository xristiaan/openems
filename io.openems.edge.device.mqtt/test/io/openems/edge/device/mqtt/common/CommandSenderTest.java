package io.openems.edge.device.mqtt.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class CommandSenderTest {

	private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
	private static final String OK_1 = "{\"commandId\":\"cmd-1\",\"status\":\"ok\"}";

	private static CommandSender sender() {
		var ids = new int[] { 0 };
		// 50W deadband, 5s between commands, resend after 300s, ack within 30s.
		return new CommandSender("homey-battery-1", "ess0", 50, Duration.ofSeconds(5), Duration.ofSeconds(300),
				Duration.ofSeconds(30), () -> "cmd-" + ++ids[0]);
	}

	@Test
	void theFirstValueIsSentAsASetPowerEnvelope() {
		var json = sender().next(T0, 1_200, 0.24).orElseThrow();
		var envelope = JsonParser.parseString(json).getAsJsonObject();
		var payload = envelope.getAsJsonObject("payload");

		assertEquals("cmd-1", envelope.get("commandId").getAsString());
		assertEquals("ess0", envelope.get("source").getAsString());
		assertEquals("set-power", envelope.get("type").getAsString());
		assertEquals("homey-battery-1", payload.get("deviceId").getAsString());
		assertEquals("set-power", payload.get("command").getAsString());
		assertEquals(1_200, payload.get("value").getAsInt());
		assertEquals(0.24, payload.get("fraction").getAsDouble(), 1e-9);
		assertEquals(T0.toString(), envelope.get("issuedAt").getAsString());
	}

	@Test
	void aChangeBelowTheDeadbandIsNotSent() {
		var sender = sender();
		sender.next(T0, 1_200, null);
		sender.onAck(OK_1);

		assertTrue(sender.next(T0.plusSeconds(60), 1_230, null).isEmpty());
	}

	@Test
	void aChangeWaitsForTheMinimumInterval() {
		var sender = sender();
		sender.next(T0, 1_200, null);

		assertTrue(sender.next(T0.plusSeconds(2), 2_000, null).isEmpty());
		assertTrue(sender.next(T0.plusSeconds(5), 2_000, null).isPresent());
	}

	@Test
	void anUnchangedValueIsResentAfterTheResendInterval() {
		var sender = sender();
		sender.next(T0, 1_200, null);
		sender.onAck(OK_1);

		assertTrue(sender.next(T0.plusSeconds(299), 1_200, null).isEmpty());
		assertTrue(sender.next(T0.plusSeconds(300), 1_200, null).isPresent());
	}

	@Test
	void aCommandWithoutAckIsResentAfterTheAckTimeout() {
		var sender = sender();
		sender.next(T0, 1_200, null);

		assertTrue(sender.next(T0.plusSeconds(30), 1_200, null).isEmpty());
		var resent = sender.next(T0.plusSeconds(31), 1_200, null).orElseThrow();
		assertEquals("cmd-2", JsonParser.parseString(resent).getAsJsonObject().get("commandId").getAsString());
		assertFalse(sender.unacknowledged(T0.plusSeconds(31)), "the resend starts a new wait");
	}

	@Test
	void anErrorAckForTheLastCommandIsReportedAndAnOkClearsIt() {
		var sender = sender();
		sender.next(T0, 1_200, null);

		sender.onAck("{\"commandId\":\"cmd-1\",\"status\":\"error\",\"detail\":\"no flow\"}");
		assertTrue(sender.lastCommandFailed());
		assertEquals("no flow", sender.lastFailure());

		sender.next(T0.plusSeconds(10), 2_000, null);
		sender.onAck("{\"commandId\":\"cmd-2\",\"status\":\"ok\"}");
		assertFalse(sender.lastCommandFailed());
	}

	@Test
	void acksForOtherCommandsAndGarbageAreIgnored() {
		var sender = sender();
		sender.next(T0, 1_200, null);

		sender.onAck("{\"commandId\":\"someone-else\",\"status\":\"error\"}");
		sender.onAck("not json");
		assertFalse(sender.lastCommandFailed());
		assertTrue(sender.unacknowledged(T0.plusSeconds(31)), "still waiting for its own ack");
	}

	@Test
	void aCommandIsUnacknowledgedOnlyAfterTheTimeout() {
		var sender = sender();
		sender.next(T0, 1_200, null);

		assertFalse(sender.unacknowledged(T0.plusSeconds(30)));
		assertTrue(sender.unacknowledged(T0.plusSeconds(31)));
		sender.onAck("{\"commandId\":\"cmd-1\",\"status\":\"ok\"}");
		assertFalse(sender.unacknowledged(T0.plusSeconds(31)));
	}
}
