package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.hvac.api.HvacMode;
import io.openems.edge.scheduling.algorithm.ExecutionPlan;
import io.openems.edge.scheduling.algorithm.ExecutionPlanCalculator;
import io.openems.edge.scheduling.algorithm.PlanTableBuilder;
import io.openems.edge.scheduling.algorithm.TimeSlot;

class ZonePlanningTest {

	private static final int SLOTS = 96;
	private static final Efficiency HEAT_PUMP = Efficiency.parse("7/25=4.0, 2/25=3.2, -7/25=2.4", "35=3.2, 27=4.4");
	private static final ZonePlanning.DeviationCost DEFAULT_COST = new ZonePlanning.DeviationCost(0.10, 0.10, 0.05,
			0.05);

	private static ZonePlanning planning(HvacMode mode, double outdoorC, double targetC, Double setpointC) {
		var outdoor = new double[SLOTS];
		Arrays.fill(outdoor, outdoorC);
		var schedule = new double[SLOTS];
		Arrays.fill(schedule, targetC);
		var physics = new ZonePhysicsConfig(mode, 1.8, 50, 1000, 2000, 5, 30, HEAT_PUMP, outdoor);
		var matrix = new ZoneModeSwitchingMatrix(DeviceType.MODULATING, 0, 1, 1);
		return new ZonePlanning(physics, matrix, 1.5, DEFAULT_COST, 0.5, setpointC, schedule);
	}

	private static List<TimeSlot> prices(double... perQuarterOfTheDay) {
		var slots = new ArrayList<TimeSlot>();
		for (var i = 0; i < SLOTS; i++) {
			slots.add(new TimeSlot(perQuarterOfTheDay[i * perQuarterOfTheDay.length / SLOTS], Double.MAX_VALUE));
		}
		return slots;
	}

	private static ExecutionPlan<ComfortZoneMode> plan(ZonePlanning planning, List<TimeSlot> slots, double startC) {
		var targets = planning.targets(slots.size());
		var table = PlanTableBuilder.build(planning.model(slots), slots, targets);
		return ExecutionPlanCalculator.walk(table, slots, targets, startC, ComfortZoneMode.OFF, 0);
	}

	private static double energyWh(ExecutionPlan<ComfortZoneMode> plan, int from, int to) {
		var sum = 0.0;
		for (var i = from; i < to; i++) {
			sum += plan.slots().get(i).energyUsedWh();
		}
		return sum;
	}

	@Test
	void atAFlatPriceTheRoomStaysAtTheTarget() {
		var plan = plan(planning(HvacMode.HEAT, 5, 20, null), prices(0.25), 20);

		for (var slot : plan.slots()) {
			assertTrue(Math.abs(slot.endState() - 20) < 0.3, "room at " + slot.endState());
		}
		// holding 20 °C at 5 °C outside: 750 W of heat at a COP of ~3.7
		assertEquals(750 / 3.7 * 24, energyWh(plan, 0, SLOTS), 400);
	}

	@Test
	void beforeAnExpensivePeriodItStoresHeatAndUsesLessDuringIt() {
		var planning = planning(HvacMode.HEAT, 5, 20, null);
		// cheap 00-08, expensive 08-16, cheap 16-24
		var plan = plan(planning, prices(0.10, 0.50, 0.10), 20);
		var flat = plan(planning, prices(0.25), 20);

		var expensiveFrom = SLOTS / 3;
		var expensiveTo = 2 * SLOTS / 3;
		var before = plan.slots().get(expensiveFrom - 1).endState();
		assertTrue(before > 20.3, "warmer than the target before the expensive period: " + before);
		assertTrue(energyWh(plan, expensiveFrom, expensiveTo) < energyWh(flat, expensiveFrom, expensiveTo),
				"less energy in the expensive period");
		var coldest = plan.slots().subList(expensiveFrom, expensiveTo).stream() //
				.mapToDouble(slot -> slot.endState()).min().getAsDouble();
		assertTrue(coldest > 18.5, "the quadratic cost keeps the room near the target: " + coldest);
	}

	@Test
	void coolingStaysAtTheTargetFromAbove() {
		var plan = plan(planning(HvacMode.COOL, 30, 24, null), prices(0.25), 24);

		for (var slot : plan.slots()) {
			assertTrue(Math.abs(slot.endState() - 24) < 0.3, "room at " + slot.endState());
		}
	}

	@Test
	void anExternalSetpointReplacesTheSchedule() {
		var planning = planning(HvacMode.HEAT, 5, 20, 22.0);

		assertEquals(22.0, planning.setpointFor(ComfortZoneMode.ON, 0));
		assertEquals(22.0, planning.setpointFor(ComfortZoneMode.FORCED_BOOST, 0));
		assertEquals(21.5, planning.setpointFor(ComfortZoneMode.BOOST, 0), "BOOST: schedule + offset");
		assertNull(planning.setpointFor(ComfortZoneMode.OFF, 0));
		assertEquals(22.0, planning.referenceC(0));
	}

	@Test
	void anOnOffDevicePaysForEveryStart() {
		var matrix = new ZoneModeSwitchingMatrix(DeviceType.ON_OFF, 0.02, 2, 3);

		assertEquals(0.02, matrix.transition(ComfortZoneMode.OFF, ComfortZoneMode.ON).cost());
		assertEquals(0, matrix.transition(ComfortZoneMode.ON, ComfortZoneMode.BOOST).cost());
		assertEquals(3, matrix.minSlotsInMode(ComfortZoneMode.OFF));
		assertEquals(2, matrix.minSlotsInMode(ComfortZoneMode.ON));
	}

	@Test
	void storedHeatIsWorthLessThanAShortfallCosts() {
		var planning = planning(HvacMode.HEAT, 5, 20, null);
		var value = planning.terminalValue(prices(0.30));

		var oneAbove = value.valueEur(21);
		var oneBelow = value.valueEur(19);
		assertTrue(oneAbove > 0);
		assertTrue(-oneBelow > oneAbove, "a shortfall counts in full, stored heat at f_end");
		assertEquals(2 * oneAbove, -oneBelow, 1e-9);
	}
}
