package io.openems.edge.controller.scheduling.dp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	/**
	 * A fake producer (PV): reports a forecast and produces as much as it is
	 * allowed — the forecast, clipped to the magnitude of its (negative)
	 * budget.
	 */
	private static final class FakeProducer extends AbstractDummyOpenemsComponent<FakeProducer>
			implements PlanableController {

		private final double[] forecastW;

		FakeProducer(String id, double... forecastW) {
			super(id, OpenemsComponent.ChannelId.values());
			this.forecastW = forecastW;
		}

		@Override
		protected FakeProducer self() {
			return this;
		}

		@Override
		public PlanSummary createPlan(List<TimeSlot> slots) {
			var power = new double[slots.size()];
			for (var i = 0; i < slots.size(); i++) {
				power[i] = -Math.min(this.forecastW[i], Math.abs(slots.get(i).availablePowerW()));
			}
			return new PlanSummary(power, new double[slots.size()]);
		}

		@Override
		public void executePlan(Instant planStart) {
		}

		@Override
		public OptionalInt slackSlots() {
			return OptionalInt.empty();
		}

		@Override
		public Optional<double[]> productionForecastW(int slotCount) {
			return Optional.of(this.forecastW.clone());
		}
	}

	/**
	 * A fake balancing resource (battery): follows whatever residual it is
	 * given — absorbs all surplus, provides all shortfall.
	 */
	private static final class FakeBalancer extends AbstractDummyOpenemsComponent<FakeBalancer>
			implements PlanableController {

		FakeBalancer(String id) {
			super(id, OpenemsComponent.ChannelId.values());
		}

		@Override
		protected FakeBalancer self() {
			return this;
		}

		@Override
		public PlanSummary createPlan(List<TimeSlot> slots) {
			var power = new double[slots.size()];
			for (var i = 0; i < slots.size(); i++) {
				power[i] = slots.get(i).availablePowerW();
			}
			return new PlanSummary(power, new double[slots.size()]);
		}

		@Override
		public void executePlan(Instant planStart) {
		}

		@Override
		public OptionalInt slackSlots() {
			return OptionalInt.empty();
		}

		@Override
		public boolean balancesResidual() {
			return true;
		}
	}

	/**
	 * A fake flexible consumer: needs {@code powerW} for one slot, and runs in
	 * whichever slot its energy costs least.
	 */
	private static final class FakeShifter extends AbstractDummyOpenemsComponent<FakeShifter>
			implements PlanableController {

		private final double powerW;

		FakeShifter(String id, double powerW) {
			super(id, OpenemsComponent.ChannelId.values());
			this.powerW = powerW;
		}

		@Override
		protected FakeShifter self() {
			return this;
		}

		@Override
		public PlanSummary createPlan(List<TimeSlot> slots) {
			var energyWh = this.powerW * TimeSlot.DURATION_HOURS;
			var best = 0;
			for (var i = 1; i < slots.size(); i++) {
				if (slots.get(i).energyCostEur(energyWh) < slots.get(best).energyCostEur(energyWh)) {
					best = i;
				}
			}
			var power = new double[slots.size()];
			power[best] = this.powerW;
			return new PlanSummary(power, new double[slots.size()]);
		}

		@Override
		public void executePlan(Instant planStart) {
		}

		@Override
		public OptionalInt slackSlots() {
			return OptionalInt.empty();
		}
	}

	/**
	 * A fake PV that either produces its full forecast or curtails to a
	 * minimum, whichever costs less at the slot's prices and rest of the site.
	 */
	private static final class FakeCurtailingPv extends AbstractDummyOpenemsComponent<FakeCurtailingPv>
			implements PlanableController {

		private final double[] forecastW;
		private final double minimumW;

		FakeCurtailingPv(String id, double minimumW, double... forecastW) {
			super(id, OpenemsComponent.ChannelId.values());
			this.minimumW = minimumW;
			this.forecastW = forecastW;
		}

		@Override
		protected FakeCurtailingPv self() {
			return this;
		}

		@Override
		public PlanSummary createPlan(List<TimeSlot> slots) {
			var power = new double[slots.size()];
			for (var i = 0; i < slots.size(); i++) {
				var slot = slots.get(i);
				var full = -Math.min(this.forecastW[i], Math.abs(slot.availablePowerW()));
				var curtailed = -Math.min(this.minimumW, -full);
				power[i] = slot.energyCostEur(full * TimeSlot.DURATION_HOURS) <= slot
						.energyCostEur(curtailed * TimeSlot.DURATION_HOURS) ? full : curtailed;
			}
			return new PlanSummary(power, new double[slots.size()]);
		}

		@Override
		public void executePlan(Instant planStart) {
		}

		@Override
		public OptionalInt slackSlots() {
			return OptionalInt.empty();
		}

		@Override
		public Optional<double[]> productionForecastW(int slotCount) {
			return Optional.of(this.forecastW.clone());
		}
	}

	/**
	 * A fake battery: follows the residual it is given, up to a maximum charge
	 * power, and never discharges.
	 */
	private static final class FakeChargingBattery extends AbstractDummyOpenemsComponent<FakeChargingBattery>
			implements PlanableController {

		private final double maxChargeW;

		FakeChargingBattery(String id, double maxChargeW) {
			super(id, OpenemsComponent.ChannelId.values());
			this.maxChargeW = maxChargeW;
		}

		@Override
		protected FakeChargingBattery self() {
			return this;
		}

		@Override
		public PlanSummary createPlan(List<TimeSlot> slots) {
			var power = new double[slots.size()];
			for (var i = 0; i < slots.size(); i++) {
				power[i] = Math.max(0, Math.min(this.maxChargeW, slots.get(i).availablePowerW()));
			}
			return new PlanSummary(power, new double[slots.size()]);
		}

		@Override
		public void executePlan(Instant planStart) {
		}

		@Override
		public OptionalInt slackSlots() {
			return OptionalInt.empty();
		}

		@Override
		public boolean balancesResidual() {
			return true;
		}
	}

	private static Map<String, double[]> runWithBaseLoad(List<PlanableController> resources, double[] capacity,
			double baseLoadW) throws OpenemsNamedException {
		var baseLoad = new double[capacity.length];
		Arrays.fill(baseLoad, baseLoadW);
		return DpArbitration.runDetailed(resources, new double[capacity.length], capacity,
				unlimitedExport(capacity.length), baseLoad, 4, 0.05).budget();
	}

	@Test
	void aBalancingResourceGetsTheSurplusToAbsorb() throws OpenemsNamedException {
		var pv = new FakeProducer("pv0", 4_000);
		var battery = new FakeBalancer("battery0");

		var budget = runWithBaseLoad(List.of(pv, battery), new double[] { 12_000 }, 500);

		assertEquals(3_500, budget.get("battery0")[0], 1e-6, "4kW PV - 0.5kW base load");
	}

	@Test
	void aBalancingResourceGetsTheShortfallToProvide() throws OpenemsNamedException {
		var consumer = new FakeController("boiler0", 1_000, OptionalInt.empty());
		var battery = new FakeBalancer("battery0");

		var budget = runWithBaseLoad(List.of(consumer, battery), new double[] { 12_000 }, 500);

		assertEquals(-1_500, budget.get("battery0")[0], 1e-6, "1kW boiler + 0.5kW base load");
		assertEquals(1_000, budget.get("boiler0")[0], 1e-6);
	}

	@Test
	void aBalancingResourcesSupplyDoesNotAddToTheConsumersCapacity() throws OpenemsNamedException {
		var consumer = new FakeController("boiler0", 5_000, OptionalInt.empty());
		var battery = new FakeBalancer("battery0");

		var budget = runWithBaseLoad(List.of(consumer, battery), new double[] { 2_000 }, 0);

		assertEquals(2_000, budget.get("boiler0")[0], 1e-6, "only the import capacity, not the battery");
		assertEquals(-2_000, budget.get("battery0")[0], 1e-6, "it provides the boiler's share");
	}

	private static double[] unlimitedExport(int slotCount) {
		var out = new double[slotCount];
		Arrays.fill(out, 1_000_000);
		return out;
	}

	@Test
	void producerGetsANegativeBudgetEqualToItsForecastWhenExportIsUnconstrained() throws OpenemsNamedException {
		var pv = new FakeProducer("pv0", 4_000, 0);

		var price = new double[] { 0.30, 0.30 };
		var capacity = new double[] { 12_000, 12_000 };

		var finalBudget = DpArbitration.run(List.of(pv), price, capacity, unlimitedExport(2), 4, 0.05);

		assertEquals(-4_000, finalBudget.get("pv0")[0], 1e-6, "negative budget, bounded by the forecast");
		assertEquals(0, finalBudget.get("pv0")[1], 1e-6, "no forecast production, no budget (night)");
	}

	@Test
	void productionAddsToTheCapacityAvailableToConsumers() throws OpenemsNamedException {
		var pv = new FakeProducer("pv0", 4_000);
		var consumer = new FakeController("boiler0", 5_000, OptionalInt.empty());

		var price = new double[] { 0.30 };
		var capacity = new double[] { 2_000 }; // too little import for the consumer on its own

		var finalBudget = DpArbitration.run(List.of(pv, consumer), price, capacity, unlimitedExport(1), 4,
				0.05);

		assertEquals(5_000, finalBudget.get("boiler0")[0], 1e-6, "2kW import + 4kW PV covers the 5kW request");
		assertEquals(-4_000, finalBudget.get("pv0")[0], 1e-6);
	}

	@Test
	void producerIsCurtailedOnlyAsFarAsTheExportCapacityRequires() throws OpenemsNamedException {
		var pv = new FakeProducer("pv0", 4_000);
		var consumer = new FakeController("boiler0", 500, OptionalInt.empty());

		var price = new double[] { 0.30 };
		var capacity = new double[] { 12_000 };
		var exportCapacity = new double[] { 1_000 };

		var finalBudget = DpArbitration.run(List.of(pv, consumer), price, capacity, exportCapacity, 4, 0.05);

		assertEquals(-1_500, finalBudget.get("pv0")[0], 1e-6, "1kW export + 0.5kW self-consumption");
		assertEquals(500, finalBudget.get("boiler0")[0], 1e-6);
	}

	@Test
	void exportHeadroomIsSharedAcrossProducersInRegistrationOrder() throws OpenemsNamedException {
		var pvRoof = new FakeProducer("pv0", 3_000);
		var pvShed = new FakeProducer("pv1", 3_000);

		var price = new double[] { 0.30 };
		var capacity = new double[] { 12_000 };
		var exportCapacity = new double[] { 4_000 };

		var finalBudget = DpArbitration.run(List.of(pvRoof, pvShed), price, capacity, exportCapacity, 4,
				0.05);

		assertEquals(-3_000, finalBudget.get("pv0")[0], 1e-6);
		assertEquals(-1_000, finalBudget.get("pv1")[0], 1e-6);
	}

	@Test
	void tier1UrgentResourceGetsPriorityOverTier2WhenCapacityIsTight() throws OpenemsNamedException {
		var urgent = new FakeController("urgent0", 10_000, OptionalInt.of(0));
		var opportunistic = new FakeController("opportunistic0", 10_000, OptionalInt.empty());

		var price = new double[] { 0.30 };
		var capacity = new double[] { 12_000 }; // not enough for both full 10kW requests

		var finalBudget = DpArbitration.run(List.of(urgent, opportunistic), price, capacity,
				unlimitedExport(price.length), 4, 0.05);

		assertEquals(10_000, finalBudget.get("urgent0")[0], 1e-6, "Tier-1 gets its full request first");
		assertEquals(2_000, finalBudget.get("opportunistic0")[0], 1e-6, "Tier-2 gets only what's left");
	}

	@Test
	void negativePowerRequestsAreAlwaysGrantedInFullRegardlessOfCapacity() throws OpenemsNamedException {
		var discharging = new FakeController("battery0", -5_000, OptionalInt.empty());

		var price = new double[] { 0.30 };
		var capacity = new double[] { 100 }; // tiny — irrelevant to a supplying resource

		var finalBudget = DpArbitration.run(List.of(discharging), price, capacity,
				unlimitedExport(price.length), 4, 0.05);

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

		var finalBudget = DpArbitration.run(List.of(solo), price, capacity,
				unlimitedExport(price.length), 4, 0.05);

		assertEquals(3_000, finalBudget.get("solo0")[0], 1e-6);
		assertEquals(3_000, finalBudget.get("solo0")[1], 1e-6);
	}

	@Test
	void eachResourceGetsBothPricesAndTheRestOfTheSitesGridPower() throws OpenemsNamedException {
		var pv = new FakeProducer("pv0", 2_000, 0);
		var boiler = new FakeController("boiler0", 1_000, OptionalInt.empty());
		var baseLoad = new double[] { 500, 500 };

		DpArbitration.runDetailed(List.of(pv, boiler), new double[] { 0.30, 0.20 }, new double[] { 0.05, 0.04 },
				new double[] { 12_000, 12_000 }, unlimitedExport(2), baseLoad, 4, 0.05);

		var slots = boiler.lastSlots;
		assertEquals(0.30, slots.get(0).buyPriceEurPerKwh(), 1e-9);
		assertEquals(0.05, slots.get(0).sellPriceEurPerKwh(), 1e-9);
		assertEquals(500 - 2_000, slots.get(0).restOfSiteW(), 1e-6, "base load minus PV, without itself");
		assertEquals(500, slots.get(1).restOfSiteW(), 1e-6);
	}

	@Test
	void aFlexibleConsumerMovesToThePvSurplusAlthoughTheBuyPriceIsHigherThere() throws OpenemsNamedException {
		// Slot 0: 2 kW PV surplus, buy 0.30, sell 0.05. Slot 1: no PV, buy 0.20.
		// At one price per slot it would run in slot 1; costed by the change
		// to the site's bill, running on the surplus costs only 0.05.
		var pv = new FakeProducer("pv0", 2_000, 0);
		var boiler = new FakeShifter("boiler0", 1_000);

		var result = DpArbitration.runDetailed(List.of(pv, boiler), new double[] { 0.30, 0.20 },
				new double[] { 0.05, 0.05 }, new double[] { 12_000, 12_000 }, unlimitedExport(2), new double[2], 4,
				0.05);

		var plan = result.plans().get("boiler0").powerPerSlotW();
		assertEquals(1_000, plan[0], 1e-6);
		assertEquals(0, plan[1], 1e-6);
	}

	@Test
	void withOnePriceTheConsumerStillPicksTheCheaperSlot() throws OpenemsNamedException {
		var pv = new FakeProducer("pv0", 2_000, 0);
		var boiler = new FakeShifter("boiler0", 1_000);

		var result = DpArbitration.runDetailed(List.of(pv, boiler), new double[] { 0.30, 0.20 },
				new double[] { 12_000, 12_000 }, unlimitedExport(2), new double[2], 4, 0.05);

		// Same price for buying and selling: PV's surplus is worth 0.30 exported,
		// so using it costs 0.30 too, and slot 1 is cheaper.
		assertEquals(1_000, result.plans().get("boiler0").powerPerSlotW()[1], 1e-6);
	}

	@Test
	void pvKeepsProducingWhatTheBatteryAbsorbsAtANegativeSellPrice() throws OpenemsNamedException {
		// 3.6 kW PV, 0.5 kW base load, a battery taking up to 2.5 kW, selling
		// costs money. Curtailing would only make sense for the 0.6 kW left over;
		// against an idle battery, PV would curtail everything.
		var pv = new FakeCurtailingPv("pv0", 200, 3_600);
		var battery = new FakeChargingBattery("battery0", 2_500);

		var result = DpArbitration.runDetailed(List.of(pv, battery), new double[] { 0.10 }, new double[] { -0.025 },
				new double[] { 12_000 }, unlimitedExport(1), new double[] { 500 }, 6, 0.05);

		assertEquals(-3_600, result.plans().get("pv0").powerPerSlotW()[0], 1e-6);
		assertEquals(2_500, result.plans().get("battery0").powerPerSlotW()[0], 1e-6);
	}

	@Test
	void pvCurtailsWhenNobodyTakesItsSurplusAtANegativeSellPrice() throws OpenemsNamedException {
		var pv = new FakeCurtailingPv("pv0", 200, 3_600);
		var fullBattery = new FakeChargingBattery("battery0", 0);

		var result = DpArbitration.runDetailed(List.of(pv, fullBattery), new double[] { 0.10 },
				new double[] { -0.025 }, new double[] { 12_000 }, unlimitedExport(1), new double[] { 500 }, 6, 0.05);

		assertEquals(-200, result.plans().get("pv0").powerPerSlotW()[0], 1e-6);
	}
}
