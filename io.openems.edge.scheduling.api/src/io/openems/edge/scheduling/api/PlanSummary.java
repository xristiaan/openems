package io.openems.edge.scheduling.api;

/**
 * The type-erased result of a Plan-able Controller's {@code createPlan} —
 * the DP Controller doing cross-resource arbitration never needs to know a
 * resource's own Mode type, only what running its plan would cost it in
 * power and money, slot by slot.
 *
 * @param powerPerSlotW   this plan's power draw each slot, in watts —
 *                            consumption-positive (charging, heating),
 *                            negative when the resource supplies power back
 *                            instead (discharging, PV production); the
 *                            same sign convention the power-budget inputs
 *                            already use
 * @param costPerSlotEur  this plan's own realized cost each slot, in euro
 */
public record PlanSummary(double[] powerPerSlotW, double[] costPerSlotEur) {
}
