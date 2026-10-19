package io.openems.edge.bridge.mqtt.writeback;

import io.openems.common.test.AbstractComponentConfig;

@SuppressWarnings("all")
public class MyConfig extends AbstractComponentConfig implements Config {

	private final String topic;
	private final String targetChannel;
	private final String mqttId;

	public MyConfig(String id, String topic, String targetChannel, String mqttId) {
		super(Config.class, id);
		this.topic = topic;
		this.targetChannel = targetChannel;
		this.mqttId = mqttId;
	}

	@Override
	public String alias() {
		return "";
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public String topic() {
		return this.topic;
	}

	@Override
	public String targetChannel() {
		return this.targetChannel;
	}

	@Override
	public String mqtt_id() {
		return this.mqttId;
	}

	@Override
	public String mqtt_target() {
		return "(enabled=true)";
	}

	@Override
	public String webconsole_configurationFactory_nameHint() {
		return "Bridge Mqtt Writeback [" + this.id() + "]";
	}
}
