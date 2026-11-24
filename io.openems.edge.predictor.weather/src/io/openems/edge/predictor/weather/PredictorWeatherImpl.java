package io.openems.edge.predictor.weather;

import java.time.Instant;
import java.util.List;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.predictor.api.prediction.AbstractPredictor;
import io.openems.edge.predictor.api.prediction.Prediction;
import io.openems.edge.predictor.api.prediction.Predictor;
import io.openems.edge.weather.api.QuarterlyWeatherSnapshot;
import io.openems.edge.weather.api.Weather;

/**
 * The outdoor temperature forecast from a {@link Weather} component, per
 * quarter, in deci-degrees Celsius. Without a (valid) weather forecast it
 * predicts nothing, so a consumer falls back to its own default.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Predictor.Weather", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class PredictorWeatherImpl extends AbstractPredictor implements Predictor, OpenemsComponent {

	/** 48 hours of quarters, like the other OpenEMS predictors. */
	private static final int SLOTS = 48 * 4;
	private static final long QUARTER_SECONDS = 15 * 60;

	private final Logger log = LoggerFactory.getLogger(PredictorWeatherImpl.class);

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, //
			cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Weather weather;

	public PredictorWeatherImpl() {
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.logVerbosity(),
				config.channelAddresses());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ClockProvider getClockProvider() {
		return this.componentManager;
	}

	@Override
	protected Prediction createNewPrediction(ChannelAddress channelAddress) {
		var weather = this.weather;
		if (weather == null) {
			return Prediction.EMPTY_PREDICTION;
		}
		var start = quarterStart(Instant.now(this.componentManager.getClock()));
		try {
			return Prediction.from(start, deciDegrees(start, weather.getQuarterlyWeatherForecast(SLOTS), SLOTS));
		} catch (OpenemsException | RuntimeException e) {
			this.logWarn(this.log, "No weather forecast: " + e.getMessage());
			return Prediction.EMPTY_PREDICTION;
		}
	}

	/**
	 * The forecast temperatures per quarter from {@code start}, in deci-degrees
	 * Celsius; null where the forecast has no (finite) temperature.
	 *
	 * @param start    the first quarter
	 * @param forecast the weather forecast, in any order
	 * @param count    the number of quarters
	 * @return one value per quarter
	 */
	static Integer[] deciDegrees(Instant start, List<QuarterlyWeatherSnapshot> forecast, int count) {
		var out = new Integer[count];
		for (var snapshot : forecast) {
			var seconds = snapshot.datetime().toInstant().getEpochSecond() - start.getEpochSecond();
			if (seconds < 0 || seconds % QUARTER_SECONDS != 0 || !Double.isFinite(snapshot.temperature())) {
				continue;
			}
			var index = seconds / QUARTER_SECONDS;
			if (index < count) {
				out[(int) index] = (int) Math.round(snapshot.temperature() * 10);
			}
		}
		return out;
	}

	private static Instant quarterStart(Instant time) {
		var seconds = time.getEpochSecond();
		return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, QUARTER_SECONDS));
	}
}
