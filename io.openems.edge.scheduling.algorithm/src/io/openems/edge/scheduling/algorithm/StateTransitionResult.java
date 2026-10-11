package io.openems.edge.scheduling.algorithm;

/**
 * The State-Transition Function's result: "calculates the end state and
 * energy used within one time slot."
 *
 * @param energyUsedWh the energy actually used/produced during the slot
 *                         (negative for a net-producing resource, e.g. PV)
 * @param nextState    the resulting state at the end of the slot
 */
public record StateTransitionResult(double energyUsedWh, double nextState) {
}
