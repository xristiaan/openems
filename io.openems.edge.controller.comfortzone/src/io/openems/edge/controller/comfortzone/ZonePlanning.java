package io.openems.edge.controller.comfortzone;

import java.util.ArrayList;
import java.util.List;

import io.openems.edge.scheduling.algorithm.PlanableResourceModel;
import io.openems.edge.scheduling.algorithm.SlotTarget;
import io.openems.edge.scheduling.algorithm.StateBuckets;
import io.openems.edge.scheduling.algorithm.TargetSetpointResolver;
import io.openems.edge.scheduling.algorithm.TerminalValue;
import io.openems.edge.scheduling.algorithm.TimeSlot;

/**
 * Everything the DP engine needs to plan a single-node Comfort Zone: the
 * deviation-cost targets, the set point per mode, what the end state is worth,
 * and the model. Plain Java, so it is unit-testable without OpenEMS.
 *
 * @param physics            the zone's physical parameters
 * @param matrix             the Mode Switching Matrix
 * @param boostOffsetK       how far BOOST goes past the target, in K
 * @param cost               the Deviation Cost per hour
 * @param endOfHorizonFactor {@code f_end}: what stored heat at the end of the
 *                               plan is worth, as a fraction of its price
 * @param setpointC          the optional external set point, in °C, or null
 * @param scheduleC          the schedule's target per slot, in °C
 */
public record ZonePlanning(ZonePhysicsConfig physics, ZoneModeSwitchingMatrix matrix, double boostOffsetK,
		DeviationCost cost, double endOfHorizonFactor, Double setpointC, double[] scheduleC) {

	/** The room temperature buckets: 0.1 °C. */
	static final double BUCKET_K = 0.1;
	/** The table covers the physical limits and this much beyond them, in K. */
	static final double BUCKET_MARGIN_K = 5.0;

	/**
	 * The Deviation Cost: {@code a · d + b · d²} in € per hour for a deviation
	 * {@code d} in °C, separately on the uncomfortable side (below the target
	 * when heating, above it when cooling) and the other side.
	 *
	 * @param aBelow linear, below the target (heating)
	 * @param bBelow quadratic, below the target (heating)
	 * @param aAbove linear, above the target (heating)
	 * @param bAbove quadratic, above the target (heating)
	 */
	public record DeviationCost(double aBelow, double bBelow, double aAbove, double bAbove) {
	}

	/**
	 * The temperature the room is scored against in a slot: the external set
	 * point if one is given (it replaces the schedule, like a manual change),
	 * else the schedule's target.
	 *
	 * @param slot the slot index
	 * @return the target in °C
	 */
	double referenceC(int slot) {
		return this.setpointC != null ? this.setpointC : this.scheduleC[Math.min(slot, this.scheduleC.length - 1)];
	}

	/**
	 * The deviation-cost target per slot. The costs are per hour, so they are
	 * multiplied by the slot length. When cooling, being too warm is the
	 * uncomfortable side, so the below and above costs swap.
	 *
	 * @param slotCount the number of slots
	 * @return one target per slot
	 */
	List<SlotTarget> targets(int slotCount) {
		var hours = TimeSlot.DURATION_HOURS;
		var c = this.cost;
		var targets = new ArrayList<SlotTarget>(slotCount);
		for (var i = 0; i < slotCount; i++) {
			var reference = this.referenceC(i);
			targets.add(this.physics.heating() //
					? SlotTarget.quadratic(reference, c.aBelow() * hours, c.bBelow() * hours, c.aAbove() * hours,
							c.bAbove() * hours) //
					: SlotTarget.quadratic(reference, c.aAbove() * hours, c.bAbove() * hours, c.aBelow() * hours,
							c.bBelow() * hours));
		}
		return targets;
	}

	/**
	 * The set point a mode's physics aims for (the Set-Point Translation
	 * Mapping): ON and FORCED the target (the external set point if given),
	 * BOOST the schedule's target plus {@code BoostOffset} (minus when cooling),
	 * FORCED_BOOST the external set point, else like BOOST. OFF has none: the
	 * physics only protects against frost (overheating).
	 *
	 * @param mode the candidate mode
	 * @param slot the slot index
	 * @return the set point in °C, or null for OFF
	 */
	Double setpointFor(ComfortZoneMode mode, int slot) {
		var schedule = this.scheduleC[Math.min(slot, this.scheduleC.length - 1)];
		var boosted = schedule + (this.physics.heating() ? this.boostOffsetK : -this.boostOffsetK);
		return switch (mode) {
		case OFF -> null;
		case ON, FORCED -> this.setpointC != null ? this.setpointC : schedule;
		case BOOST -> boosted;
		case FORCED_BOOST -> this.setpointC != null ? this.setpointC : boosted;
		};
	}

	/**
	 * What the room's state is still worth at the end of the plan (the
	 * specification's End-of-horizon value): heat stored above the last
	 * target at {@code f_end} of the average buy price, a shortfall in full —
	 * it has to be bought back. Cooling: the other way round.
	 *
	 * @param slots the plan's slots
	 * @return the terminal value
	 */
	TerminalValue terminalValue(List<TimeSlot> slots) {
		if (slots.isEmpty()) {
			return TerminalValue.NONE;
		}
		var last = slots.size() - 1;
		var averageBuy = slots.stream().mapToDouble(TimeSlot::buyPriceEurPerKwh).average().getAsDouble();
		var targetEndC = this.referenceC(last);
		var zonePhysics = new ZonePhysics(this.physics);
		var efficiencyEnd = zonePhysics.efficiency(zonePhysics.outdoorC(last), targetEndC);
		var sign = this.physics.heating() ? 1 : -1;
		var capacity = this.physics.capacityKwhPerK();
		var factor = this.endOfHorizonFactor;
		return endC -> {
			var storedKwh = sign * capacity * (endC - targetEndC);
			var f = storedKwh > 0 ? factor : 1.0;
			return f * averageBuy * storedKwh / efficiencyEnd;
		};
	}

	/**
	 * The model for the DP engine.
	 *
	 * @param slots the plan's slots
	 * @return the model
	 */
	PlanableResourceModel<ComfortZoneMode> model(List<TimeSlot> slots) {
		var buckets = new StateBuckets(this.physics.minC() - BUCKET_MARGIN_K, this.physics.maxC() + BUCKET_MARGIN_K,
				BUCKET_K);
		TargetSetpointResolver<ComfortZoneMode> resolver = this::setpointFor;
		return new PlanableResourceModel<>(ComfortZoneMode.values(), buckets, this.matrix,
				new ZonePhysics(this.physics), resolver, this.terminalValue(slots));
	}
}
