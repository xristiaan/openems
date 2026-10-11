package io.openems.edge.scheduling.algorithm;

import java.util.List;

/**
 * "An execution plan (an array of N slot with mode, and set-point for each
 * slot)" — the output of Create Plan, consumed by Execute Plan.
 *
 * @param <M> the controller's own mode type
 * @param slots one {@link PlannedSlot} per slot, in order
 */
public record ExecutionPlan<M>(List<PlannedSlot<M>> slots) {
}
