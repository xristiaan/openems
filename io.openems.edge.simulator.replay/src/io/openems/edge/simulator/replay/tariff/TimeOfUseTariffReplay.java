package io.openems.edge.simulator.replay.tariff;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.timeofusetariff.api.TariffGridSell;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

public interface TimeOfUseTariffReplay extends TimeOfUseTariff, TariffGridSell, OpenemsComponent {

	enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/**
		 * The buy price of the slot playing now, in Euro/MWh.
		 */
		PRICE(Doc.of(OpenemsType.DOUBLE)//
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR)),

		/**
		 * The sell price of the slot playing now, in Euro/MWh; undefined without a
		 * sell expression.
		 */
		SELL_PRICE(Doc.of(OpenemsType.DOUBLE)//
				.unit(Unit.MONEY_PER_MEGAWATT_HOUR)),

		/**
		 * The test days could not be loaded; no prices are given.
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
