package io.openems.edge.device.mqtt.hvac;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

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
import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.device.mqtt.common.DeviceLink;
import io.openems.edge.device.mqtt.common.DeviceReadings;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.hvac.api.Hvac;
import io.openems.edge.hvac.api.HvacMode;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.thermometer.api.Thermometer;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * A heating / cooling device behind a platform bridge (Homey, Home
 * Assistant), e.g. an air conditioner through its manufacturer's Homey app.
 * Its state topic gives the room temperature (as a {@link Thermometer}), the
 * electrical power (as an {@link ElectricityMeter}) and on/off, mode and set
 * point (the {@link Hvac} Channels, read back).
 *
 * <p>
 * The consumed energy is the device's own counter if it has one (MELCloud
 * sends its power only hourly, so integrating it would be wrong), else it is
 * calculated from the power.
 *
 * <p>
 * Read-only for now: what a Controller writes to the {@link Hvac} Channels is
 * not yet sent to the device.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("mqtt")
@Component(//
		name = "Hvac.Mqtt", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class HvacMqttImpl extends AbstractOpenemsComponent implements HvacMqtt, Hvac, Thermometer,
		ElectricityMeter, MqttDevice, OpenemsComponent, TimedataProvider, EventHandler {

	private final CalculateEnergyFromPower calculateConsumptionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY);

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.mqtt_id})(enabled=true))")
	private BridgeMqtt mqtt;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	private Config config;
	private DeviceLink link;

	public HvacMqttImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				Thermometer.ChannelId.values(), //
				Hvac.ChannelId.values(), //
				MqttDevice.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		var readings = new DeviceReadings(config.powerField(), null, false,
				Duration.ofSeconds(config.staleAfterSeconds()));
		this.link = new DeviceLink(this.mqtt, config.deviceId(), config.stateTopic(), "", "", readings, null);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.link != null) {
			this.link.close();
			this.link = null;
		}
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled() || this.link == null) {
			return;
		}
		var now = Instant.now(this.componentManager.getClock());
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.updateReadings(now);
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.updateEnergy(now);
		}
	}

	private void updateReadings(Instant now) {
		var readings = this.link.readings();
		var power = readings.powerW(now);
		this._setActivePower(power);
		this._setActivePowerL1(power);
		this._setTemperature(toDeciDegrees(readings.number(this.config.temperatureField(), now)));
		var modeText = readings.text(this.config.modeField(), now);
		this.getOnChannel().setNextValue(isOn(readings.bool(this.config.onField(), now), modeText));
		this.getModeChannel().setNextValue(modeOf(modeText));
		this.getSetpointChannel().setNextValue(toDeciDegrees(readings.number(this.config.setpointField(), now)));
		this.link.updateChannels(this, now);
	}

	private void updateEnergy(Instant now) {
		if (this.config.energyField().isBlank()) {
			this.calculateConsumptionEnergy.update(this.getActivePower().get());
			return;
		}
		this._setActiveConsumptionEnergy(toWattHours(this.link.readings().number(this.config.energyField(), now)));
	}

	/**
	 * Kilowatt-hours to watt-hours.
	 *
	 * @param kwh the energy, or null
	 * @return watt-hours, or null
	 */
	static Long toWattHours(Double kwh) {
		return kwh == null ? null : Math.round(kwh * 1000);
	}

	/**
	 * The device's mode from the platform's mode text.
	 *
	 * @param text e.g. "heat", "cool", "auto"; null if unknown
	 * @return HEAT, COOL or OFF, else UNDEFINED (e.g. auto, dry, fan)
	 */
	static HvacMode modeOf(String text) {
		if (text == null) {
			return HvacMode.UNDEFINED;
		}
		return switch (text.trim().toLowerCase()) {
		case "heat" -> HvacMode.HEAT;
		case "cool" -> HvacMode.COOL;
		case "off" -> HvacMode.OFF;
		default -> HvacMode.UNDEFINED;
		};
	}

	/**
	 * Whether the device is on: its on/off value, except that a mode "off"
	 * means off (some platforms switch off through the mode).
	 *
	 * @param onoff    the on/off value, or null if unknown
	 * @param modeText the mode text, or null if unknown
	 * @return on, off, or null if unknown
	 */
	static Boolean isOn(Boolean onoff, String modeText) {
		if (modeText != null && "off".equalsIgnoreCase(modeText.trim())) {
			return false;
		}
		return onoff;
	}

	/**
	 * Degrees Celsius to deci-degrees.
	 *
	 * @param celsius the temperature, or null
	 * @return deci-degrees, or null
	 */
	static Integer toDeciDegrees(Double celsius) {
		return celsius == null ? null : (int) Math.round(celsius * 10);
	}

	@Override
	public String debugLog() {
		return celsius(this.getTemperature().get()) + "|" + this.getActivePower().asString() + "|"
				+ this.getModeChannel().value().asOptionString() + "|Set:" + celsius(this.getSetpointChannel().value().get())
				+ "|On:" + this.getOnChannel().value().asString();
	}

	private static String celsius(Integer deciDegrees) {
		return deciDegrees == null ? "UNDEFINED" : String.format(Locale.ROOT, "%.1f °C", deciDegrees / 10.0);
	}

	@Override
	public MeterType getMeterType() {
		return this.config.type();
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
