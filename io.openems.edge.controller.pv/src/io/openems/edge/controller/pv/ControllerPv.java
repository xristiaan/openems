package io.openems.edge.controller.pv;

import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.STRING;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerPv extends Controller, OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * The mode currently being executed, from the active plan.
		 */
		PLANNED_MODE(Doc.of(STRING)),

		/**
		 * The planner's output limit for the current slot, in W.
		 */
		PLANNED_LIMIT(Doc.of(INTEGER)//
				.unit(Unit.WATT)),

		/**
		 * The output limit actually commanded to the inverter right now, in W
		 * — the planner's or the Balancing Controller's, after the execution
		 * limits (dwell time, ramp, deadband).
		 */
		COMMANDED_LIMIT(Doc.of(INTEGER)//
				.unit(Unit.WATT)),

		/**
		 * Which controller asked for {@link #COMMANDED_LIMIT}: PLANNER or
		 * BALANCING.
		 */
		COMMAND_SOURCE(Doc.of(STRING)),

		/**
		 * Optional externally supplied target output, {@code P_target}, in W —
		 * production negative (only the magnitude is used), e.g. written via
		 * the MQTT write-back bridge. Honoured in ON, BOOST and FORCED;
		 * without one, FORCED follows the site's consumption (no export).
		 * Empty means no set-point.
		 */
		SETPOINT_POWER(Doc.of(INTEGER)//
				.unit(Unit.WATT)),

		/**
		 * Real-time correction from the Balancing Controller: the output limit
		 * to apply, in W. Holds until the next slot starts; writing a value at
		 * or above {@code P_max} releases it earlier.
		 */
		BALANCING_LIMIT(Doc.of(INTEGER)//
				.unit(Unit.WATT)//
				.accessMode(AccessMode.READ_WRITE)),

		/**
		 * No plan has been pushed into this Controller yet (via
		 * {@code executePlan}), or the active plan's horizon has passed — no
		 * curtailment is applied by the planner while this is set.
		 */
		NO_PLAN_AVAILABLE(Doc.of(Level.INFO)),

		/**
		 * No production forecast was available for the last planning cycle —
		 * planned production is 0, and no planned curtailment is applied.
		 */
		NO_FORECAST_AVAILABLE(Doc.of(Level.INFO));

		final Doc doc;

		ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
