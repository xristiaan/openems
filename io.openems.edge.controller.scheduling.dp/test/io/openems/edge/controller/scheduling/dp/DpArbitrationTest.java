package io.openems.edge.controller.scheduling.dp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.scheduling.algorithm.TimeSlot;
import io.openems.edge.scheduling.api.PlanSummary;
import io.openems.edge.scheduling.api.PlanableController;

class DpArbitrationTest {

	/**
	 * A fake resource that requests a constant power every slot (clipped to
	 * whatever budget it's actually given), for exercising the arbitration
	 * logic alone — no real physics, no real OSGi runtime needed.
	 */
	private static final class FakeController extends AbstractDummyOpenemsComponent<FakeController>
			implements PlanableController {

		private final double requestedPowerPerSlotW;
		private final OptionalInt slack;
		private List<TimeSlot> lastSlots;

		FakeController(String id, double requestedPowerPerSlotW, OptionalInt slack) {
			super(id, OpenemsComponent.ChannelId.values());
			this.requestedPowerPerSlotW = requestedPowerPerSlotW;
			this.slack = slack;
		}

		@Override
		protected FakeController self() {
			return this;
		}

		@Override
		public PlanSummary createPlan(List<TimeSlot> slots) {
			this.lastSlots = slots;
			var power = new double[slots.size()];
			var cost = new double[slots.size()];
			for (var i = 0; i < slots.size(); i++) {
				// A consuming request is clipped to the budget, same as any
				// real resource would be; a supplying (negative) request is
				// left as-is — arbitration itself decides whether import
				// capacity constrains it, not this fake.
				var budget = slots.get(i).availablePowerW();
				power[i] = this.requestedPowerPerSlotW > 0 ? Math.min(this.requestedPowerPerSlotW, budget)
						: this.requestedPowerPerSlotW;
			}
			return new PlanSummary(power, cost);
		}

		@Override
		public void executePlan(Instant planStart) {
			// Nothing to verify here — DpArbitration's own tests only care
			// about the returned budget, not execution.
		}

		@Override
		public OptionalInt slackSlots() {
			return this.slack;
		}
	}

	@Test
	void tier1UrgentResourceGetsPriorityOverTier2WhenCapacityIsTight() throws OpenemsNamedException {
		var urgent = new FakeController("urgent0", 10_000, OptionalInt.of(0));
		var opportunistic = new FakeController("opportunistic0", 10_000, OptionalInt.empty());

		var price = new double[] { 0.30 };
		var capacity = new double[] { 12_000 }; // not enough for both full 10kW requests

		var finalBudget = DpArbitration.run(List.of(urgent, opportunistic), price, capacity, 4, 0.05);

		assertEquals(10_000, finalBudget.get("urgent0")[0], 1e-6, "Tier-1 gets its full request first");
		assertEquals(2_000, finalBudget.get("opportunistic0")[0], 1e-6, "Tier-2 gets only what's left");
	}

	@Test
	void negativePowerRequestsAreAlwaysGrantedInFullRegardlessOfCapacity() throws OpenemsNamedException {
		var discharging = new FakeController("battery0", -5_000, OptionalInt.empty());

		var price = new double[] { 0.30 };
		var capacity = new double[] { 100 }; // tiny — irrelevant to a supplying resource

		var finalBudget = DpArbitration.run(List.of(discharging), price, capacity, 4, 0.05);

		// The returned value is next round's budget ceiling, not the
		// granted power itself — confirmed unconstrained by the tiny
		// capacity, since supplying power never competes for it.
		assertEquals(100, finalBudget.get("battery0")[0], 1e-6);
	}

	@Test
	void convergesWithNoContentionAcrossMultipleSlots() throws OpenemsNamedException {
		var solo = new FakeController("solo0", 3_000, OptionalInt.empty());

		var price = new double[] { 0.30, 0.30 };
		var capacity = new double[] { 12_000, 12_000 };

		var finalBudget = DpArbitration.run(List.of(solo), price, capacity, 4, 0.05);

		assertEquals(3_000, finalBudget.get("solo0")[0], 1e-6);
		assertEquals(3_000, finalBudget.get("solo0")[1], 1e-6);
	}
}
