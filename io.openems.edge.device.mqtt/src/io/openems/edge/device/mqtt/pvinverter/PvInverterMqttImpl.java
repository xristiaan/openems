package io.openems.edge.device.mqtt.pvinverter;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
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
import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.device.mqtt.common.CommandSender;
import io.openems.edge.device.mqtt.common.DeviceLink;
import io.openems.edge.device.mqtt.common.DeviceReadings;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * A PV inverter behind a platform bridge (Homey, Home Assistant). Its power
 * comes from the device's MQTT state topic; whatever a Controller writes to
 * {@code ActivePowerLimit} is sent as a {@code set-power} command, with the
 * limit in W and as a fraction of the rated power. Device specifics (how a
 * limit is applied) live in the platform's flow, not here.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("mqtt")
@Component(//
		name = "PvInverter.Mqtt", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class PvInverterMqttImpl extends AbstractOpenemsComponent implements PvInverterMqtt,
		ManagedSymmetricPvInverter, ElectricityMeter, MqttDevice, OpenemsComponent, TimedataProvider, EventHandler {

	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.mqtt_id})(enabled=true))")
	private BridgeMqtt mqtt;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	private Config config;
	private DeviceLink link;
	private Integer limitW;

	public PvInverterMqttImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				MqttDevice.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this._setMaxApparentPower(config.ratedPowerW());
		var readings = new DeviceReadings(config.powerField(), null, config.powerInverted(),
				Duration.ofSeconds(config.staleAfterSeconds()));
		var sender = new CommandSender(config.deviceId(), config.id(), config.deadbandW(),
				Duration.ofSeconds(config.minCommandIntervalSeconds()),
				Duration.ofSeconds(config.resendIntervalSeconds()), Duration.ofSeconds(config.ackTimeoutSeconds()));
		this.link = new DeviceLink(this.mqtt, config.deviceId(), config.stateTopic(), config.commandTopic(),
				config.ackTopic(), readings, sender);
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
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> {
			this._setActivePower(this.link.readings().powerW(now));
			this.link.updateChannels(this, now);
		}
		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE -> this.sendLimit(now);
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE ->
			this.calculateProductionEnergy.update(this.getActivePower().get());
		}
	}

	private void sendLimit(Instant now) {
		this.getActivePowerLimitChannel().getNextWriteValueAndReset().ifPresent(limit -> this.limitW = limit);
		this._setActivePowerLimit(this.limitW);
		if (this.limitW != null) {
			var fraction = this.config.ratedPowerW() > 0 ? (double) this.limitW / this.config.ratedPowerW() : null;
			this.link.send(now, this.limitW, fraction);
		}
	}

	@Override
	public String debugLog() {
		return this.getActivePower().asString() + "|Limit:" + this.getActivePowerLimit().asString();
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
