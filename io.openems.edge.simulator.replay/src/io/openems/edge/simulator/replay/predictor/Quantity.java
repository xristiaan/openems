package io.openems.edge.simulator.replay.predictor;

/**
 * What a {@link PredictorReplayImpl} predicts from the recorded test days.
 */
public enum Quantity {
	/** PV production in W, from the recorded PV forecast. */
	PV_POWER,
	/**
	 * The outdoor temperature in deci-degrees Celsius, from the recorded
	 * temperature forecast.
	 */
	OUTDOOR_TEMPERATURE;
}
