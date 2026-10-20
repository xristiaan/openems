package io.openems.edge.device.mqtt.ess;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Duration;
import java.time.Instant;

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

import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.bridge.mqtt.api.BridgeMqtt;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.device.mqtt.common.CommandSender;
import io.openems.edge.device.mqtt.common.DeviceLink;
import io.openems.edge.device.mqtt.common.DeviceReadings;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

/**
 * A battery behind a platform bridge (Homey, Home Assistant). Power and SoC
 * come from the device's MQTT state topic; the power OpenEMS assigns it (via
 * {@link #applyPower}) is sent as a {@code set-power} command — in W,
 * charge-positive like the rest of this project, and as a fraction of the
 * maximum power in that direction. While the readings are stale, no
 * charging or discharging is allowed.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("mqtt")
@Component(//
		name = "Ess.Mqtt", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
public class EssMqttImpl extends AbstractOpenemsComponent implements EssMqtt, ManagedSymmetricEss, SymmetricEss,
		MqttDevice, OpenemsComponent, TimedataProvider, EventHandler {

	private final CalculateEnergyFromPower calculateChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);

	@Reference
	private Power power;

	@Reference
	private ComponentManager componentManager;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.mqtt_id})(enabled=true))")
	private BridgeMqtt mqtt;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile Timedata timedata = null;

	private Config config;
	private DeviceLink link;

	public EssMqttImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				MqttDevice.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
		this._setCapacity(config.capacityWh());
		this._setMaxApparentPower(Math.max(config.maxChargePowerW(), config.maxDischargePowerW()));
		this.channel(SymmetricEss.ChannelId.GRID_MODE).setNextValue(GridMode.ON_GRID);
		// OpenEMS counts discharging as positive: invert a charge-positive reading.
		var readings = new DeviceReadings(config.powerField(), config.socField(), config.chargingReportedPositive(),
				Duration.ofSeconds(config.staleAfterSeconds()));
		var sender = new CommandSender(config.deviceId(), config.id(), config.deadbandW(),
				Duration.ofSeconds(config.minCommandIntervalSeconds()),
				Duration.ofSeconds(config.resendIntervalSeconds()), Duration.ofSeconds(config.ackTimeoutSeconds()));
		this.link = new DeviceLink(this.mqtt, config.deviceId(), config.stateTopic(), config.commandTopic(),
				config.ackTopic(), readings, sender);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		if (this.link != null) {
			this.link.close();
			this.link = null;
		}
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled() || this.link == null) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_PROCESS_IMAGE -> this.updateReadings();
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.calculateEnergy();
		}
	}

	private void updateReadings() {
		var now = Instant.now(this.componentManager.getClock());
		var readings = this.link.readings();
		var soc = readings.soc(now);
		this._setActivePower(readings.powerW(now));
		this._setSoc(soc);
		this.link.updateChannels(this, now);

		// Unknown state: allow nothing. Full or empty: stop in that direction.
		var stale = readings.isStale(now);
		var allowedChargeW = stale || soc == null || soc >= 100 ? 0 : this.config.maxChargePowerW();
		var allowedDischargeW = stale || soc == null || soc <= 0 ? 0 : this.config.maxDischargePowerW();
		this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER).setNextValue(-allowedChargeW);
		this._setAllowedDischargePower(allowedDischargeW);
	}

	@Override
	public void applyPower(int activePower, int reactivePower) {
		if (this.link == null) {
			return;
		}
		var now = Instant.now(this.componentManager.getClock());
		this.link.send(now, chargePositiveW(activePower), this.fraction(activePower));
	}

	/**
	 * OpenEMS's discharge-positive ESS power as this project's charge-positive
	 * command value.
	 *
	 * @param activePower the ESS power, discharge-positive
	 * @return the command value, charge-positive
	 */
	static int chargePositiveW(int activePower) {
		return -activePower;
	}

	private Double fraction(int activePower) {
		var max = activePower < 0 ? this.config.maxChargePowerW() : this.config.maxDischargePowerW();
		return max > 0 ? (double) chargePositiveW(activePower) / max : null;
	}

	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public int getPowerPrecision() {
		return 1;
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString() //
				+ "|Allowed:" + this.getAllowedChargePower().asStringWithoutUnit() + ";"
				+ this.getAllowedDischargePower().asString();
	}

	private void calculateEnergy() {
		var activePower = this.getActivePower().get();
		if (activePower == null) {
			this.calculateChargeEnergy.update(null);
			this.calculateDischargeEnergy.update(null);
		} else if (activePower > 0) {
			this.calculateChargeEnergy.update(0);
			this.calculateDischargeEnergy.update(activePower);
		} else {
			this.calculateChargeEnergy.update(-activePower);
			this.calculateDischargeEnergy.update(0);
		}
	}

	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}
}
