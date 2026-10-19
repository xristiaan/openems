package io.openems.edge.simulator.replay.pvinverter;

import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

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

import io.openems.common.types.MeterType;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.simulator.replay.ReplayData;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * Produces the recorded PV actuals of test days (else their forecast), capped
 * by ActivePowerLimit — the counterpart of {@code Simulator.PvInverter} for
 * clock-aligned, recorded data.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Simulator.PvInverter.Replay", //
		immediate = true, configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class SimulatorPvInverterReplayImpl extends AbstractOpenemsComponent implements SimulatorPvInverterReplay,
		ManagedSymmetricPvInverter, ElectricityMeter, OpenemsComponent, TimedataProvider, EventHandler {

	private final Logger log = LoggerFactory.getLogger(SimulatorPvInverterReplayImpl.class);

	private final CalculateEnergyFromPower calculateProductionEnergy = new CalculateEnergyFromPower(this,
			ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY);

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	private ReplayData replay;
	private double scale;

	public SimulatorPvInverterReplayImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				SimulatorPvInverterReplay.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.scale = config.scale();
		try {
			var zone = ZoneId.of(config.timeZone());
			var today = Instant.now(this.componentManager.getClock()).atZone(zone).toLocalDate();
			this.replay = ReplayData.load(Path.of(config.directory()), config.dates(), zone, today);
			this.channel(SimulatorPvInverterReplay.ChannelId.LOAD_FAILED).setNextValue(false);
		} catch (IOException | RuntimeException e) {
			this.logError(this.log, "Unable to load test days from [" + config.directory() + "]: " + e.getMessage());
			this.replay = null;
			this.channel(SimulatorPvInverterReplay.ChannelId.LOAD_FAILED).setNextValue(true);
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
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.updateChannels();
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE ->
			this.calculateProductionEnergy.update(this.getActivePower().get());
		}
	}

	private void updateChannels() {
		// copy write value to read value
		this._setActivePowerLimit(this.getActivePowerLimitChannel().getNextWriteValueAndReset().orElse(null));

		var replay = this.replay;
		if (replay == null) {
			this.channel(SimulatorPvInverterReplay.ChannelId.UNCURTAILED_POWER).setNextValue(null);
			this._setActivePower(null);
			return;
		}
		var now = Instant.now(this.componentManager.getClock());
		var uncurtailed = (int) Math.round(replay.slotAt(now).pvProducedW() * this.scale);
		this.channel(SimulatorPvInverterReplay.ChannelId.UNCURTAILED_POWER).setNextValue(uncurtailed);

		var limit = this.getActivePowerLimit();
		this._setActivePower(limit.isDefined() ? Math.min(uncurtailed, limit.get()) : uncurtailed);
	}

	@Override
	public String debugLog() {
		return this.getActivePower().asString();
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.PRODUCTION;
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
