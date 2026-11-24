package io.openems.edge.device.mqtt.thermometer;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
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
import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.device.mqtt.common.DeviceLink;
import io.openems.edge.device.mqtt.common.DeviceReadings;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.thermometer.api.Thermometer;

/**
 * A temperature from an MQTT state topic, e.g. a Homey zone's temperature
 * (bridge-homey's zone readings: the average of the zone's temperature
 * sensors), so a Controller's room temperature can come from any source.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("mqtt")
@Component(//
		name = "Thermometer.Mqtt", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE)
@EventTopics(EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE)
public class ThermometerMqttImpl extends AbstractOpenemsComponent
		implements ThermometerMqtt, Thermometer, MqttDevice, OpenemsComponent, EventHandler {

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.mqtt_id})(enabled=true))")
	private BridgeMqtt mqtt;

	private Config config;
	private DeviceLink link;

	public ThermometerMqttImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Thermometer.ChannelId.values(), //
				MqttDevice.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		var readings = new DeviceReadings("", null, false, Duration.ofSeconds(config.staleAfterSeconds()));
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
		var celsius = this.link.readings().number(this.config.temperatureField(), now);
		this._setTemperature(celsius == null ? null : (int) Math.round(celsius * 10));
		this.link.updateChannels(this, now);
	}

	@Override
	public String debugLog() {
		var deci = this.getTemperature().get();
		return deci == null ? "UNDEFINED" : String.format(Locale.ROOT, "%.1f °C", deci / 10.0);
	}
}
