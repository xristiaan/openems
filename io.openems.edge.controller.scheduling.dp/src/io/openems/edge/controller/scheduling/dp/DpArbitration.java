package io.openems.edge.controller.scheduling.dp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.scheduling.algorithm.TimeSlot;
import io.openems.edge.scheduling.api.PlanSummary;
import io.openems.edge.scheduling.api.PlanableController;

/**
 * The DP Controller's own allocation algorithm: Tier-1 (Least-Slack-Time
 * ordered)/Tier-2 greedy arbitration, repeated as an iterative water-filling
 * loop. Plain Java over the {@link PlanableController} contract, so it's
 * testable with fake resources — no real OSGi runtime or real hardware
 * needed to verify the arbitration logic itself.
 *
 * <p>
 * Per slot: Tier-1 resources (a genuine deadline/next-target — see
 * {@link PlanableController#slackSlots()}), ordered by ascending slack
 * (least slack = most urgent = served first), each get their full request
 * up to whatever capacity remains; Tier-2 resources (no deadline,
 * opportunistic) get whatever's left over, in registration order for now —
 * marginal-cost-based Tier-2 ordering is a known, logged post-MVP
 * refinement. A resource supplying power back (a negative request —
 * discharging, PV production) is always granted in full; it doesn't
 * compete for import capacity.
 */
final class DpArbitration {

	private DpArbitration() {
	}

	/**
	 * Runs the Round 0 / iterate-until-convergence loop described above.
	 *
	 * @param resources               every registered Plan-able Controller
	 * @param priceEurPerKwh          one entry per slot: the forecast energy
	 *                                    price
	 * @param availableCapacityW      one entry per slot: the total import
	 *                                    capacity available to share
	 * @param maxRounds               n: the hard cap on rounds
	 * @param convergenceThresholdFraction y: stop early once no resource's
	 *                                    budget changes by more than this
	 *                                    fraction between rounds
	 * @return each resource's final, converged per-slot budget — by the
	 *             time this returns, every resource's own retained plan
	 *             (from its last {@code createPlan} call) already reflects
	 *             it
	 * @throws OpenemsNamedException if any resource's {@code createPlan}
	 *                                   call fails
	 */
	static Map<String, double[]> run(List<PlanableController> resources, double[] priceEurPerKwh,
			double[] availableCapacityW, int maxRounds, double convergenceThresholdFraction)
			throws OpenemsNamedException {
		var slotCount = priceEurPerKwh.length;
		var budget = initialBudget(resources, availableCapacityW, slotCount);

		for (var round = 0; round < maxRounds; round++) {
			var requested = createPlans(resources, priceEurPerKwh, budget);
			var nextBudget = arbitrate(resources, requested, availableCapacityW, slotCount);
			var converged = hasConverged(budget, nextBudget, convergenceThresholdFraction);
			budget = nextBudget;
			if (converged) {
				break;
			}
		}

		// One final pass so every resource's retained plan matches the
		// budget this method is about to return, even when convergence (or
		// the round cap) was reached on the last round's own arbitration
		// output rather than its createPlan input.
		createPlans(resources, priceEurPerKwh, budget);

		return budget;
	}

	private static Map<String, double[]> initialBudget(List<PlanableController> resources,
			double[] availableCapacityW, int slotCount) {
		var budget = new LinkedHashMap<String, double[]>();
		for (var resource : resources) {
			budget.put(resource.id(), availableCapacityW.clone());
		}
		return budget;
	}

	private static Map<String, PlanSummary> createPlans(List<PlanableController> resources, double[] priceEurPerKwh,
			Map<String, double[]> budget) throws OpenemsNamedException {
		var requested = new LinkedHashMap<String, PlanSummary>();
		for (var resource : resources) {
			var slots = toTimeSlots(priceEurPerKwh, budget.get(resource.id()));
			requested.put(resource.id(), resource.createPlan(slots));
		}
		return requested;
	}

	private static List<TimeSlot> toTimeSlots(double[] priceEurPerKwh, double[] availablePowerW) {
		var slots = new ArrayList<TimeSlot>(priceEurPerKwh.length);
		for (var i = 0; i < priceEurPerKwh.length; i++) {
			slots.add(new TimeSlot(priceEurPerKwh[i], availablePowerW[i]));
		}
		return slots;
	}

	private static Map<String, double[]> arbitrate(List<PlanableController> resources,
			Map<String, PlanSummary> requested, double[] availableCapacityW, int slotCount) {
		var tier1 = resources.stream() //
				.filter(resource -> resource.slackSlots().isPresent()) //
				.sorted(Comparator.comparingInt(resource -> resource.slackSlots().getAsInt())) //
				.toList();
		var tier2 = resources.stream() //
				.filter(resource -> resource.slackSlots().isEmpty()) //
				.toList();

		var nextBudget = new LinkedHashMap<String, double[]>();
		for (var resource : resources) {
			nextBudget.put(resource.id(), new double[slotCount]);
		}

		for (var slot = 0; slot < slotCount; slot++) {
			var remaining = availableCapacityW[slot];
			for (var resource : tier1) {
				remaining = allocateOne(resource, requested, nextBudget, slot, remaining, availableCapacityW[slot]);
			}
			for (var resource : tier2) {
				remaining = allocateOne(resource, requested, nextBudget, slot, remaining, availableCapacityW[slot]);
			}
		}
		return nextBudget;
	}

	private static double allocateOne(PlanableController resource, Map<String, PlanSummary> requested,
			Map<String, double[]> nextBudget, int slot, double remaining, double fullCapacityW) {
		var requestedPowerW = requested.get(resource.id()).powerPerSlotW()[slot];
		if (requestedPowerW <= 0) {
			// Supplying power back doesn't compete for import capacity —
			// re-offer the full ceiling as next round's budget, unconstrained.
			nextBudget.get(resource.id())[slot] = fullCapacityW;
			return remaining;
		}
		var grant = Math.min(requestedPowerW, Math.max(0, remaining));
		nextBudget.get(resource.id())[slot] = grant;
		return remaining - grant;
	}

	private static boolean hasConverged(Map<String, double[]> previous, Map<String, double[]> next,
			double thresholdFraction) {
		for (var id : previous.keySet()) {
			var a = previous.get(id);
			var b = next.get(id);
			for (var i = 0; i < a.length; i++) {
				var denominator = Math.max(Math.abs(a[i]), 1e-6);
				if (Math.abs(a[i] - b[i]) / denominator > thresholdFraction) {
					return false;
				}
			}
		}
		return true;
	}
}
