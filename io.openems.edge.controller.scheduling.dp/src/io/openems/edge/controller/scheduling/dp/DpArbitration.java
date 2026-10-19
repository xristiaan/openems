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
 * refinement.
 *
 * <p>
 * Supply: any power a resource requests to supply back (a negative request —
 * discharging, PV production) adds to the capacity consumers can draw in
 * that slot, since it offsets grid import. A non-producer supplying back
 * (battery discharge) is always re-offered its full ceiling. A producer (a
 * resource reporting {@link PlanableController#productionForecastW(int)})
 * instead gets a <em>negative</em> budget, bounded by its forecast and
 * reduced only as far as the export capacity requires: export headroom is
 * the export capacity plus whatever consumers were granted, minus any other
 * supply, shared across producers in registration order.
 *
 * <p>
 * Balancing: a resource that {@link PlanableController#balancesResidual()
 * balances the residual} (a battery) takes no part in the above. It comes
 * last, with the signed residual of all other plans and the base load as its
 * budget — surplus to absorb, or shortfall to provide — and its own supply
 * or consumption doesn't change the other resources' budgets. So it never
 * charges from the grid: it only picks up slack.
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
	 * @param availableExportCapacityW one entry per slot: the total export
	 *                                    capacity, as a positive magnitude —
	 *                                    how much net production may flow
	 *                                    back to the grid
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
			double[] availableCapacityW, double[] availableExportCapacityW, int maxRounds,
			double convergenceThresholdFraction) throws OpenemsNamedException {
		return runDetailed(resources, priceEurPerKwh, availableCapacityW, availableExportCapacityW,
				new double[priceEurPerKwh.length], maxRounds, convergenceThresholdFraction).budget();
	}

	/**
	 * The outcome of one arbitration.
	 *
	 * @param budget each resource's final per-slot budget, by Component-ID
	 * @param plans  each resource's final plan (its last {@code createPlan}
	 *               result, made with that budget), by Component-ID
	 * @param rounds the number of arbitration rounds run
	 */
	record Result(Map<String, double[]> budget, Map<String, PlanSummary> plans, int rounds) {
	}

	/**
	 * Same as {@link #run}, but also returns every resource's final plan, and
	 * takes the base load the residual for balancing resources includes.
	 *
	 * @param resources                    see {@link #run}
	 * @param priceEurPerKwh               see {@link #run}
	 * @param availableCapacityW           see {@link #run}
	 * @param availableExportCapacityW     see {@link #run}
	 * @param baseLoadW                    one entry per slot: the flat base
	 *                                         load, part of the residual a
	 *                                         balancing resource picks up
	 * @param maxRounds                    see {@link #run}
	 * @param convergenceThresholdFraction see {@link #run}
	 * @return the {@link Result}
	 * @throws OpenemsNamedException see {@link #run}
	 */
	static Result runDetailed(List<PlanableController> resources, double[] priceEurPerKwh,
			double[] availableCapacityW, double[] availableExportCapacityW, double[] baseLoadW, int maxRounds,
			double convergenceThresholdFraction) throws OpenemsNamedException {
		var slotCount = priceEurPerKwh.length;
		var forecasts = productionForecasts(resources, slotCount);
		var budget = initialBudget(resources, forecasts, availableCapacityW, baseLoadW);

		var rounds = 0;
		for (var round = 0; round < maxRounds; round++) {
			rounds++;
			var requested = createPlans(resources, priceEurPerKwh, budget);
			var nextBudget = arbitrate(resources, forecasts, requested, availableCapacityW,
					availableExportCapacityW, baseLoadW, slotCount);
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
		var plans = createPlans(resources, priceEurPerKwh, budget);

		return new Result(budget, plans, rounds);
	}

	private static Map<String, double[]> productionForecasts(List<PlanableController> resources, int slotCount) {
		var forecasts = new LinkedHashMap<String, double[]>();
		for (var resource : resources) {
			resource.productionForecastW(slotCount).ifPresent(forecast -> {
				if (forecast.length != slotCount) {
					throw new IllegalArgumentException(
							"Production forecast of " + resource.id() + " must have " + slotCount + " slots");
				}
				forecasts.put(resource.id(), forecast.clone());
			});
		}
		return forecasts;
	}

	private static Map<String, double[]> initialBudget(List<PlanableController> resources,
			Map<String, double[]> forecasts, double[] availableCapacityW, double[] baseLoadW) {
		// Round 0 is optimistic for consumers: import capacity plus all
		// forecast production, so a consumer can ask for PV-backed power at
		// all — its later requests are clipped to whatever it was offered.
		var consumerCeilingW = availableCapacityW.clone();
		for (var forecast : forecasts.values()) {
			for (var i = 0; i < consumerCeilingW.length; i++) {
				consumerCeilingW[i] += Math.max(0, forecast[i]);
			}
		}
		// A balancing resource starts from the residual of forecast production
		// and base load alone; consumers' plans are added from round 1 on.
		var residualW = new double[availableCapacityW.length];
		for (var i = 0; i < residualW.length; i++) {
			residualW[i] = -baseLoadW[i];
			for (var forecast : forecasts.values()) {
				residualW[i] += Math.max(0, forecast[i]);
			}
		}
		var budget = new LinkedHashMap<String, double[]>();
		for (var resource : resources) {
			var forecast = forecasts.get(resource.id());
			if (resource.balancesResidual()) {
				budget.put(resource.id(), residualW.clone());
			} else {
				budget.put(resource.id(), forecast == null ? consumerCeilingW.clone() : negated(forecast));
			}
		}
		return budget;
	}

	private static double[] negated(double[] magnitudes) {
		var out = new double[magnitudes.length];
		for (var i = 0; i < magnitudes.length; i++) {
			out[i] = -Math.max(0, magnitudes[i]);
		}
		return out;
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
			Map<String, double[]> forecasts, Map<String, PlanSummary> requested, double[] availableCapacityW,
			double[] availableExportCapacityW, double[] baseLoadW, int slotCount) {
		var balancing = resources.stream() //
				.filter(PlanableController::balancesResidual) //
				.toList();
		var producers = resources.stream() //
				.filter(resource -> !resource.balancesResidual()) //
				.filter(resource -> forecasts.containsKey(resource.id())) //
				.toList();
		var others = resources.stream() //
				.filter(resource -> !resource.balancesResidual()) //
				.filter(resource -> !forecasts.containsKey(resource.id())) //
				.toList();
		var tier1 = others.stream() //
				.filter(resource -> resource.slackSlots().isPresent()) //
				.sorted(Comparator.comparingInt(resource -> resource.slackSlots().getAsInt())) //
				.toList();
		var tier2 = others.stream() //
				.filter(resource -> resource.slackSlots().isEmpty()) //
				.toList();

		var nextBudget = new LinkedHashMap<String, double[]>();
		for (var resource : resources) {
			nextBudget.put(resource.id(), new double[slotCount]);
		}

		for (var slot = 0; slot < slotCount; slot++) {
			var producedW = 0.0;
			for (var resource : producers) {
				producedW += supplyOf(requested, resource, slot);
			}
			var otherSupplyW = 0.0;
			for (var resource : others) {
				otherSupplyW += supplyOf(requested, resource, slot);
			}

			// Consumers: import capacity, plus whatever is being supplied
			// locally in the same slot.
			var start = availableCapacityW[slot] + producedW + otherSupplyW;
			var remaining = start;
			for (var resource : tier1) {
				remaining = allocateOne(resource, requested, nextBudget, slot, remaining, availableCapacityW[slot]);
			}
			for (var resource : tier2) {
				remaining = allocateOne(resource, requested, nextBudget, slot, remaining, availableCapacityW[slot]);
			}
			var consumedW = start - remaining;

			// Producers: bounded by their forecast, curtailed only as far as
			// the export capacity requires.
			var exportHeadroomW = availableExportCapacityW[slot] + consumedW - otherSupplyW;
			for (var resource : producers) {
				var grant = Math.min(Math.max(0, forecasts.get(resource.id())[slot]), Math.max(0, exportHeadroomW));
				nextBudget.get(resource.id())[slot] = -grant;
				exportHeadroomW -= grant;
			}

			// Balancing resources, last: the signed residual of everyone
			// else's plan and the base load — positive surplus to absorb,
			// negative shortfall to provide — shared in registration order.
			var residualW = -baseLoadW[slot];
			for (var resource : producers) {
				residualW -= requested.get(resource.id()).powerPerSlotW()[slot];
			}
			for (var resource : others) {
				residualW -= requested.get(resource.id()).powerPerSlotW()[slot];
			}
			for (var resource : balancing) {
				nextBudget.get(resource.id())[slot] = residualW;
				residualW -= requested.get(resource.id()).powerPerSlotW()[slot];
			}
		}
		return nextBudget;
	}

	private static double supplyOf(Map<String, PlanSummary> requested, PlanableController resource, int slot) {
		return Math.max(0, -requested.get(resource.id()).powerPerSlotW()[slot]);
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
