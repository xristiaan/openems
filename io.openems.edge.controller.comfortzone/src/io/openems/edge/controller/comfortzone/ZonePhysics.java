package io.openems.edge.controller.comfortzone;

import io.openems.edge.scheduling.algorithm.StateTransitionFunction;
import io.openems.edge.scheduling.algorithm.StateTransitionResult;
import io.openems.edge.scheduling.algorithm.TimeSlot;

/**
 * The State-Transition Function of a single-node Comfort Zone: the room air
 * temperature {@code T_r} is the state.
 *
 * <pre>
 * C_r · dT_r/dt = Q − H_ro · (T_r − T_out)
 * </pre>
 *
 * <p>
 * With the thermal power {@code Q} and {@code T_out} constant within a slot,
 * the end of the slot follows exactly: {@code T_end = T_eq + (T_r − T_eq)·k},
 * with {@code T_eq = T_out + Q / H_ro} and {@code k = e^(−H_ro·t / C_r)}. The
 * device heats (cools) towards the mode's goal: the {@code Q} that reaches it
 * at the end of the slot, within the mode's power and the budget. {@code Q} is
 * the average over the slot: a modulating device runs at it, an on-off device
 * for that fraction of the slot.
 *
 * <p>
 * OFF heats only to {@code T_min} (frost protection) when heating, and cools
 * only to {@code T_max} when cooling, at nominal power.
 */
public final class ZonePhysics implements StateTransitionFunction<ComfortZoneMode> {

	/** The supply temperature is taken as the room temperature plus this, in K. */
	static final double SUPPLY_OFFSET_K = 5.0;

	private final ZonePhysicsConfig config;
	/** {@code e^(−H·t/C)} for one slot. */
	private final double decay;

	/**
	 * Creates the physics.
	 *
	 * @param config the zone's parameters
	 */
	public ZonePhysics(ZonePhysicsConfig config) {
		if (config.capacityKwhPerK() <= 0 || config.transmissionWPerK() <= 0) {
			throw new IllegalArgumentException("Thermal Capacity and TransmissionFactor must be positive");
		}
		this.config = config;
		this.decay = Math.exp(
				-config.transmissionWPerK() * TimeSlot.DURATION_HOURS / (config.capacityKwhPerK() * 1000.0));
	}

	@Override
	public StateTransitionResult apply(double currentState, ComfortZoneMode mode, int slot, double availablePowerW,
			Double targetSetpoint) {
		var config = this.config;
		var outdoorC = this.outdoorC(slot);
		var heating = config.heating();

		double goalC;
		if (mode == ComfortZoneMode.OFF || targetSetpoint == null) {
			goalC = heating ? config.minC() : config.maxC();
		} else {
			goalC = Math.max(config.minC(), Math.min(config.maxC(), targetSetpoint));
		}

		var modePowerW = mode.isBoost() ? config.maxPowerW() : config.nominalPowerW();
		var capW = Math.max(0, Math.min(modePowerW, availablePowerW));
		var efficiency = this.efficiency(outdoorC, currentState);
		var maxThermalW = capW * efficiency;

		var neededW = this.thermalPowerToReach(currentState, goalC, outdoorC);
		var thermalW = heating //
				? Math.max(0, Math.min(maxThermalW, neededW)) //
				: Math.min(0, Math.max(-maxThermalW, neededW));

		var endC = this.endTemperature(currentState, thermalW, outdoorC);
		var energyUsedWh = Math.abs(thermalW) / efficiency * TimeSlot.DURATION_HOURS;
		return new StateTransitionResult(energyUsedWh, endC);
	}

	/**
	 * The COP (heating) or EER (cooling) at the start of a slot.
	 *
	 * @param outdoorC the outdoor temperature, in °C
	 * @param roomC    the room temperature, in °C
	 * @return the efficiency
	 */
	double efficiency(double outdoorC, double roomC) {
		return this.config.heating() //
				? this.config.efficiency().cop(outdoorC, roomC + SUPPLY_OFFSET_K) //
				: this.config.efficiency().eer(outdoorC);
	}

	/**
	 * The outdoor temperature of a slot; the last known value beyond the
	 * forecast.
	 *
	 * @param slot the slot index
	 * @return the temperature in °C
	 */
	double outdoorC(int slot) {
		var outdoor = this.config.outdoorC();
		return outdoor[Math.min(slot, outdoor.length - 1)];
	}

	/**
	 * The room temperature at the end of a slot.
	 *
	 * @param startC   the room temperature at the start, in °C
	 * @param thermalW the average thermal power, heating positive, in W
	 * @param outdoorC the outdoor temperature, in °C
	 * @return the temperature in °C
	 */
	double endTemperature(double startC, double thermalW, double outdoorC) {
		var equilibriumC = outdoorC + thermalW / this.config.transmissionWPerK();
		return equilibriumC + (startC - equilibriumC) * this.decay;
	}

	/**
	 * The average thermal power that brings the room from {@code startC} to
	 * {@code goalC} at the end of one slot (unlimited, either sign).
	 *
	 * @param startC   the room temperature at the start, in °C
	 * @param goalC    the goal, in °C
	 * @param outdoorC the outdoor temperature, in °C
	 * @return the thermal power in W
	 */
	double thermalPowerToReach(double startC, double goalC, double outdoorC) {
		var h = this.config.transmissionWPerK();
		return h * (goalC - outdoorC - (startC - outdoorC) * this.decay) / (1 - this.decay);
	}
}
