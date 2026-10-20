package io.openems.edge.controller.pv;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.predictor.api.prediction.Prediction;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
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
 * The PV Controller: a Plan-able Controller for a PV inverter, and a
 * <em>producer</em> in the DP Controller's arbitration — it reports its
 * forecast via {@link #productionForecastW(int)} and gets a negative budget
 * back. Day-ahead planning ({@link #createPlan}) and real-time execution
 * ({@link #executePlan}/{@link #run}) are split exactly as for the Battery
 * Controller; the physics ({@link PvPhysics}) and the command rules
 * ({@link PvCommandLimiter}) are plain Java with no OpenEMS dependency.
 *
 * <p>
 * Execution commands the inverter's standard {@code ActivePowerLimit}
 * Channel (Communication Mode "OpenEMS native").
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("pvInverter")
@Component(//
		name = "Controller.Pv", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerPvImpl extends AbstractOpenemsComponent
		implements ControllerPv, PlanableController, Controller, OpenemsComponent {

	// Stateless: one bucket is enough — the next slot never depends on this
	// slot's output.
	private static final StateBuckets STATELESS = new StateBuckets(0, 0, 1);

	// Candidate order breaks cost ties: with nothing to gain either way
	// (e.g. no production at night), prefer not curtailing.
	private static final PvMode[] MODES_IN_PREFERENCE_ORDER = { PvMode.ON, PvMode.BOOST, PvMode.FORCED,
			PvMode.OFF };

	/** Below this gap a budget counts as "not binding", in W. */
	private static final double BUDGET_BINDING_TOLERANCE_W = 1;

	@Reference
	private ConfigurationAdmin cm;

	@Reference
	private PredictorManager predictorManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.pvInverter_id})(enabled=true))")
	private ManagedSymmetricPvInverter pvInverter;

	private Config config;
	private PvCommandLimiter limiter;

	// Forward-walk mode state, carried from one plan/slot to the next.
	private PvMode priorMode = PvMode.ON;

	// Read once per planning cycle by productionForecastW, reused by every
	// createPlan call of that cycle.
	private double[] forecastW;

	// Computed by createPlan, not yet committed.
	private PlannedRun pendingRun;

	// Committed by executePlan; what run()/executeSlot actually acts on.
	private PlannedRun activeRun;
	private Instant activePlanStart;

	/**
	 * A plan together with the budget and physics it was made with — needed
	 * at execution time to decide whether the budget is actually binding.
	 */
	private record PlannedRun(ExecutionPlan<PvMode> plan, List<TimeSlot> slots, PvPhysics physics,
			PvPhysicsConfig physicsConfig) {
	}

	public ControllerPvImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerPv.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.limiter = new PvCommandLimiter(config.deadbandW(),
				Duration.ofSeconds(config.minCurtailmentPeriodSeconds()), config.rampLimitWPerSecond());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public Optional<double[]> productionForecastW(int slotCount) {
		this.forecastW = this.readForecast(slotCount);
		return Optional.of(this.forecastW.clone());
	}

	@Override
	public double[] curtailableProductionW(int slotCount) {
		var physicsConfig = this.physicsConfig(slotCount);
		var out = new double[slotCount];
		for (var i = 0; i < slotCount; i++) {
			out[i] = physicsConfig.curtailableW(i);
		}
		return out;
	}

	@Override
	public PlanSummary createPlan(List<TimeSlot> slots) throws OpenemsNamedException {
		var physicsConfig = this.physicsConfig(slots.size());
		var physics = new PvPhysics(physicsConfig);

		// Deviation cost is 0 for PV (producing less than the limit because
		// of clouds isn't a deviation) and there's no deadline: the energy
		// price alone decides whether to curtail.
		var targets = Collections.nCopies(slots.size(), SlotTarget.unconstrained());

		var setpointW = this.setpointW();
		TargetSetpointResolver<PvMode> resolver = (mode, slot) -> resolveTarget(mode, setpointW,
				slots.get(slot).restOfSiteW());
		var model = new PlanableResourceModel<>(MODES_IN_PREFERENCE_ORDER, STATELESS, new PvModeSwitchingMatrix(),
				physics, resolver);

		var table = PlanTableBuilder.build(model, slots, targets);
		// currentState is ignored by the physics (stateless).
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, 0, this.priorMode, 0);
		this.pendingRun = new PlannedRun(plan, List.copyOf(slots), physics, physicsConfig);

		var slotHours = PvPhysics.SLOT_DURATION_SECONDS / 3600.0;
		var slotCount = plan.slots().size();
		var powerPerSlotW = new double[slotCount];
		var costPerSlotEur = new double[slotCount];
		var modePerSlot = new String[slotCount];
		for (var i = 0; i < slotCount; i++) {
			var plannedSlot = plan.slots().get(i);
			powerPerSlotW[i] = plannedSlot.energyUsedWh() / slotHours;
			costPerSlotEur[i] = plannedSlot.costEur();
			modePerSlot[i] = plannedSlot.mode().name();
		}
		return new PlanSummary(powerPerSlotW, costPerSlotEur, modePerSlot, null);
	}

	@Override
	public void executePlan(Instant planStart) {
		this.activeRun = this.pendingRun;
		this.activePlanStart = planStart;
	}

	@Override
	public OptionalInt slackSlots() {
		// Forced device: no deadline, never Tier-1-urgent.
		return OptionalInt.empty();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.executeSlot(Instant.now());
	}

	/**
	 * Execute Slot: decides the planner's limit for the slot {@code now} falls
	 * into, lets {@link PvCommandLimiter} combine it with any Balancing
	 * correction under the execution limits, and commands the inverter.
	 *
	 * @param now the current time
	 * @throws OpenemsNamedException if writing the inverter's limit fails
	 */
	void executeSlot(Instant now) throws OpenemsNamedException {
		var pMaxW = this.config.pFracMax() * this.config.inverterRatedW();
		var slotIndex = this.activeRun == null ? -1 : this.slotIndexFor(now);
		var hasPlan = slotIndex >= 0 && slotIndex < this.activeRun.plan().slots().size();
		this.channel(ControllerPv.ChannelId.NO_PLAN_AVAILABLE).setNextValue(!hasPlan);

		var slotStart = hasPlan //
				? this.activePlanStart.plusSeconds((long) (slotIndex * PvPhysics.SLOT_DURATION_SECONDS))
				: quarterStart(now);

		IntegerWriteChannel balancingChannel = this.channel(ControllerPv.ChannelId.BALANCING_LIMIT);
		var balancing = balancingChannel.getNextWriteValueAndReset();
		if (balancing.isPresent()) {
			this.limiter.requestBalancing(balancing.get(), pMaxW, slotStart);
		}

		double plannerLimitW;
		if (hasPlan) {
			var plannedSlot = this.activeRun.plan().slots().get(slotIndex);
			this.priorMode = plannedSlot.mode();
			this.channel(ControllerPv.ChannelId.PLANNED_MODE).setNextValue(plannedSlot.mode().name());
			plannerLimitW = commandLimitW(this.activeRun.physics(), this.activeRun.physicsConfig(),
					plannedSlot.mode(), this.activeRun.slots().get(slotIndex).availablePowerW(),
					plannedSlot.targetSetpoint(), slotIndex);
		} else {
			// No plan: the planner doesn't curtail.
			this.activeRun = null;
			this.channel(ControllerPv.ChannelId.PLANNED_MODE).setNextValue(null);
			plannerLimitW = pMaxW;
		}
		this.channel(ControllerPv.ChannelId.PLANNED_LIMIT).setNextValue((int) Math.round(plannerLimitW));

		if (!this.config.curtailmentSupported()) {
			// Required graceful degradation: never send a limit.
			this.channel(ControllerPv.ChannelId.COMMANDED_LIMIT).setNextValue(null);
			this.channel(ControllerPv.ChannelId.COMMAND_SOURCE).setNextValue(null);
			return;
		}

		var commandW = (int) Math.round(this.limiter.step(now, slotStart, plannerLimitW));
		this.channel(ControllerPv.ChannelId.COMMANDED_LIMIT).setNextValue(commandW);
		this.channel(ControllerPv.ChannelId.COMMAND_SOURCE).setNextValue(this.limiter.lastSource().name());
		this.pvInverter.getActivePowerLimitChannel().setNextWriteValue(commandW);
	}

	/**
	 * The planner's limit to command for a planned slot. Same as the
	 * physics' {@code P_limit}, except that the budget only limits the
	 * command when it is actually binding (below the forecast): a budget that
	 * merely equals the forecast must not curtail real production when the
	 * forecast turns out too low.
	 *
	 * @param physics         the physics the plan was made with
	 * @param physicsConfig   its configuration, including the forecast
	 * @param mode            the planned mode
	 * @param budgetW         the slot's budget (negative)
	 * @param targetSetpointW the planned set-point, or null
	 * @param slot            the slot index
	 * @return the limit to command, in W
	 */
	static double commandLimitW(PvPhysics physics, PvPhysicsConfig physicsConfig, PvMode mode, double budgetW,
			Double targetSetpointW, int slot) {
		var binding = Math.abs(budgetW) < physicsConfig.availableW(slot) - BUDGET_BINDING_TOLERANCE_W;
		return physics.limitW(mode, binding ? budgetW : Double.MAX_VALUE, targetSetpointW);
	}

	/**
	 * Resolves the set-point a mode's physics uses: nothing in OFF; otherwise
	 * the optional external set-point, honoured in ON/BOOST. FORCED without
	 * one follows the site: produce only what the rest of the site consumes,
	 * so nothing is exported (the physics still keeps at least P_min).
	 *
	 * @param mode        the candidate mode
	 * @param setpointW   the external set-point, or null
	 * @param restOfSiteW the rest of the site's grid power in the slot,
	 *                        import-positive
	 * @return the set-point (production negative), or null
	 */
	static Double resolveTarget(PvMode mode, Double setpointW, double restOfSiteW) {
		if (mode == PvMode.OFF) {
			return null;
		}
		if (mode == PvMode.FORCED && setpointW == null) {
			return -Math.max(0, restOfSiteW);
		}
		return setpointW;
	}

	private PvPhysicsConfig physicsConfig(int slotCount) {
		if (this.forecastW == null || this.forecastW.length != slotCount) {
			this.forecastW = this.readForecast(slotCount);
		}
		return new PvPhysicsConfig(this.config.inverterRatedW(), this.config.pFracMin(), this.config.pFracMax(),
				this.config.curtailmentSupported(), this.forecastW.clone());
	}

	private Double setpointW() {
		IntegerReadChannel channel = this.channel(ControllerPv.ChannelId.SETPOINT_POWER);
		var value = channel.value();
		return value.isDefined() ? (double) value.get() : null;
	}

	private double[] readForecast(int slotCount) {
		var out = new double[slotCount];
		Integer[] values;
		try {
			var prediction = this.predictorManager
					.getPrediction(ChannelAddress.fromString(this.config.forecastChannel()));
			values = Prediction.from(Instant.now(), prediction).asArray();
		} catch (OpenemsNamedException | RuntimeException e) {
			values = new Integer[0];
		}
		var any = false;
		for (var i = 0; i < slotCount && i < values.length; i++) {
			if (values[i] != null) {
				out[i] = Math.max(0, values[i]);
				any = true;
			}
		}
		this.channel(ControllerPv.ChannelId.NO_FORECAST_AVAILABLE).setNextValue(!any);
		return out;
	}

	private int slotIndexFor(Instant now) {
		var elapsedSeconds = Duration.between(this.activePlanStart, now).getSeconds();
		return (int) Math.floorDiv(elapsedSeconds, (long) PvPhysics.SLOT_DURATION_SECONDS);
	}

	private static Instant quarterStart(Instant now) {
		var epochSeconds = now.getEpochSecond();
		var slotSeconds = (long) PvPhysics.SLOT_DURATION_SECONDS;
		return Instant.ofEpochSecond(epochSeconds - epochSeconds % slotSeconds);
	}
}
