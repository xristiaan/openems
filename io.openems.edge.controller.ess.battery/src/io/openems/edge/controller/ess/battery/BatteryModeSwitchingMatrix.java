package io.openems.edge.controller.ess.battery;

import io.openems.edge.scheduling.algorithm.ModeSwitchingMatrix;
import io.openems.edge.scheduling.algorithm.ModeTransition;

/**
 * The Battery Controller's Mode Switching Matrix: every transition is free
 * and every mode has a minimum dwell time of 1 slot — a battery has no real
 * physical cycling-lock concern the way a compressor does. Degradation cost
 * is charged separately, per unit of energy throughput, in
 * {@link BatteryPhysics} — not as a switching cost here.
 */
public final class BatteryModeSwitchingMatrix implements ModeSwitchingMatrix<BatteryMode> {

	@Override
	public ModeTransition transition(BatteryMode priorMode, BatteryMode currentMode) {
		return ModeTransition.FREE;
	}

	@Override
	public int minSlotsInMode(BatteryMode mode) {
		return 1;
	}
}
