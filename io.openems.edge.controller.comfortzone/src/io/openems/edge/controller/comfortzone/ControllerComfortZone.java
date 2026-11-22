package io.openems.edge.controller.comfortzone;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerComfortZone extends Controller, OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/** The mode of the current slot, from the active plan. */
		PLANNED_MODE(Doc.of(OpenemsType.STRING)),

		/** The set point sent to the device, in deci-degrees Celsius. */
		PLANNED_SETPOINT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS)),

		/** The planned average electrical power of the current slot. */
		PLANNED_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)),

		/**
		 * Optional external set point (the specification's {@code setpointT}), in
		 * deci-degrees Celsius: replaces the schedule's target while set, and is
		 * what FORCED and FORCED_BOOST aim for.
		 */
		SETPOINT_TEMPERATURE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.DEZIDEGREE_CELSIUS) //
				.accessMode(AccessMode.READ_WRITE)),

		/** No plan yet, or the active plan has run out: the device is left as it is. */
		NO_PLAN_AVAILABLE(Doc.of(Level.INFO)),

		/** No outdoor temperature forecast: the fallback temperature is used. */
		NO_OUTDOOR_FORECAST(Doc.of(Level.INFO)),

		/** The configuration can't be used (schedule or COP/EER points). */
		CONFIGURATION_ERROR(Doc.of(Level.FAULT));

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
