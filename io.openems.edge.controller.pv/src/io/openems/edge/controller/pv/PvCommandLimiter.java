package io.openems.edge.controller.pv;

import java.time.Duration;
import java.time.Instant;

/**
 * Execute Slot's command rules, per the PV Controller specification — plain
 * Java, no OpenEMS dependency, so the rules are unit-testable on their own.
 *
 * <p>
 * Two sources ask for an output limit: the planner (once per slot, from the
 * active plan) and the Balancing Controller (real-time corrections).
 * Precedence: a Balancing correction holds until the start of the next slot,
 * or until the Balancing Controller resets the limit to {@code P_max}
 * (release); the planner never re-asserts its own limit within the slot.
 *
 * <p>
 * Every change of the commanded limit, whichever source asked for it,
 * respects the execution limits: no change within
 * {@code minCurtailmentPeriod} of the previous change, changes smaller than
 * {@code deadband} are not made, and each change moves at most
 * {@code rampLimit} × the time since the previous change.
 */
final class PvCommandLimiter {

	/** Which controller asked for the currently commanded limit. */
	enum Source {
		PLANNER, BALANCING
	}

	private final double deadbandW;
	private final Duration minCurtailmentPeriod;
	private final double rampLimitWPerSecond;

	private Double balancingLimitW;
	private Instant balancingSlotStart;

	private Double lastCommandW;
	private Instant lastCommandTime;
	private Source lastSource;

	/**
	 * Creates the limiter.
	 *
	 * @param deadbandW            minimum change worth sending, in W
	 * @param minCurtailmentPeriod minimum time between two changes
	 * @param rampLimitWPerSecond  maximum change rate, in W/s
	 */
	PvCommandLimiter(double deadbandW, Duration minCurtailmentPeriod, double rampLimitWPerSecond) {
		this.deadbandW = deadbandW;
		this.minCurtailmentPeriod = minCurtailmentPeriod;
		this.rampLimitWPerSecond = rampLimitWPerSecond;
	}

	/**
	 * A correction from the Balancing Controller. A limit at or above
	 * {@code P_max} releases a previous correction.
	 *
	 * @param limitW    the requested output limit, in W
	 * @param pMaxW     the inverter's {@code P_max}, in W
	 * @param slotStart the start of the slot this correction is made in
	 */
	void requestBalancing(double limitW, double pMaxW, Instant slotStart) {
		if (limitW >= pMaxW) {
			this.balancingLimitW = null;
			this.balancingSlotStart = null;
			return;
		}
		this.balancingLimitW = Math.max(0, limitW);
		this.balancingSlotStart = slotStart;
	}

	/**
	 * Called every cycle: decides the limit to command right now.
	 *
	 * @param now            the current time
	 * @param slotStart      the start of the current slot
	 * @param plannerLimitW  the active plan's limit for this slot, in W
	 * @return the limit to command, in W — unchanged from the previous call
	 *             unless the rules above allow a change
	 */
	double step(Instant now, Instant slotStart, double plannerLimitW) {
		if (this.balancingSlotStart != null && !this.balancingSlotStart.equals(slotStart)) {
			// A Balancing correction only holds until the next slot starts.
			this.balancingLimitW = null;
			this.balancingSlotStart = null;
		}
		var source = this.balancingLimitW != null ? Source.BALANCING : Source.PLANNER;
		var desiredW = source == Source.BALANCING ? this.balancingLimitW : plannerLimitW;

		if (this.lastCommandW == null) {
			return this.change(now, desiredW, source);
		}
		var deltaW = desiredW - this.lastCommandW;
		if (Math.abs(deltaW) < this.deadbandW) {
			return this.lastCommandW;
		}
		var elapsed = Duration.between(this.lastCommandTime, now);
		if (elapsed.compareTo(this.minCurtailmentPeriod) < 0) {
			return this.lastCommandW;
		}
		var maxStepW = this.rampLimitWPerSecond * elapsed.toMillis() / 1000.0;
		var stepW = Math.max(-maxStepW, Math.min(maxStepW, deltaW));
		return this.change(now, this.lastCommandW + stepW, source);
	}

	private double change(Instant now, double limitW, Source source) {
		this.lastCommandW = limitW;
		this.lastCommandTime = now;
		this.lastSource = source;
		return limitW;
	}

	/**
	 * The source of the currently commanded limit.
	 *
	 * @return the source, or null before the first command
	 */
	Source lastSource() {
		return this.lastSource;
	}
}
