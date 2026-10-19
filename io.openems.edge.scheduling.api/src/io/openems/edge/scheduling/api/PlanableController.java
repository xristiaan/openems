package io.openems.edge.scheduling.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.scheduling.algorithm.TimeSlot;

/**
 * The contract the DP Controller calls every Plan-able Controller through,
 * regardless of device type or which internal Mode enum it happens to use.
 * Deliberately non-generic: the DP Controller never needs a resource's own
 * Mode type, only the power/cost consequences of its plan ({@link PlanSummary}) —
 * the same "DP Controller never sees the breakdown" principle already
 * applied to {@code deviceCostEur}.
 */
public interface PlanableController extends OpenemsComponent {

	/**
	 * Create Plan: builds (and internally retains) an execution plan against
	 * the given slots, starting from this resource's real current state.
	 * Does not commit it — see {@link #executePlan}.
	 *
	 * <p>
	 * Deliberately takes no target/schedule argument: "normally it will be
	 * known by the controller... In that case it is encapsulated in the
	 * controller" — the DP Controller doing cross-resource arbitration has
	 * no business knowing a battery's SoC band or a comfort zone's schedule;
	 * each concrete controller resolves its own target internally.
	 *
	 * @param slots one entry per slot: energy price and the power budget
	 *                  assigned to this resource for this planning pass
	 * @return the type-erased power/cost consequence of this plan
	 * @throws OpenemsNamedException if this resource's current state can't
	 *                                   be read
	 */
	PlanSummary createPlan(List<TimeSlot> slots) throws OpenemsNamedException;

	/**
	 * Execute Plan: commits whichever plan the last {@link #createPlan} call
	 * computed, to be run slot by slot starting at {@code planStart}.
	 *
	 * @param planStart the absolute time slot 0 of the committed plan begins
	 */
	void executePlan(Instant planStart);

	/**
	 * Least Slack Time: how many slots of headroom this resource has before
	 * it must start an urgent catch-up run to still meet its own next
	 * deadline/target — see the DP Controller's Tier-1 ordering. Resources
	 * with no genuine deadline (a comfort-band-only resource like a battery
	 * kept within a SoC range, with nothing forcing it to a specific value
	 * by a specific time) report empty, and are never Tier-1-urgent —
	 * they're arbitrated opportunistically (Tier-2) instead.
	 *
	 * @return the slack in slots, or empty if this resource has no deadline
	 */
	OptionalInt slackSlots();

	/**
	 * Production forecast: for a producing resource (PV), the maximum power it
	 * could produce each slot given the forecast, in watts, as positive
	 * magnitudes. A resource that reports one is arbitrated as a producer: it
	 * gets a <em>negative</em> per-slot budget ("produce at most this much"),
	 * bounded by this forecast and reduced only when the grid export capacity
	 * requires it, and its production adds to the import capacity available
	 * to consuming resources in the same slot.
	 *
	 * <p>
	 * Consuming and balancing resources (a battery) report empty.
	 *
	 * @param slotCount the number of slots being planned
	 * @return the forecast, one entry per slot, or empty if this resource is
	 *             not a producer
	 */
	default Optional<double[]> productionForecastW(int slotCount) {
		return Optional.empty();
	}

	/**
	 * Whether this resource balances the plan: it picks up the slack other
	 * resources leave. A balancing resource (a battery) is arbitrated last,
	 * and its budget each slot is the signed residual of everyone else's plan
	 * plus the base load: positive is surplus it may absorb (charge),
	 * negative is shortfall it may provide (discharge). The sign sets the
	 * direction; its own planning only decides whether and how (its mode) to
	 * follow it.
	 *
	 * @return true for a balancing resource
	 */
	default boolean balancesResidual() {
		return false;
	}
}
