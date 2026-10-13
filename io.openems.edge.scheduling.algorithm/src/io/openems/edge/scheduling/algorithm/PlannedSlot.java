package io.openems.edge.scheduling.algorithm;

/**
 * One slot of an {@link ExecutionPlan} — "an array of N slot with mode, and
 * set-point for each slot" — carrying the mode to run, the set-point its
 * physics sought (if any), and the resulting energy use, cost and state.
 *
 * @param <M> the controller's own mode type
 * @param mode           the mode to run this slot
 * @param targetSetpoint the target this mode's physics sought, or
 *                           {@code null} if this mode has none
 * @param energyUsedWh   the resulting energy used this slot
 * @param costEur        this slot's realized cost (energy price × energy
 *                           used, plus deviation, switching and device
 *                           cost) — the same {@link SlotCost} computation
 *                           {@link PlanTableBuilder} used to choose this
 *                           mode in the first place
 * @param endState       the resulting state at the end of this slot
 */
public record PlannedSlot<M>(M mode, Double targetSetpoint, double energyUsedWh, double costEur, double endState) {
}
