package io.openems.edge.simulator.replay.tariff;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.simulator.replay.ReplayData;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

/**
 * Day-ahead prices from recorded test days, replayed against the real clock.
 * Like the real market, the next day's prices only appear from
 * {@code pricesPublishedAtHour} (13:00 by default).
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "TimeOfUseTariff.Replay", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class TimeOfUseTariffReplayImpl extends AbstractOpenemsComponent
		implements TimeOfUseTariffReplay, TimeOfUseTariff, OpenemsComponent {

	private static final double KWH_PER_MWH = 1000;

	private final Logger log = LoggerFactory.getLogger(TimeOfUseTariffReplayImpl.class);

	@Reference
	private ComponentManager componentManager;

	private Config config;
	private ReplayData replay;

	public TimeOfUseTariffReplayImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				TimeOfUseTariffReplay.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		try {
			var zone = ZoneId.of(config.timeZone());
			var today = Instant.now(this.componentManager.getClock()).atZone(zone).toLocalDate();
			this.replay = ReplayData.load(Path.of(config.directory()), config.dates(), zone, today);
			this._setLoadFailed(false);
		} catch (IOException | RuntimeException e) {
			this.logError(this.log, "Unable to load test days from [" + config.directory() + "]: " + e.getMessage());
			this.replay = null;
			this._setLoadFailed(true);
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public TimeOfUsePrices getPrices() {
		var replay = this.replay;
		if (replay == null) {
			return TimeOfUsePrices.EMPTY_PRICES;
		}
		var now = Instant.now(this.componentManager.getClock());
		var prices = pricesEurPerMwh(replay, now, this.config.pricesPublishedAtHour(),
				this.config.priceAddEurPerKwh(), this.config.priceFactor());
		this._setPrice(prices.length > 0 ? prices[0] : null);
		return TimeOfUsePrices.from(ReplayData.slotStart(now), prices);
	}

	/**
	 * The known prices from the slot playing now: up to local midnight, plus the
	 * next day once it's past the publication hour.
	 *
	 * @param replay          the test days
	 * @param now             the current instant
	 * @param publishedAtHour the local hour the next day's prices appear
	 * @param addEurPerKwh    added to the spot price
	 * @param factor          multiplies the spot price plus add-on
	 * @return the prices in Euro/MWh, one per 15 minutes
	 */
	static Double[] pricesEurPerMwh(ReplayData replay, Instant now, int publishedAtHour, double addEurPerKwh,
			double factor) {
		var end = replay.nextMidnight(now);
		if (now.atZone(replay.zone()).getHour() >= publishedAtHour) {
			end = replay.nextMidnight(end);
		}
		var start = ReplayData.slotStart(now);
		var count = (int) Duration.between(start, end).dividedBy(ReplayData.SLOT);

		var slots = replay.slotsFrom(start, count);
		var out = new Double[count];
		for (var i = 0; i < count; i++) {
			var spot = slots.get(i).priceEurPerKwh();
			if (spot == null) {
				// TimeOfUsePrices takes no gaps: stop at the first missing price
				var known = new Double[i];
				System.arraycopy(out, 0, known, 0, i);
				return known;
			}
			out[i] = (spot + addEurPerKwh) * factor * KWH_PER_MWH;
		}
		return out;
	}

	private void _setPrice(Double value) {
		this.channel(TimeOfUseTariffReplay.ChannelId.PRICE).setNextValue(value);
	}

	private void _setLoadFailed(boolean value) {
		this.channel(TimeOfUseTariffReplay.ChannelId.LOAD_FAILED).setNextValue(value);
	}
}
