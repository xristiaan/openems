package io.openems.edge.controller.comfortzone;

import io.openems.edge.scheduling.algorithm.ModeSwitchingMatrix;
import io.openems.edge.scheduling.algorithm.ModeTransition;

/**
 * The Comfort Zone's Mode Switching Matrix. All transitions are allowed. A
 * modulating device switches for free and stays at least 1 slot in a mode. An
 * on-off device pays the Start Cost for every switch from OFF to another mode,
 * stays OFF at least its Minimum Off Time and in the other modes at least its
 * Minimum On Time.
 *
 * @param deviceType   modulating or on-off
 * @param startCostEur the cost of one start, in €
 * @param minOnSlots   the Minimum On Time, in slots (&ge; 1)
 * @param minOffSlots  the Minimum Off Time, in slots (&ge; 1)
 */
public record ZoneModeSwitchingMatrix(DeviceType deviceType, double startCostEur, int minOnSlots,
		int minOffSlots) implements ModeSwitchingMatrix<ComfortZoneMode> {

	@Override
	public ModeTransition transition(ComfortZoneMode priorMode, ComfortZoneMode currentMode) {
		if (this.deviceType == DeviceType.ON_OFF && priorMode == ComfortZoneMode.OFF
				&& currentMode != ComfortZoneMode.OFF) {
			return new ModeTransition(true, this.startCostEur);
		}
		return ModeTransition.FREE;
	}

	@Override
	public int minSlotsInMode(ComfortZoneMode mode) {
		if (this.deviceType == DeviceType.MODULATING) {
			return 1;
		}
		return Math.max(1, mode == ComfortZoneMode.OFF ? this.minOffSlots : this.minOnSlots);
	}
}
