package io.openems.edge.controller.ess.battery;

import io.openems.edge.scheduling.algorithm.StateTransitionFunction;
import io.openems.edge.scheduling.algorithm.StateTransitionResult;

/**
 * The Battery Controller's State-Transition Function. State is SoC as a
 * fraction (0.0-1.0) of {@link BatteryPhysicsConfig#nominalCapacityWh()}.
 *
 * <p>
 * Works entirely in Wh/W internally — matching the real ESS Channels'
 * native units ({@code CAPACITY} is Wh, power Channels are W) and this
 * package's own {@code StateTransitionResult.energyUsedWh}. {@code t} is
 * tracked in seconds (capped at the 900s slot length), converted to hours
 * only where it multiplies a Watt figure into Wh.
 */
public final class BatteryPhysics implements StateTransitionFunction<BatteryMode> {

	static final double SLOT_DURATION_SECONDS = 900;
	private static final double BOOST_C_RATE_THRESHOLD = 0.5;

	private final BatteryPhysicsConfig config;

	/**
	 * Creates the physics for one planning cycle.
	 *
	 * @param config a snapshot of this planning cycle's parameters,
	 *                   including the live-read battery capacity
	 */
	public BatteryPhysics(BatteryPhysicsConfig config) {
		this.config = config;
	}

	@Override
	public StateTransitionResult apply(double currentSoc, BatteryMode mode, int slot, double availablePowerW,
			Double targetSoc) {
		if (targetSoc == null) {
			// No target to seek this slot: idle, regardless of mode.
			return new StateTransitionResult(0, currentSoc);
		}

		var charging = targetSoc >= currentSoc;
		var cRate = this.cRate(mode, charging);
		if (cRate == 0) {
			// OFF, or any mode with zero rate in this direction: nothing moves.
			return new StateTransitionResult(0, currentSoc);
		}

		var chargeDirection = charging ? 1 : -1;
		var capacityWh = this.config.nominalCapacityWh();
		var pW = Math.min(availablePowerW, capacityWh * cRate);
		var efficiency = charging ? this.config.chargeEfficiency() : 1 / this.config.dischargeEfficiency();

		var eCurrentWh = currentSoc * capacityWh;
		var eTargetWh = targetSoc * capacityWh;

		// Cap how far this mode is allowed to move state at all — its own
		// SoC band, the same physical-limit role as the boiler's T_Max/T_min.
		// Signed, not abs(): if currentSoc is already past the band edge in
		// the direction of travel (e.g. drifted to 85% while in ON, whose
		// NORMAL band caps at 80%), there must be zero room left, not some
		// positive distance back from the wrong side of the edge.
		var band = this.socBand(mode);
		var bandEdgeWh = (charging ? band.max() : band.min()) * capacityWh;
		var remainingBandRoomWh = Math.max(0, charging ? bandEdgeWh - eCurrentWh : eCurrentWh - bandEdgeWh);

		var tTargetSeconds = Math.abs(eTargetWh - eCurrentWh) / pW / efficiency * 3600.0;
		var tBandLimitSeconds = remainingBandRoomWh / pW / efficiency * 3600.0;
		var tSeconds = Math.min(Math.min(tTargetSeconds, tBandLimitSeconds), SLOT_DURATION_SECONDS);
		var tHours = tSeconds / 3600.0;

		var eChangeWh = tHours * pW * efficiency * chargeDirection;
		var eConsumedWh = tHours * pW * chargeDirection;
		var newSoc = (eCurrentWh + eChangeWh) / capacityWh;

		var degradationRate = this.config.degradationCostEurPerKwh()
				* (cRate < BOOST_C_RATE_THRESHOLD ? 1 : this.config.degradationCostBoostMultiplier());
		var deviceCostEur = Math.abs(eChangeWh) / 1000.0 * degradationRate;

		return new StateTransitionResult(eConsumedWh, newSoc, deviceCostEur);
	}

	private double cRate(BatteryMode mode, boolean charging) {
		return switch (mode) {
		case OFF -> 0;
		case ON -> charging ? this.config.cRateOnCharge() : this.config.cRateOnDischarge();
		case BOOST -> charging ? this.config.cRateBoostCharge() : this.config.cRateBoostDischarge();
		case FORCED -> charging ? this.config.cRateForcedCharge() : this.config.cRateForcedDischarge();
		case FORCED_BOOST ->
			charging ? this.config.cRateForcedBoostCharge() : this.config.cRateForcedBoostDischarge();
		};
	}

	private SocBand socBand(BatteryMode mode) {
		return switch (mode) {
		case OFF, ON, FORCED -> new SocBand(this.config.socMinNormal(), this.config.socMaxNormal());
		case BOOST, FORCED_BOOST -> new SocBand(this.config.socMinSafe(), this.config.socMaxSafe());
		};
	}

	private record SocBand(double min, double max) {
	}
}
