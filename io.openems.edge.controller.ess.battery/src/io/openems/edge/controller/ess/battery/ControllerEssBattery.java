package io.openems.edge.controller.ess.battery;

import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.STRING;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;

public interface ControllerEssBattery extends Controller, OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * The mode currently being executed, from the active plan.
		 */
		PLANNED_MODE(Doc.of(STRING)),

		/**
		 * The AC power currently being commanded to the Ess, in OpenEMS's own
		 * sign convention (negative = charge, positive = discharge) — the
		 * opposite of this controller's internal charge-positive convention.
		 */
		PLANNED_POWER(Doc.of(INTEGER)//
				.unit(Unit.WATT)),

		/**
		 * No plan has been pushed into this Controller yet (via
		 * {@code executePlan}), or the active plan's horizon has passed —
		 * this Controller holds no power constraint while this is set.
		 * Expected until the DP Controller exists to supply plans.
		 */
		NO_PLAN_AVAILABLE(Doc.of(Level.INFO));

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
