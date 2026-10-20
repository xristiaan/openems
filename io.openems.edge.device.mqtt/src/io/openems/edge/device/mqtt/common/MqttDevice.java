package io.openems.edge.device.mqtt.common;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * Channels shared by every device behind a platform bridge (Homey, Home
 * Assistant), reached over MQTT.
 */
public interface MqttDevice extends OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * No state message from the device for longer than the stale timeout
		 * (or none yet): its readings are unknown.
		 */
		STATE_STALE(Doc.of(Level.FAULT)//
				.text("No recent readings from the device")),

		/**
		 * The platform reported the last command as failed.
		 */
		COMMAND_FAILED(Doc.of(Level.WARNING)//
				.text("The platform could not execute the last command")),

		/**
		 * The last command has not been acknowledged within the ack timeout.
		 */
		COMMAND_UNACKNOWLEDGED(Doc.of(Level.WARNING)//
				.text("The last command was not acknowledged")),

		/**
		 * The last value sent to the device, in W.
		 */
		COMMANDED_POWER(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.WATT));

		private final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
