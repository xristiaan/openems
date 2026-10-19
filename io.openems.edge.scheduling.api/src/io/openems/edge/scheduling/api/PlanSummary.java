package io.openems.edge.scheduling.api;

/**
 * The type-erased result of a Plan-able Controller's {@code createPlan} —
 * the DP Controller doing cross-resource arbitration never needs to know a
 * resource's own Mode type, only what running its plan would cost it in
 * power and money, slot by slot.
 *
 * @param powerPerSlotW    this plan's power draw each slot, in watts —
 *                             consumption-positive (charging, heating),
 *                             negative when the resource supplies power
 *                             back instead (discharging, PV production);
 *                             the same sign convention the power-budget
 *                             inputs already use
 * @param costPerSlotEur   this plan's own realized cost each slot, in euro
 * @param modePerSlot      the planned Mode's name each slot, for
 *                             diagnostics only (e.g. plan logging); null if
 *                             the resource doesn't report it
 * @param endStatePerSlot  the planned state at the end of each slot, in the
 *                             resource's own state unit (e.g. SoC as a
 *                             fraction), for diagnostics only; null for a
 *                             stateless resource or if not reported
 */
public record PlanSummary(double[] powerPerSlotW, double[] costPerSlotEur, String[] modePerSlot,
		double[] endStatePerSlot) {

	/**
	 * A summary without the diagnostic per-slot mode and state.
	 *
	 * @param powerPerSlotW  see {@link PlanSummary}
	 * @param costPerSlotEur see {@link PlanSummary}
	 */
	public PlanSummary(double[] powerPerSlotW, double[] costPerSlotEur) {
		this(powerPerSlotW, costPerSlotEur, null, null);
	}
}
