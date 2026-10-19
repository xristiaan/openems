package io.openems.edge.simulator.replay.pvinverter;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface SimulatorPvInverterReplay extends ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * What the sun would give right now, before ActivePowerLimit — the
		 * difference with ActivePower is the curtailed power.
		 */
		UNCURTAILED_POWER(Doc.of(OpenemsType.INTEGER)//
				.unit(Unit.WATT)),

		/**
		 * The test days could not be loaded; no production is simulated.
		 */
		LOAD_FAILED(Doc.of(Level.FAULT));

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
