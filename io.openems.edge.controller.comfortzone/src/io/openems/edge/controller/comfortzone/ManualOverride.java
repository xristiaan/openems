package io.openems.edge.controller.comfortzone;

import java.time.Duration;
import java.time.Instant;

import io.openems.edge.hvac.api.HvacMode;

/**
 * Manual changes (the specification's user override): a change made by hand
 * on the device or in its app is followed instead of undone.
 *
 * <p>
 * A manual change is a difference between the device's read-back and the last
 * command, once the device has shown that command (so an unconfirmed or failed
 * write, or a set point the device can't take, is not one) and the settling
 * time after the command has passed. Set points within the tolerance count as
 * the same (the device rounds). The override lasts until the next step in the
 * schedule, at most the maximum duration if one is given, or until the device
 * shows the last command again (the user reversed the change).
 *
 * <p>
 * Plain Java, no OpenEMS dependencies, so it is unit-testable on its own.
 */
final class ManualOverride {

	/**
	 * What the device reports; null fields are unknown.
	 *
	 * @param on           on or off
	 * @param mode         heat, cool, off or undefined
	 * @param setpointDeci the set point in deci-degrees Celsius
	 */
	record Readback(Boolean on, HvacMode mode, Integer setpointDeci) {
	}

	/**
	 * What the controller sends.
	 *
	 * @param on           on or off
	 * @param mode         heat or cool
	 * @param setpointDeci the set point in deci-degrees Celsius; null while off
	 */
	record Sent(boolean on, HvacMode mode, Integer setpointDeci) {
	}

	private final int toleranceDeci;
	private final Duration settling;
	private final Duration maxDuration;

	private Sent lastSent;
	private Instant sentAt;
	private boolean adopted;

	private boolean active;
	private Instant until;
	private Readback manual;

	/**
	 * Creates it.
	 *
	 * @param toleranceDeci set points this close (deci-degrees) count as equal
	 * @param settling      after a new command, differences are ignored this
	 *                      long
	 * @param maxDuration   the longest override, or null: until the next step
	 *                      in the schedule
	 */
	ManualOverride(int toleranceDeci, Duration settling, Duration maxDuration) {
		this.toleranceDeci = toleranceDeci;
		this.settling = settling;
		this.maxDuration = maxDuration;
	}

	/**
	 * Called every cycle with what the controller would send.
	 *
	 * @param now      the current time
	 * @param wanted   the command the controller would send
	 * @param readback what the device reports
	 * @param nextStep the next step in the schedule, or null if none
	 * @return true while a manual change is followed: send nothing
	 */
	synchronized boolean hold(Instant now, Sent wanted, Readback readback, Instant nextStep) {
		if (this.active) {
			var expired = this.until != null && !now.isBefore(this.until);
			if (!expired && !this.shows(this.lastSent, readback)) {
				return true;
			}
			this.end();
		}
		if (!wanted.equals(this.lastSent)) {
			this.lastSent = wanted;
			this.sentAt = now;
			this.adopted = this.shows(wanted, readback);
			return false;
		}
		if (this.shows(wanted, readback)) {
			this.adopted = true;
			return false;
		}
		if (this.adopted && Duration.between(this.sentAt, now).compareTo(this.settling) >= 0) {
			this.start(now, readback, nextStep);
			return true;
		}
		return false;
	}

	private void start(Instant now, Readback readback, Instant nextStep) {
		this.active = true;
		this.manual = readback;
		var latest = this.maxDuration == null ? null : now.plus(this.maxDuration);
		this.until = nextStep == null ? latest : latest == null || nextStep.isBefore(latest) ? nextStep : latest;
	}

	private void end() {
		this.active = false;
		this.manual = null;
		this.until = null;
		// The plan's command is sent again: it may equal the one before the change
		this.lastSent = null;
	}

	/**
	 * Whether the device shows a command: its on/off is known and equal, and, if
	 * on, its mode and set point where known.
	 *
	 * @param sent     the command, or null
	 * @param readback what the device reports
	 * @return true if the device shows it
	 */
	private boolean shows(Sent sent, Readback readback) {
		if (sent == null || readback.on() == null || readback.on() != sent.on()) {
			return false;
		}
		if (!sent.on()) {
			return true;
		}
		var modeKnown = readback.mode() == HvacMode.HEAT || readback.mode() == HvacMode.COOL;
		if (modeKnown && sent.mode() != null && readback.mode() != sent.mode()) {
			return false;
		}
		return readback.setpointDeci() == null || sent.setpointDeci() == null
				|| Math.abs(readback.setpointDeci() - sent.setpointDeci()) <= this.toleranceDeci;
	}

	/**
	 * Whether a manual change is being followed.
	 *
	 * @return true during an override
	 */
	synchronized boolean active() {
		return this.active;
	}

	/**
	 * When the override ends.
	 *
	 * @return the end, or null if it has none (no schedule, no maximum) or none
	 *         is active
	 */
	synchronized Instant until() {
		return this.until;
	}

	/**
	 * The target during the override, for planning: the manual set point if the
	 * device is on, else the given minimum (off).
	 *
	 * @param offC the target while switched off by hand
	 * @return the target in °C, or null if no override is active
	 */
	synchronized Double targetC(double offC) {
		if (!this.active) {
			return null;
		}
		if (Boolean.TRUE.equals(this.manual.on()) && this.manual.setpointDeci() != null) {
			return this.manual.setpointDeci() / 10.0;
		}
		return offC;
	}
}
