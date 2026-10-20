package io.openems.edge.device.mqtt.common;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.bridge.mqtt.api.BridgeMqtt.MqttSubscription;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Connects {@link DeviceReadings} and {@link CommandSender} to a
 * {@link BridgeMqtt}: subscribes to the device's state topic and the
 * platform's ack topic, publishes commands, and keeps the shared
 * {@link MqttDevice} Channels up to date.
 */
public class DeviceLink {

	/** The placeholder for the device ID in a topic. */
	public static final String DEVICE_ID_PLACEHOLDER = "{deviceId}";

	private final BridgeMqtt mqtt;
	private final DeviceReadings readings;
	private final CommandSender sender;
	private final String commandTopic;
	private final List<MqttSubscription> subscriptions = new ArrayList<>();

	/**
	 * Creates the link and subscribes.
	 *
	 * @param mqtt         the MQTT bridge
	 * @param deviceId     the platform device ID, substituted for
	 *                     {@value #DEVICE_ID_PLACEHOLDER} in the topics
	 * @param stateTopic   the device's state topic
	 * @param commandTopic the platform's command topic
	 * @param ackTopic     the platform's ack topic
	 * @param readings     the readings to fill
	 * @param sender       the command sender
	 */
	public DeviceLink(BridgeMqtt mqtt, String deviceId, String stateTopic, String commandTopic, String ackTopic,
			DeviceReadings readings, CommandSender sender) {
		this.mqtt = mqtt;
		this.readings = readings;
		this.sender = sender;
		this.commandTopic = topic(commandTopic, deviceId);
		this.subscriptions.add(mqtt.subscribe(topic(stateTopic, deviceId),
				message -> readings.onMessage(message.payloadAsString(), Instant.now())));
		this.subscriptions.add(mqtt.subscribe(topic(ackTopic, deviceId),
				message -> sender.onAck(message.payloadAsString())));
	}

	private static String topic(String template, String deviceId) {
		return template.replace(DEVICE_ID_PLACEHOLDER, deviceId);
	}

	/**
	 * Unsubscribes.
	 */
	public void close() {
		for (var subscription : this.subscriptions) {
			subscription.unsubscribe();
		}
		this.subscriptions.clear();
	}

	/**
	 * The readings.
	 *
	 * @return the readings
	 */
	public DeviceReadings readings() {
		return this.readings;
	}

	/**
	 * Sends the wanted value if the {@link CommandSender} says it is due.
	 *
	 * @param now      the current time
	 * @param valueW   the wanted value, in W
	 * @param fraction the value as a fraction of the rating, or null
	 */
	public void send(Instant now, int valueW, Double fraction) {
		this.sender.next(now, valueW, fraction).ifPresent(json -> this.mqtt.publish(this.commandTopic, json));
	}

	/**
	 * Updates the shared {@link MqttDevice} Channels.
	 *
	 * @param component the device Component
	 * @param now       the current time
	 */
	public void updateChannels(OpenemsComponent component, Instant now) {
		component.channel(MqttDevice.ChannelId.STATE_STALE).setNextValue(this.readings.isStale(now));
		component.channel(MqttDevice.ChannelId.COMMAND_FAILED).setNextValue(this.sender.lastCommandFailed());
		component.channel(MqttDevice.ChannelId.COMMAND_UNACKNOWLEDGED).setNextValue(this.sender.unacknowledged(now));
		component.channel(MqttDevice.ChannelId.COMMANDED_POWER).setNextValue(this.sender.lastValueW());
	}
}
