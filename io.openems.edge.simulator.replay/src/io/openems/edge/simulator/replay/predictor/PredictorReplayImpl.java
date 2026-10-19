package io.openems.edge.simulator.replay.predictor;

import java.io.IOException;
import java.nio.file.Path;
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

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.ChannelAddress;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.predictor.api.prediction.AbstractPredictor;
import io.openems.edge.predictor.api.prediction.Prediction;
import io.openems.edge.predictor.api.prediction.Predictor;
import io.openems.edge.simulator.replay.ReplayData;

/**
 * The PV forecast of recorded test days, replayed against the real clock. A
 * slot without a recorded forecast predicts its recorded actual (a perfect
 * forecast), so test days fetched after they started still work.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Predictor.Replay", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class PredictorReplayImpl extends AbstractPredictor implements Predictor, OpenemsComponent {

	/** 48 hours of quarters, like the other OpenEMS predictors. */
	private static final int SLOTS = 48 * 4;

	private final Logger log = LoggerFactory.getLogger(PredictorReplayImpl.class);

	@Reference
	private ComponentManager componentManager;

	private ReplayData replay;
	private double scale;

	public PredictorReplayImpl() {
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsNamedException {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.logVerbosity(),
				config.channelAddresses());
		this.scale = config.scale();
		try {
			var zone = ZoneId.of(config.timeZone());
			var today = Instant.now(this.componentManager.getClock()).atZone(zone).toLocalDate();
			this.replay = ReplayData.load(Path.of(config.directory()), config.dates(), zone, today);
		} catch (IOException | RuntimeException e) {
			this.logError(this.log, "Unable to load test days from [" + config.directory() + "]: " + e.getMessage());
			this.replay = null;
		}
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
		var replay = this.replay;
		if (replay == null) {
			return Prediction.EMPTY_PREDICTION;
		}
		var start = ReplayData.slotStart(Instant.now(this.componentManager.getClock()));
		return Prediction.from(start, predictedW(replay, start, SLOTS, this.scale));
	}

	static Integer[] predictedW(ReplayData replay, Instant start, int count, double scale) {
		var slots = replay.slotsFrom(start, count);
		var out = new Integer[count];
		for (var i = 0; i < count; i++) {
			out[i] = (int) Math.round(slots.get(i).pvPredictedW() * scale);
		}
		return out;
	}
}
