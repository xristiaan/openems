package io.openems.edge.controller.ess.battery;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
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

/**
 * The Battery Controller: a Plan-able Controller for a battery ESS. Day-ahead
 * planning ({@link #createPlan}) and real-time execution
 * ({@link #executePlan}/{@link #run}) are split exactly as the Plan-able
 * Controller interface specifies — the actual physics live in
 * {@link BatteryPhysics}, a plain Java class with no OpenEMS dependency at
 * all, per the project's "keep every algorithm OpenEMS-free" principle.
 *
 * <p>
 * <b>Known, deliberate gap:</b> nothing yet calls {@link #createPlan}/
 * {@link #executePlan} — that is the DP Controller's job (not built yet; see
 * {@code docs/architecture/controller-inventory.md}'s Open Items), which is
 * meant to supply the price forecast and per-resource power budget this
 * Controller needs to plan against. Until then, {@link #run} correctly holds
 * no power constraint and reports {@code NO_PLAN_AVAILABLE} — this
 * Controller is fully wired and ready, just has nothing to execute yet.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Ess.Battery", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerEssBatteryImpl extends AbstractOpenemsComponent
		implements ControllerEssBattery, Controller, OpenemsComponent {

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

	/**
	 * Create Plan: builds an execution plan starting from the real,
	 * currently-measured SoC.
	 *
	 * @param slots   one entry per slot: energy price and the power budget
	 *                    assigned to this resource (by the DP Controller)
	 * @param targets one entry per slot: the deviation-cost target, same
	 *                    length as {@code slots}
	 * @return the execution plan
	 * @throws OpenemsNamedException if the real SoC can't be read
	 */
	public ExecutionPlan<BatteryMode> createPlan(List<TimeSlot> slots, List<SlotTarget> targets)
			throws OpenemsNamedException {
		var model = this.buildModel(targets);
		var table = PlanTableBuilder.build(model, slots, targets);
		var currentSoc = this.ess.getSoc().getOrError() / 100.0;
		return ExecutionPlanCalculator.walk(table, slots, currentSoc, this.priorMode, this.lockRemaining);
	}

	/**
	 * Execute Plan: pushes a plan into this Controller, to be executed slot
	 * by slot starting at {@code planStart} as {@link #run} is called.
	 *
	 * @param plan      the execution plan from {@link #createPlan}
	 * @param planStart the absolute time slot 0 of {@code plan} begins
	 */
	public void executePlan(ExecutionPlan<BatteryMode> plan, Instant planStart) {
		this.activePlan = plan;
		this.activePlanStart = planStart;
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

		TargetSetpointResolver<BatteryMode> targetSetpointResolver = (mode, slot) -> {
			if (mode == BatteryMode.OFF) {
				return null;
			}
			var target = targets.get(slot);
			return (target.min() + target.max()) / 2.0;
		};

		return new PlanableResourceModel<>(BatteryMode.values(), SOC_BUCKETS, new BatteryModeSwitchingMatrix(),
				new BatteryPhysics(physicsConfig), targetSetpointResolver);
	}
}
