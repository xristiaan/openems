package io.openems.edge.scheduling.algorithm;

/**
 * The State-Transition Function's result: "calculates the end state and
 * energy used within one time slot."
 *
 * @param energyUsedWh the energy actually used/produced during the slot
 *                         (negative for a net-producing resource, e.g. PV)
 * @param nextState    the resulting state at the end of the slot
 * @param deviceCostEur any non-energy cost incurred by running this mode
 *                         this slot — wear/degradation, for example — added
 *                         to the energy/deviation/switching cost the engine
 *                         already computes itself. Most devices have none;
 *                         see the convenience constructor below.
 */
public record StateTransitionResult(double energyUsedWh, double nextState, double deviceCostEur) {

	/**
	 * Convenience constructor for the common case of no device cost.
	 *
	 * @param energyUsedWh the energy actually used/produced during the slot
	 * @param nextState    the resulting state at the end of the slot
	 */
	public StateTransitionResult(double energyUsedWh, double nextState) {
		this(energyUsedWh, nextState, 0);
	}
}
