package io.openems.edge.controller.pv;

import io.openems.edge.scheduling.algorithm.ModeSwitchingMatrix;
import io.openems.edge.scheduling.algorithm.ModeTransition;

/**
 * The PV Controller's Mode Switching Matrix: every transition is allowed and
 * free, minimum time in mode 1 slot. Within-slot limits on set-point changes
 * (dwell time, ramp, deadband) are execution limits, enforced by
 * {@link PvCommandLimiter}, not planning constraints.
 */
public final class PvModeSwitchingMatrix implements ModeSwitchingMatrix<PvMode> {

	@Override
	public ModeTransition transition(PvMode priorMode, PvMode currentMode) {
		return ModeTransition.FREE;
	}

	@Override
	public int minSlotsInMode(PvMode mode) {
		return 1;
	}
}
