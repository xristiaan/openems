package io.openems.edge.simulator.replay.zone;

import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.common.types.MeterType;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.hvac.api.Hvac;
import io.openems.edge.hvac.api.HvacMode;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.thermometer.api.Thermometer;

/**
 * A simulated room with its own heating / cooling device. Every cycle the
 * device's thermostat sets the thermal power from the set point it was sent —
 * full power below the set point by the thermostat band or more, none at the
 * set point — and the room follows
 * {@code C · dT/dt = Q − H · (T − T_out)}. The electrical power is
 * {@code |Q| / COP} (or EER), reported as a meter of type
 * {@code CONSUMPTION_NOT_METERED}, like the simulated household load: the
 * simulated grid meter ({@code Simulator.GridMeter.Reacting}) adds only those to
 * the grid power, since a metered consumer is already in the real grid meter.
 *
 * <p>
 * Deliberately not the Comfort Zone Controller's own model: a constant COP and
 * a thermostat, so the planner is tested against a room that differs from it.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("outdoor")
@Component(//
		name = "Simulator.ComfortZone", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=CONSUMPTION_NOT_METERED" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
})
public class SimulatorComfortZoneImpl extends AbstractOpenemsComponent
		implements Hvac, Thermometer, ElectricityMeter, OpenemsComponent, EventHandler {

	/** Longer gaps between cycles (a pause, a debugger) are not simulated. */
	private static final double MAX_STEP_SECONDS = 10;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL, //
			target = "(&(id=${config.outdoor_id})(enabled=true))")
	private volatile Thermometer outdoor;

	private Config config;
	private Room room;
	private double roomC;
	private boolean on = false;
	private HvacMode mode = HvacMode.HEAT;
	private double setpointC;
	private Instant lastStep;

	public SimulatorComfortZoneImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				Thermometer.ChannelId.values(), //
				Hvac.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.room = new Room(config.thermalCapacityKwhPerK(), config.transmissionWPerK(), config.maxPowerW(),
				config.cop(), config.eer(), config.thermostatBand());
		this.roomC = config.initialTemperature();
		this.setpointC = config.initialTemperature();
		this.lastStep = null;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		this.takeCommands();
		var now = Instant.now(this.componentManager.getClock());
		var seconds = this.lastStep == null ? 0 : Duration.between(this.lastStep, now).toMillis() / 1000.0;
		this.lastStep = now;

		var outdoorC = this.outdoorC();
		var step = step(this.room, this.roomC, outdoorC, this.on, this.mode, this.setpointC,
				Math.min(seconds, MAX_STEP_SECONDS));
		this.roomC = step.roomC();

		this._setTemperature((int) Math.round(this.roomC * 10));
		this._setActivePower((int) Math.round(step.electricalW()));
		this._setActivePowerL1((int) Math.round(step.electricalW()));
		this.getOnChannel().setNextValue(this.on);
		this.getModeChannel().setNextValue(this.mode);
		this.getSetpointChannel().setNextValue((int) Math.round(this.setpointC * 10));
	}

	// What the controller wrote this cycle becomes the device's state
	private void takeCommands() {
		this.getOnChannel().getNextWriteValueAndReset().ifPresent(value -> this.on = value);
		this.getModeChannel().getNextWriteValueAndReset().ifPresent(value -> {
			if (value == HvacMode.COOL.getValue()) {
				this.mode = HvacMode.COOL;
			} else if (value == HvacMode.HEAT.getValue()) {
				this.mode = HvacMode.HEAT;
			}
		});
		this.getSetpointChannel().getNextWriteValueAndReset().ifPresent(value -> this.setpointC = value / 10.0);
	}

	private double outdoorC() {
		var outdoor = this.outdoor;
		var value = outdoor == null ? null : outdoor.getTemperature().get();
		return value == null ? this.config.fallbackOutdoorTemperature() : value / 10.0;
	}

	/**
	 * The simulated room and its device.
	 *
	 * @param capacityKwhPerK   the thermal capacity, in kWh/°C
	 * @param transmissionWPerK the heat loss to the outside, in W/°C
	 * @param maxPowerW         the device's maximum electrical power, in W
	 * @param cop               the COP when heating
	 * @param eer               the EER when cooling
	 * @param bandK             the thermostat band, in K
	 */
	record Room(double capacityKwhPerK, double transmissionWPerK, double maxPowerW, double cop, double eer,
			double bandK) {
	}

	/**
	 * One simulation step.
	 *
	 * @param roomC       the room temperature afterwards, in °C
	 * @param electricalW the device's electrical power during the step, in W
	 */
	record Step(double roomC, double electricalW) {
	}

	/**
	 * Advances the room by one step.
	 *
	 * @param room      the room and its device
	 * @param roomC     the room temperature, in °C
	 * @param outdoorC  the outdoor temperature, in °C
	 * @param on        whether the device is on
	 * @param mode      heat or cool
	 * @param setpointC the set point, in °C
	 * @param seconds   the step length
	 * @return the step's result
	 */
	static Step step(Room room, double roomC, double outdoorC, boolean on, HvacMode mode, double setpointC,
			double seconds) {
		var heating = mode != HvacMode.COOL;
		var efficiency = heating ? room.cop() : room.eer();
		var error = heating ? setpointC - roomC : roomC - setpointC;
		var fraction = on ? Math.max(0, Math.min(1, error / room.bandK())) : 0;
		var electricalW = fraction * room.maxPowerW();
		var thermalW = (heating ? 1 : -1) * electricalW * efficiency;
		var hours = seconds / 3600.0;
		var next = roomC
				+ (thermalW - room.transmissionWPerK() * (roomC - outdoorC)) * hours
						/ (room.capacityKwhPerK() * 1000.0);
		return new Step(next, electricalW);
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.CONSUMPTION_NOT_METERED;
	}

	@Override
	public String debugLog() {
		return String.format("%.2f°C %s %s %.1f°C %dW", this.roomC, this.on ? "on" : "off", this.mode.getName(),
				this.setpointC, this.getActivePower().orElse(0));
	}
}
