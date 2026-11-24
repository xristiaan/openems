package io.openems.edge.controller.comfortzone;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.hvac.api.Hvac;
import io.openems.edge.hvac.api.HvacMode;
import io.openems.edge.predictor.api.manager.PredictorManager;
import io.openems.edge.predictor.api.prediction.Prediction;
import io.openems.edge.scheduling.algorithm.ExecutionPlan;
import io.openems.edge.scheduling.algorithm.ExecutionPlanCalculator;
import io.openems.edge.scheduling.algorithm.PlanTableBuilder;
import io.openems.edge.scheduling.algorithm.TimeSlot;
import io.openems.edge.scheduling.api.PlanSummary;
import io.openems.edge.scheduling.api.PlanableController;
import io.openems.edge.thermometer.api.Thermometer;

/**
 * The Comfort Zone Controller, single-node: a Plan-able Controller for a room
 * heated or cooled by one device. {@link #createPlan} plans the device's mode
 * per slot (see {@link ZonePlanning}); every cycle, {@link #run} sends the
 * current slot's on/off, heat/cool and set point to the device, whose own
 * thermostat regulates.
 *
 * <p>
 * A Tier-2 resource: no deadline, a continuous schedule.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences({ "hvac", "room" })
@Component(//
		name = "Controller.ComfortZone", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerComfortZoneImpl extends AbstractOpenemsComponent
		implements ControllerComfortZone, PlanableController, Controller, OpenemsComponent {

	private static final long SLOT_SECONDS = (long) (TimeSlot.DURATION_HOURS * 3600);
	/** OFF protects within this margin of the physical limits, in K. */
	private static final double PROTECTION_MARGIN_K = 0.5;

	private final Logger log = LoggerFactory.getLogger(ControllerComfortZoneImpl.class);

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.hvac_id})(enabled=true))")
	private Hvac hvac;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.room_id})(enabled=true))")
	private Thermometer room;

	@Reference
	private PredictorManager predictorManager;

	private Config config;
	private Efficiency efficiency;
	private ZoneSchedule schedule;
	private ManualOverride manualOverride;

	private ComfortZoneMode priorMode = ComfortZoneMode.OFF;

	private ExecutionPlan<ComfortZoneMode> pendingPlan;
	private ExecutionPlan<ComfortZoneMode> activePlan;
	private Instant activePlanStart;

	public ControllerComfortZoneImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values(), //
				ControllerComfortZone.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.manualOverride = new ManualOverride((int) Math.round(config.manualChangeTolerance() * 10),
				Duration.ofSeconds(config.manualChangeSettlingSeconds()),
				config.manualOverrideMaxMinutes() > 0 ? Duration.ofMinutes(config.manualOverrideMaxMinutes()) : null);
		try {
			this.efficiency = Efficiency.parse(config.copPoints(), config.eerPoints());
			var heating = config.hvacMode() != HvacMode.COOL;
			this.schedule = ZoneSchedule.parse(config.schedule(),
					heating ? config.defaultTargetHeat() : config.defaultTargetCool(), ZoneId.systemDefault());
			this.channel(ControllerComfortZone.ChannelId.CONFIGURATION_ERROR).setNextValue(false);
		} catch (IllegalArgumentException e) {
			this.logError(this.log, "Configuration: " + e.getMessage());
			this.channel(ControllerComfortZone.ChannelId.CONFIGURATION_ERROR).setNextValue(true);
			this.efficiency = null;
			this.schedule = null;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public PlanSummary createPlan(List<TimeSlot> slots) throws OpenemsNamedException {
		var slotCount = slots.size();
		var planning = this.planning(slotStart(Instant.now()), slotCount);
		if (planning == null) {
			// Misconfigured: plan nothing, so the DP Controller sees no consumption.
			this.pendingPlan = null;
			return new PlanSummary(new double[slotCount], new double[slotCount]);
		}
		var roomC = this.room.getTemperatureChannel().value().getOrError() / 10.0;

		var targets = planning.targets(slotCount);
		var table = PlanTableBuilder.build(planning.model(slots), slots, targets);
		var plan = ExecutionPlanCalculator.walk(table, slots, targets, roomC, this.priorMode, 0);
		this.pendingPlan = plan;

		var powerW = new double[slotCount];
		var costEur = new double[slotCount];
		var modes = new String[slotCount];
		var endC = new double[slotCount];
		for (var i = 0; i < slotCount; i++) {
			var slot = plan.slots().get(i);
			powerW[i] = slot.energyUsedWh() / TimeSlot.DURATION_HOURS;
			costEur[i] = slot.costEur();
			modes[i] = slot.mode().name();
			endC[i] = slot.endState();
		}
		return new PlanSummary(powerW, costEur, modes, endC);
	}

	@Override
	public void executePlan(Instant planStart) {
		this.activePlan = this.pendingPlan;
		this.activePlanStart = slotStart(planStart);
	}

	@Override
	public OptionalInt slackSlots() {
		// A continuous schedule, no deadline: never Tier-1-urgent.
		return OptionalInt.empty();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.executeSlot(Instant.now());
	}

	/**
	 * Execute Slot: sends the device the command of the slot {@code now} falls
	 * in. Without a plan the device is left as it is.
	 *
	 * @param now the current time
	 * @throws OpenemsNamedException if writing to the device fails
	 */
	void executeSlot(Instant now) throws OpenemsNamedException {
		var index = this.activePlan == null ? -1
				: (int) Math.floorDiv(Duration.between(this.activePlanStart, now).getSeconds(), SLOT_SECONDS);
		if (index < 0 || index >= this.activePlan.slots().size()) {
			this.channel(ControllerComfortZone.ChannelId.NO_PLAN_AVAILABLE).setNextValue(true);
			this.activePlan = null;
			return;
		}
		this.channel(ControllerComfortZone.ChannelId.NO_PLAN_AVAILABLE).setNextValue(false);

		var slot = this.activePlan.slots().get(index);
		this.priorMode = slot.mode();
		this.channel(ControllerComfortZone.ChannelId.PLANNED_MODE).setNextValue(slot.mode().name());
		this.channel(ControllerComfortZone.ChannelId.PLANNED_POWER)
				.setNextValue((int) Math.round(slot.energyUsedWh() / TimeSlot.DURATION_HOURS));

		var roomDeci = this.room.getTemperature().get();
		var roomC = roomDeci == null ? null : roomDeci / 10.0;
		var command = command(this.config.hvacMode(), slot.mode(), slot.targetSetpoint(), slot.endState(), roomC,
				this.config.minTemperature(), this.config.maxTemperature());
		this.channel(ControllerComfortZone.ChannelId.PLANNED_SETPOINT).setNextValue(command.setpointDeci());

		var hold = this.manualOverride.hold(now,
				new ManualOverride.Sent(command.on(), this.config.hvacMode(), command.setpointDeci()),
				new ManualOverride.Readback(this.hvac.getOnChannel().value().get(),
						modeOf(this.hvac.getModeChannel().value().get()), this.hvac.getSetpointChannel().value().get()),
				this.schedule.nextChange(now));
		this.channel(ControllerComfortZone.ChannelId.MANUAL_OVERRIDE).setNextValue(hold);
		if (hold) {
			// Nothing is sent during a manual override, except a hard safety limit
			command = protection(this.config.hvacMode(), roomC, this.config.minTemperature(),
					this.config.maxTemperature());
			if (command == null) {
				return;
			}
		}

		this.hvac.getOnChannel().setNextWriteValue(command.on());
		this.hvac.getModeChannel().setNextWriteValue(this.config.hvacMode());
		if (command.setpointDeci() != null) {
			this.hvac.getSetpointChannel().setNextWriteValue(command.setpointDeci());
		}
	}

	/**
	 * What to send the device.
	 *
	 * @param on           on or off
	 * @param setpointDeci the set point in deci-degrees Celsius, or null
	 */
	record Command(boolean on, Integer setpointDeci) {
	}

	/**
	 * The command for a planned slot. OFF switches the device off, except as
	 * frost protection (heating) or overheating protection (cooling) near the
	 * physical limits. The other modes switch it on with the room temperature
	 * the plan expects at the end of the slot as the set point — never beyond
	 * the mode's goal: the device's own thermostat decides its power, so
	 * sending the goal (e.g. BOOST's target + BoostOffset) would make it run
	 * at full power where the plan has less (a slot cut by the budget).
	 *
	 * @param hvacMode  heat or cool
	 * @param mode      the planned mode
	 * @param goalC     the mode's goal in °C, null for OFF
	 * @param plannedC  the planned room temperature at the end of the slot, in
	 *                      °C
	 * @param roomC     the room temperature in °C, or null if unknown
	 * @param minC      the minimum temperature
	 * @param maxC      the maximum temperature
	 * @return the command
	 */
	static Command command(HvacMode hvacMode, ComfortZoneMode mode, Double goalC, double plannedC, Double roomC,
			double minC, double maxC) {
		var heating = hvacMode != HvacMode.COOL;
		if (mode == ComfortZoneMode.OFF || goalC == null) {
			var protection = protection(hvacMode, roomC, minC, maxC);
			return protection != null ? protection : new Command(false, null);
		}
		var setpointC = heating ? Math.min(plannedC, goalC) : Math.max(plannedC, goalC);
		return new Command(true, deci(Math.max(minC, Math.min(maxC, setpointC))));
	}

	/**
	 * Frost protection (heating) or overheating protection (cooling): on at the
	 * physical limit when the room is within the margin of it.
	 *
	 * @param hvacMode heat or cool
	 * @param roomC    the room temperature in °C, or null if unknown
	 * @param minC     the minimum temperature
	 * @param maxC     the maximum temperature
	 * @return the protecting command, or null if none is needed
	 */
	static Command protection(HvacMode hvacMode, Double roomC, double minC, double maxC) {
		var heating = hvacMode != HvacMode.COOL;
		var protect = roomC != null
				&& (heating ? roomC < minC + PROTECTION_MARGIN_K : roomC > maxC - PROTECTION_MARGIN_K);
		return protect ? new Command(true, deci(heating ? minC : maxC)) : null;
	}

	private static HvacMode modeOf(Integer value) {
		if (value != null) {
			for (var mode : HvacMode.values()) {
				if (mode.getValue() == value) {
					return mode;
				}
			}
		}
		return HvacMode.UNDEFINED;
	}

	private ZonePlanning planning(Instant start, int slotCount) {
		if (this.efficiency == null || this.schedule == null) {
			return null;
		}
		var config = this.config;
		var physics = new ZonePhysicsConfig(config.hvacMode(), config.thermalCapacityKwhPerK(),
				config.transmissionWPerK(), config.nominalPowerW(), config.maxPowerW(), config.minTemperature(),
				config.maxTemperature(), this.efficiency, this.outdoorForecastC(slotCount));
		var matrix = new ZoneModeSwitchingMatrix(config.deviceType(), config.startCostEur(), config.minOnSlots(),
				config.minOffSlots());
		var cost = new ZonePlanning.DeviationCost(config.deviationCostUncomfortableA(),
				config.deviationCostUncomfortableB(), config.deviationCostOtherA(), config.deviationCostOtherB());
		return new ZonePlanning(physics, matrix, config.boostOffset(), cost, config.endOfHorizonFactor(),
				this.setpointC(), this.targets(start, slotCount));
	}

	/**
	 * The schedule's targets per slot; during a manual override the manual
	 * value until the override ends (switched off by hand: the physical limit,
	 * so the plan is OFF).
	 *
	 * @param start     the start of slot 0
	 * @param slotCount the number of slots
	 * @return one target per slot, in °C
	 */
	private double[] targets(Instant start, int slotCount) {
		var targets = this.schedule.targets(start, slotCount);
		var heating = this.config.hvacMode() != HvacMode.COOL;
		var manualC = this.manualOverride.targetC(heating ? this.config.minTemperature() : this.config.maxTemperature());
		if (manualC != null) {
			var until = this.manualOverride.until();
			for (var i = 0; i < slotCount; i++) {
				if (until == null || start.plusSeconds(i * SLOT_SECONDS).isBefore(until)) {
					targets[i] = manualC;
				}
			}
		}
		return targets;
	}

	/**
	 * The outdoor temperature per slot from the predictor, in °C; the fallback
	 * temperature if there is no forecast at all.
	 *
	 * @param slotCount the number of slots
	 * @return one temperature per slot
	 */
	private double[] outdoorForecastC(int slotCount) {
		Integer[] values;
		try {
			var prediction = this.predictorManager
					.getPrediction(ChannelAddress.fromString(this.config.outdoorForecastChannel()));
			values = Prediction.from(Instant.now(), prediction).asArray();
		} catch (OpenemsNamedException | RuntimeException e) {
			values = new Integer[0];
		}
		var forecast = fillForecast(values, slotCount);
		this.channel(ControllerComfortZone.ChannelId.NO_OUTDOOR_FORECAST).setNextValue(forecast == null);
		if (forecast == null) {
			forecast = new double[slotCount];
			Arrays.fill(forecast, this.config.fallbackOutdoorTemperature());
		}
		return forecast;
	}

	/**
	 * Turns a prediction in deci-degrees into °C per slot, filling a gap with
	 * the value before it (the first known value at the start).
	 *
	 * @param deciDegrees the prediction, may contain nulls or be short
	 * @param slotCount   the number of slots
	 * @return one value per slot, or null if the prediction has no value at all
	 */
	static double[] fillForecast(Integer[] deciDegrees, int slotCount) {
		Double first = null;
		for (var i = 0; i < Math.min(slotCount, deciDegrees.length); i++) {
			if (deciDegrees[i] != null) {
				first = deciDegrees[i] / 10.0;
				break;
			}
		}
		if (first == null) {
			return null;
		}
		var out = new double[slotCount];
		var last = first;
		for (var i = 0; i < slotCount; i++) {
			if (i < deciDegrees.length && deciDegrees[i] != null) {
				last = deciDegrees[i] / 10.0;
			}
			out[i] = last;
		}
		return out;
	}

	private Double setpointC() {
		IntegerReadChannel channel = this.channel(ControllerComfortZone.ChannelId.SETPOINT_TEMPERATURE);
		var value = channel.value();
		return value.isDefined() ? value.get() / 10.0 : null;
	}

	private static Integer deci(double celsius) {
		return (int) Math.round(celsius * 10);
	}

	private static Instant slotStart(Instant time) {
		var seconds = time.getEpochSecond();
		return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, SLOT_SECONDS));
	}
}
