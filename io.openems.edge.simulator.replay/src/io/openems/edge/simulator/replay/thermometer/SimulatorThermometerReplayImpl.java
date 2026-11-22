package io.openems.edge.simulator.replay.thermometer;

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
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.simulator.replay.ReplayData;
import io.openems.edge.thermometer.api.Thermometer;

/**
 * Reads the recorded outdoor temperature of test days (the actual, else the
 * forecast) as a {@link Thermometer}; null where a test day has none.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Simulator.Thermometer.Replay", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE //
})
public class SimulatorThermometerReplayImpl extends AbstractOpenemsComponent
		implements Thermometer, OpenemsComponent, EventHandler {

	private final Logger log = LoggerFactory.getLogger(SimulatorThermometerReplayImpl.class);

	@Reference
	private ComponentManager componentManager;

	private ReplayData replay;

	public SimulatorThermometerReplayImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Thermometer.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
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
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		var replay = this.replay;
		var now = Instant.now(this.componentManager.getClock());
		this._setTemperature(replay == null ? null : ReplayData.toDeciDegrees(replay.slotAt(now).outdoorNowC()));
	}

	@Override
	public String debugLog() {
		return this.getTemperature().asString();
	}
}
