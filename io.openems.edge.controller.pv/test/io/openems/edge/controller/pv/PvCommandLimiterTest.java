package io.openems.edge.controller.pv;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.pv.PvCommandLimiter.Source;

class PvCommandLimiterTest {

	private static final double P_MAX_W = 4_000;
	private static final Instant SLOT_0 = Instant.parse("2026-10-17T12:00:00Z");
	private static final Instant SLOT_1 = SLOT_0.plusSeconds(900);

	private static PvCommandLimiter limiter() {
		// 100W deadband, 15s dwell, 1kW/s ramp — the spec defaults.
		return new PvCommandLimiter(100, Duration.ofSeconds(15), 1_000);
	}

	@Test
	void theFirstCommandIsSentDirectly() {
		var limiter = limiter();

		assertEquals(4_000, limiter.step(SLOT_0, SLOT_0, 4_000), 1e-9);
		assertEquals(Source.PLANNER, limiter.lastSource());
	}

	@Test
	void noChangeWithinTheMinimumCurtailmentPeriod() {
		var limiter = limiter();
		limiter.step(SLOT_0, SLOT_0, 4_000);

		assertEquals(4_000, limiter.step(SLOT_0.plusSeconds(10), SLOT_0, 1_000), 1e-9);
		assertEquals(1_000, limiter.step(SLOT_0.plusSeconds(15), SLOT_0, 1_000), 1e-9);
	}

	@Test
	void changesSmallerThanTheDeadbandAreNotMade() {
		var limiter = limiter();
		limiter.step(SLOT_0, SLOT_0, 2_000);

		assertEquals(2_000, limiter.step(SLOT_0.plusSeconds(60), SLOT_0, 2_050), 1e-9);
	}

	@Test
	void changesAreRampLimited() {
		var limiter = new PvCommandLimiter(100, Duration.ofSeconds(15), 100);
		limiter.step(SLOT_0, SLOT_0, 4_000);

		// 15s at 100W/s: at most 1.5kW per change.
		assertEquals(2_500, limiter.step(SLOT_0.plusSeconds(15), SLOT_0, 0), 1e-9);
		assertEquals(1_000, limiter.step(SLOT_0.plusSeconds(30), SLOT_0, 0), 1e-9);
	}

	@Test
	void thePlannerWaitsTheMinimumPeriodAfterABalancingCorrection() {
		var limiter = limiter();
		limiter.step(SLOT_0, SLOT_0, 4_000);

		// Balancing sets 20% at t=20s.
		limiter.requestBalancing(800, P_MAX_W, SLOT_0);
		assertEquals(800, limiter.step(SLOT_0.plusSeconds(20), SLOT_0, 4_000), 1e-9);
		assertEquals(Source.BALANCING, limiter.lastSource());

		// Next slot starts 880s later: the correction no longer holds, and the
		// planner's own limit is applied — the dwell time has long passed.
		assertEquals(4_000, limiter.step(SLOT_1, SLOT_1, 4_000), 1e-9);
		assertEquals(Source.PLANNER, limiter.lastSource());
	}

	@Test
	void aBalancingCorrectionHoldsForTheRestOfTheSlot() {
		var limiter = limiter();
		limiter.step(SLOT_0, SLOT_0, 4_000);
		limiter.requestBalancing(800, P_MAX_W, SLOT_0);
		limiter.step(SLOT_0.plusSeconds(20), SLOT_0, 4_000);

		assertEquals(800, limiter.step(SLOT_0.plusSeconds(600), SLOT_0, 4_000), 1e-9,
				"the planner doesn't re-assert its own limit within the slot");
	}

	@Test
	void aResetToPmaxReleasesTheBalancingCorrection() {
		var limiter = limiter();
		limiter.step(SLOT_0, SLOT_0, 3_000);
		limiter.requestBalancing(800, P_MAX_W, SLOT_0);
		limiter.step(SLOT_0.plusSeconds(20), SLOT_0, 3_000);

		limiter.requestBalancing(P_MAX_W, P_MAX_W, SLOT_0);
		assertEquals(3_000, limiter.step(SLOT_0.plusSeconds(40), SLOT_0, 3_000), 1e-9);
		assertEquals(Source.PLANNER, limiter.lastSource());
	}

	@Test
	void aBalancingCorrectionAlsoRespectsTheMinimumPeriod() {
		var limiter = limiter();
		limiter.step(SLOT_0, SLOT_0, 4_000);

		limiter.requestBalancing(800, P_MAX_W, SLOT_0);
		assertEquals(4_000, limiter.step(SLOT_0.plusSeconds(5), SLOT_0, 4_000), 1e-9);
		assertEquals(800, limiter.step(SLOT_0.plusSeconds(15), SLOT_0, 4_000), 1e-9);
	}
}
