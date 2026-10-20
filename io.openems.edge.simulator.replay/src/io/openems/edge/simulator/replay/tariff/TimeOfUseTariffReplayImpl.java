package io.openems.edge.simulator.replay.tariff;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.DoubleBinaryOperator;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParser;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.simulator.replay.ReplayData;
import io.openems.edge.timeofusetariff.api.AncillaryCosts;
import io.openems.edge.timeofusetariff.api.TariffGridSell;
import io.openems.edge.timeofusetariff.api.TimeOfUsePrices;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;
import io.openems.edge.timeofusetariff.api.TouManualHelper;

/**
 * Day-ahead prices from recorded test days, replayed against the real clock,
 * as prices for buying from the grid ({@link TimeOfUseTariff}) and, if a sell
 * expression is configured, for selling to it ({@link TariffGridSell}). Both
 * are expressions of the recorded spot price and a time-of-day/seasonal
 * surcharge schedule, as in OpenEMS's ENTSO-E tariffs. Like the real market,
 * the next day's prices only appear from {@code pricesPublishedAtHour} (13:00
 * by default).
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "TimeOfUseTariff.Replay", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class TimeOfUseTariffReplayImpl extends AbstractOpenemsComponent
		implements TimeOfUseTariffReplay, TimeOfUseTariff, TariffGridSell, OpenemsComponent {

	private static final double KWH_PER_MWH = 1000;

	private final Logger log = LoggerFactory.getLogger(TimeOfUseTariffReplayImpl.class);

	@Reference
	private ComponentManager componentManager;

	private Config config;
	private ReplayData replay;
	private PriceFormula buyFormula;
	private PriceFormula sellFormula;
	private TouManualHelper surcharges;

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
			this.buyFormula = PriceFormula.parse(config.buyExpression());
			this.sellFormula = config.sellExpression().isBlank() ? null : PriceFormula.parse(config.sellExpression());
			this.surcharges = this.surcharges(config.surchargeSchedule());
			this._setLoadFailed(false);
		} catch (IOException | OpenemsNamedException | RuntimeException e) {
			this.logError(this.log, "Unable to load the test days or tariff from [" + config.directory() + "]: "
					+ e.getMessage());
			this.replay = null;
			this._setLoadFailed(true);
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private TouManualHelper surcharges(String schedule) throws OpenemsNamedException {
		if (schedule.isBlank()) {
			return TouManualHelper.EMPTY_TOU_MANUAL_HELPER;
		}
		var clock = this.componentManager.getClock();
		var tasks = AncillaryCosts.parseSchedule(clock, JsonParser.parseString(schedule).getAsJsonArray());
		return new TouManualHelper(clock, tasks, 0.0);
	}

	@Override
	public TimeOfUsePrices getPrices() {
		var prices = this.prices(this.buyFormula);
		this._setPrice(prices.length > 0 ? prices[0] : null);
		return this.toPrices(prices);
	}

	@Override
	public TimeOfUsePrices getGridSellPrices() {
		var prices = this.prices(this.sellFormula);
		this._setSellPrice(prices.length > 0 ? prices[0] : null);
		return this.toPrices(prices);
	}

	private Double[] prices(PriceFormula formula) {
		var replay = this.replay;
		if (replay == null || formula == null) {
			return new Double[0];
		}
		var now = Instant.now(this.componentManager.getClock());
		var surcharges = this.surcharges.getPrices().asArray();
		return pricesEurPerMwh(replay, now, this.config.pricesPublishedAtHour(), formula::apply, surcharges);
	}

	private TimeOfUsePrices toPrices(Double[] prices) {
		if (prices.length == 0) {
			return TimeOfUsePrices.EMPTY_PRICES;
		}
		return TimeOfUsePrices.from(ReplayData.slotStart(Instant.now(this.componentManager.getClock())), prices);
	}

	/**
	 * The known prices from the slot playing now: up to local midnight, plus the
	 * next day once it's past the publication hour.
	 *
	 * @param replay             the test days
	 * @param now                the current instant
	 * @param publishedAtHour    the local hour the next day's prices appear
	 * @param formula            the price from the spot price and the surcharge,
	 *                           all in Euro/MWh
	 * @param surchargeEurPerMwh the surcharge per slot from now; 0 where null or
	 *                           beyond its end
	 * @return the prices in Euro/MWh, one per 15 minutes
	 */
	static Double[] pricesEurPerMwh(ReplayData replay, Instant now, int publishedAtHour,
			DoubleBinaryOperator formula, Double[] surchargeEurPerMwh) {
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
			var surcharge = i < surchargeEurPerMwh.length && surchargeEurPerMwh[i] != null ? surchargeEurPerMwh[i]
					: 0.0;
			out[i] = formula.applyAsDouble(spot * KWH_PER_MWH, surcharge);
		}
		return out;
	}

	private void _setPrice(Double value) {
		this.channel(TimeOfUseTariffReplay.ChannelId.PRICE).setNextValue(value);
	}

	private void _setSellPrice(Double value) {
		this.channel(TimeOfUseTariffReplay.ChannelId.SELL_PRICE).setNextValue(value);
	}

	private void _setLoadFailed(boolean value) {
		this.channel(TimeOfUseTariffReplay.ChannelId.LOAD_FAILED).setNextValue(value);
	}
}
