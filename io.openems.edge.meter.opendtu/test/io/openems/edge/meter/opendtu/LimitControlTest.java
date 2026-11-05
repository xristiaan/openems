package io.openems.edge.meter.opendtu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.openems.common.bridge.http.api.HttpMethod;
import io.openems.common.bridge.http.api.UrlBuilder;

public class LimitControlTest {

	private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

	@Test
	public void toRelative() {
		assertEquals(50.0, LimitControl.toRelative(800, 1600), 0);
		assertEquals(100.0, LimitControl.toRelative(null, 1600), 0);
		assertEquals(100.0, LimitControl.toRelative(5000, 1600), 0);
		assertEquals(0.0, LimitControl.toRelative(-100, 1600), 0);
		assertEquals(33.3, LimitControl.toRelative(533, 1600), 0);
	}

	@Test
	public void nothingToSendWhenTheInverterAlreadyHasTheLimit() {
		var control = new LimitControl();
		assertFalse(control.shouldSend(100, 100.0, T0));
		assertFalse(control.shouldSend(100.5, 100.0, T0.plusSeconds(60)));
		assertTrue("a change goes out at once", control.shouldSend(50, 100.0, T0.plusSeconds(61)));
	}

	@Test
	public void sendsAChangeButSpacesCommandsOut() {
		var control = new LimitControl();
		assertTrue(control.shouldSend(50, 100.0, T0));
		control.sending(50, T0);
		assertFalse("waits for the reply", control.shouldSend(40, 100.0, T0.plusSeconds(1)));
		control.done(true);
		assertFalse("too soon", control.shouldSend(40, 100.0, T0.plusSeconds(5)));
		assertTrue(control.shouldSend(40, 100.0, T0.plusSeconds(10)));
		assertFalse("change too small", control.shouldSend(50.5, 100.0, T0.plusSeconds(10)));
	}

	@Test
	public void resendsALimitTheInverterLost() {
		var control = new LimitControl();
		control.sending(50, T0);
		control.done(true);
		assertFalse("applied", control.shouldSend(50, 50.0, T0.plusSeconds(120)));
		assertFalse("not yet applied", control.shouldSend(50, 100.0, T0.plusSeconds(30)));
		assertTrue("lost, e.g. after a restart", control.shouldSend(50, 100.0, T0.plusSeconds(60)));
	}

	@Test
	public void retriesAfterAFailure() {
		var control = new LimitControl();
		control.sending(50, T0);
		control.done(false);
		assertFalse(control.shouldSend(50, 100.0, T0.plusSeconds(5)));
		assertTrue(control.shouldSend(50, 100.0, T0.plusSeconds(10)));
	}

	@Test
	public void request() {
		var endpoint = LimitControl.request("http://192.168.1.201", "116494504037", 50.0, "admin", "secret");
		assertEquals("http://192.168.1.201/api/limit/config", endpoint.url());
		assertEquals(HttpMethod.POST, endpoint.method());
		assertEquals("Basic YWRtaW46c2VjcmV0", endpoint.properties().get("Authorization"));
		assertEquals("{\"serial\":\"116494504037\",\"limit_type\":1,\"limit_value\":50.0}",
				UrlBuilder.decodeFormUrlencodedBody(endpoint.body()).get("data"));
	}
}
