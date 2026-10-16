package io.openems.edge.controller.ess.battery;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.type.Phase.SingleOrAllPhase;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.PowerConstraint;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.scheduling.algorithm.ExecutionPlan;
import io.openems.edge.scheduling.algorithm.ExecutionPlanCalculator;
import io.openems.edge.scheduling.algorithm.PlanTableBuilder;
import io.openems.edge.scheduling.algorithm.PlanableResourceModel;
import io.openems.edge.scheduling.algorithm.SlotTarget;
import io.openems.edge.scheduling.algorithm.StateBuckets;
import io.openems.edge.scheduling.algorithm.TargetSetpointResolver;
import io.openems.edge.scheduling.algorithm.TimeSlot;
import io.openems.edge.scheduling.api.PlanSummary;
import io.openems.edge.scheduling.api.PlanableController;

/**
 * The Battery Controller: a Plan-able Controller for a battery ESS,
 * implementing {@link PlanableController} so the DP Controller can call it
 * uniformly alongside any other resource type. Day-ahead planning
 * ({@link #createPlan}) and real-time execution
 * ({@link #executePlan}/{@link #run}) are split exactly as the Plan-able
 * Controller interface specifies — the actual physics live in
 * {@link BatteryPhysics}, a plain Java class with no OpenEMS dependency at
 * all, per the project's "keep every algorithm OpenEMS-free" principle.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.Battery", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssBatteryImpl extends AbstractOpenemsComponent
		implements ControllerEssBattery, PlanableController, Controller, OpenemsComponent {

	private static final StateBuckets SOC_BUCKETS = new StateBuckets(0.0, 1.0, 0.02);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.ess_id})(enabled=true))")
	private ManagedSymmetricEss ess;

	private Config config;

	// Forward-walk mode-lock state, carried from one plan/slot to the next.
	private BatteryMode priorMode = BatteryMode.OFF;
	private int lockRemaining = 0;

	// Computed by createPlan, not yet committed.
	private ExecutionPlan<BatteryMode> pendingPlan;

	// Committed by executePlan; what run()/executeSlot actually acts on.
	private ExecutionPlan<BatteryMode> activePlan;
	private Instant activePlanStart;

	public ControllerEssBatteryImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerEssBattery.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public PlanSummary createPlan(List<TimeSlot> slots) throws OpenemsNamedException {
		// Target is encapsulated here, not supplied by the DP Controller: a
		// uniform comfort band every slot, since Battery has no schedule or
		// deadline of its own (see slackSlots()).
		var target = new SlotTarget(this.config.socMinNormal(), this.config.socMaxNormal(),
				this.config.deviationCostEurPerKwh());
		var targets = java.util.Collections.nCopies(slots.size(), target);

		var model = this.buildModel(targets);
		var table = PlanTableBuilder.build(model, slots, targets);
		var currentSoc = this.ess.getSoc().getOrError() / 100.0;
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, currentSoc, this.priorMode,
				this.lockRemaining);
		this.pendingPlan = plan;

		var slotHours = BatteryPhysics.SLOT_DURATION_SECONDS / 3600.0;
		var slotCount = plan.slots().size();
		var powerPerSlotW = new double[slotCount];
		var costPerSlotEur = new double[slotCount];
		for (var i = 0; i < slotCount; i++) {
			var plannedSlot = plan.slots().get(i);
			powerPerSlotW[i] = plannedSlot.energyUsedWh() / slotHours;
			costPerSlotEur[i] = plannedSlot.costEur();
		}
		return new PlanSummary(powerPerSlotW, costPerSlotEur);
	}

	@Override
	public void executePlan(Instant planStart) {
		this.activePlan = this.pendingPlan;
		this.activePlanStart = planStart;
	}

	@Override
	public OptionalInt slackSlots() {
		// Battery is a comfort-band resource (stay within a SoC range), not a
		// deadline resource — it has nothing forcing it to a specific value
		// by a specific time, so it's never Tier-1-urgent.
		return OptionalInt.empty();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.executeSlot(Instant.now());
	}

	/**
	 * Execute Slot: commands the Ess for whichever slot of the active plan
	 * {@code now} falls into, or holds no constraint if there is none.
	 *
	 * @param now the current time
	 * @throws OpenemsNamedException if applying the power constraint fails
	 */
	void executeSlot(Instant now) throws OpenemsNamedException {
		var slotIndex = this.activePlan == null ? -1 : this.slotIndexFor(now);
		if (slotIndex < 0 || slotIndex >= this.activePlan.slots().size()) {
			this.channel(ControllerEssBattery.ChannelId.NO_PLAN_AVAILABLE).setNextValue(true);
			this.activePlan = null;
			return;
		}
		this.channel(ControllerEssBattery.ChannelId.NO_PLAN_AVAILABLE).setNextValue(false);

		var plannedSlot = this.activePlan.slots().get(slotIndex);
		this.priorMode = plannedSlot.mode();
		this.channel(ControllerEssBattery.ChannelId.PLANNED_MODE).setNextValue(plannedSlot.mode().name());

		// Spread the slot's planned energy evenly across its 900s — a
		// simplification of the physics' own burst-then-idle timing within
		// the slot (see BatteryPhysics), which would need sub-slot state to
		// reproduce exactly. Reaches the same end-of-slot SoC either way.
		var slotHours = BatteryPhysics.SLOT_DURATION_SECONDS / 3600.0;
		var averagePowerW = plannedSlot.energyUsedWh() / slotHours;

		// This Controller's own convention is charge-positive; OpenEMS's ESS
		// convention is discharge-positive — flip the sign when commanding.
		var essPowerW = (int) Math.round(-averagePowerW);
		this.channel(ControllerEssBattery.ChannelId.PLANNED_POWER).setNextValue(essPowerW);

		PowerConstraint.apply(this.ess, this.id(), SingleOrAllPhase.ALL, Pwr.ACTIVE, Relationship.EQUALS, essPowerW);
	}

	private int slotIndexFor(Instant now) {
		var elapsedSeconds = Duration.between(this.activePlanStart, now).getSeconds();
		return (int) Math.floorDiv(elapsedSeconds, (long) BatteryPhysics.SLOT_DURATION_SECONDS);
	}

	private PlanableResourceModel<BatteryMode> buildModel(List<SlotTarget> targets) throws OpenemsNamedException {
		var capacityWh = (double) this.ess.getCapacity().getOrError();
		var physicsConfig = new BatteryPhysicsConfig(//
				capacityWh, //
				this.config.socMinNormal(), this.config.socMaxNormal(), //
				this.config.socMinSafe(), this.config.socMaxSafe(), //
				this.config.cRateOnCharge(), this.config.cRateOnDischarge(), //
				this.config.cRateBoostCharge(), this.config.cRateBoostDischarge(), //
				this.config.cRateForcedCharge(), this.config.cRateForcedDischarge(), //
				this.config.cRateForcedBoostCharge(), this.config.cRateForcedBoostDischarge(), //
				this.config.chargeEfficiency(), this.config.dischargeEfficiency(), //
				this.config.degradationCostEurPerKwh(), this.config.degradationCostBoostMultiplier());

		var setpointSoc = this.setpointSoc();
		TargetSetpointResolver<BatteryMode> targetSetpointResolver = (mode, slot) -> resolveTarget(mode,
				targets.get(slot), setpointSoc);

		return new PlanableResourceModel<>(BatteryMode.values(), SOC_BUCKETS, new BatteryModeSwitchingMatrix(),
				new BatteryPhysics(physicsConfig), targetSetpointResolver);
	}

	/**
	 * The optional external set-point, from {@link ControllerEssBattery.ChannelId#SETPOINT_SOC}.
	 *
	 * @return the set-point as a SoC fraction (0-1), or null if none is set
	 */
	private Double setpointSoc() {
		IntegerReadChannel channel = this.channel(ControllerEssBattery.ChannelId.SETPOINT_SOC);
		var percent = channel.value();
		return percent.isDefined() ? Math.max(0, Math.min(100, percent.get())) / 100.0 : null;
	}

	/**
	 * Resolves the target a mode's physics seeks: nothing in OFF; otherwise
	 * the external set-point if one is given (optional in every mode,
	 * including ON), else the middle of the slot's SoC band. The physics
	 * itself still caps movement at the mode's own band.
	 *
	 * @param mode        the candidate mode
	 * @param target      the slot's deviation-cost target (the SoC band)
	 * @param setpointSoc the external set-point as a fraction, or null
	 * @return the target SoC fraction, or null for OFF
	 */
	static Double resolveTarget(BatteryMode mode, SlotTarget target, Double setpointSoc) {
		if (mode == BatteryMode.OFF) {
			return null;
		}
		if (setpointSoc != null) {
			return setpointSoc;
		}
		return (target.min() + target.max()) / 2.0;
	}
}
