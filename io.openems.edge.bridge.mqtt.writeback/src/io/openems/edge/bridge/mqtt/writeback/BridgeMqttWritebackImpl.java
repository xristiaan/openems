package io.openems.edge.bridge.mqtt.writeback;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

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
import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.bridge.mqtt.api.BridgeMqtt.MqttSubscription;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Subscribes to one MQTT topic and writes each incoming payload into one
 * target Channel. The inbound counterpart to {@code Controller.Api.Mqtt}'s
 * outbound auto-publish: a resource whose measured state lives only on an
 * external platform (Homey, Home Assistant) has no native OpenEMS Channel
 * for that state until something subscribes and writes it in.
 *
 * <p>
 * Deliberately not a {@code Controller} — there is no scheduling decision
 * here, just a reactive data feed. The subscription callback runs on
 * {@link BridgeMqtt}'s own thread, not the OpenEMS Cycle, so this Component
 * needs no {@code run()} at all.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("mqtt")
@Component(//
		name = "Bridge.Mqtt.Writeback", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class BridgeMqttWritebackImpl extends AbstractOpenemsComponent implements OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(BridgeMqttWritebackImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.mqtt_id})(enabled=true))")
	private BridgeMqtt mqtt;

	private Config config;
	private ChannelAddress targetChannelAddress;
	private MqttSubscription subscription;

	public BridgeMqttWritebackImpl() {
		super(//
				OpenemsComponent.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this.targetChannelAddress = ChannelAddress.fromString(config.targetChannel());
		this.subscription = this.mqtt.subscribe(config.topic(), this::onMessage);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.subscription != null) {
			this.subscription.unsubscribe();
			this.subscription = null;
		}
		super.deactivate();
	}

	private void onMessage(io.openems.edge.bridge.mqtt.api.MqttMessage message) {
		try {
			var channel = this.componentManager.getChannel(this.targetChannelAddress);
			// Let the Channel's own type coerce the raw payload (Integer,
			// Double, Boolean, String, ...) — this writeback is generic
			// over whatever state a given target Channel actually holds,
			// not just numeric sensor values.
			channel.setNextValue(message.payloadAsString());
		} catch (Exception e) {
			this.logWarn(this.log,
					"Failed to write MQTT payload into " + this.config.targetChannel() + ": " + e.getMessage());
		}
	}
}
