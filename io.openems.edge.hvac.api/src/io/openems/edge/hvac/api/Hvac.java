package io.openems.edge.hvac.api;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.BooleanWriteChannel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.component.OpenemsComponent;

/**
 * A heating / cooling device that regulates itself to a set point — an air
 * conditioner, a heat pump zone, a thermostat. What a Comfort Zone Controller
 * sends it is the same whatever the connection (Modbus, a Home Automation
 * Platform, a native API): on or off, heat or cool, and the set point. The
 * device's own thermostat does the rest.
 *
 * <p>
 * The written values are read back on the same channels (the device's actual
 * state), so a manual change on the device can be detected.
 */
public interface Hvac extends OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/** On (true) or off (false). */
		ON(Doc.of(OpenemsType.BOOLEAN) //
				.accessMode(AccessMode.READ_WRITE)),

		/** Heat or cool. */
		MODE(Doc.of(HvacMode.values()) //
				.accessMode(AccessMode.READ_WRITE)),

		/** The set point, in deci-degrees Celsius. */
		SETPOINT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.accessMode(AccessMode.READ_WRITE));

		private final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Gets the channel for {@link ChannelId#ON}.
	 *
	 * @return the channel
	 */
	default BooleanWriteChannel getOnChannel() {
		return this.channel(ChannelId.ON);
	}

	/**
	 * Gets the channel for {@link ChannelId#MODE}.
	 *
	 * @return the channel
	 */
	default EnumWriteChannel getModeChannel() {
		return this.channel(ChannelId.MODE);
	}

	/**
	 * Gets the channel for {@link ChannelId#SETPOINT}.
	 *
	 * @return the channel
	 */
	default IntegerWriteChannel getSetpointChannel() {
		return this.channel(ChannelId.SETPOINT);
	}
}
