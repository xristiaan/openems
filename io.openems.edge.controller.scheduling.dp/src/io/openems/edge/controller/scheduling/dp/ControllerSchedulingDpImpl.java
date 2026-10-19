package io.openems.edge.controller.scheduling.dp;

import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

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
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.scheduling.api.PlanableController;
import io.openems.edge.timeofusetariff.api.TimeOfUseTariff;

/**
 * The DP (Dynamic Programming) Controller: the Allocator. Collects every
 * registered {@link PlanableController}, runs {@link DpArbitration}'s
 * Tier-1/Tier-2 water-filling loop once per 15-minute slot, and commits the
 * result by calling {@code executePlan} on each resource.
 *
 * <p>
 * <b>Known, deliberate simplifications:</b> {@code availableCapacityW} is a
 * flat {@code gridCapacityW - baseLoadW} every slot, and the export capacity
 * a flat {@code gridExportCapacityW + baseLoadW} (the base load absorbs
 * production too). Local production is not part of either: a producing
 * resource (PV) reports its own forecast and {@link DpArbitration} adds its
 * production to the consumers' capacity per slot. A balancing resource
 * (battery) gets the residual of the other plans and the flat base load.
 * Tier-2 ordering is plain
 * registration order, not the marginal-cost refinement logged as a post-MVP
 * item.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Controller.Scheduling.Dp", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerSchedulingDpImpl extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

	private static final long SLOT_DURATION_SECONDS = 900;
	private static final double MWH_PER_KWH = 1000.0;

	private final Logger log = LoggerFactory.getLogger(ControllerSchedulingDpImpl.class);

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = MULTIPLE)
	private volatile List<PlanableController> planableControllers;

	@Reference(policy = DYNAMIC, policyOption = GREEDY, cardinality = OPTIONAL)
	private volatile TimeOfUseTariff timeOfUseTariff = null;

	private Config config;
	private Instant lastPlannedSlotStart;

	public ControllerSchedulingDpImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				Controller.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) {
		super.activate(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		var now = Instant.now();
		var currentSlotStart = currentSlotStart(now);
		if (currentSlotStart.equals(this.lastPlannedSlotStart)) {
			// Already replanned for this slot; resources execute their own
			// committed plan slot-by-slot independently (see each
			// Controller's own run()) — the DP Controller only needs to
			// act once per slot, not every Cycle.
			return;
		}
		this.replan(now);
		this.lastPlannedSlotStart = currentSlotStart;
	}

	private static Instant currentSlotStart(Instant now) {
		var epochSeconds = now.getEpochSecond();
		return Instant.ofEpochSecond(epochSeconds - epochSeconds % SLOT_DURATION_SECONDS);
	}

	private void replan(Instant now) throws OpenemsNamedException {
		var resources = List.copyOf(this.planableControllers);
		if (resources.isEmpty()) {
			return;
		}

		var priceEurPerKwh = this.priceForecast();
		var availableCapacityW = this.availableCapacity(priceEurPerKwh.length);
		var availableExportCapacityW = this.availableExportCapacity(priceEurPerKwh.length);

		var baseLoadW = new double[priceEurPerKwh.length];
		Arrays.fill(baseLoadW, this.config.baseLoadW());

		var result = DpArbitration.runDetailed(resources, priceEurPerKwh, availableCapacityW,
				availableExportCapacityW, baseLoadW, this.config.maxRounds(),
				this.config.convergenceThresholdFraction());
		if (this.config.logPlan()) {
			this.logInfo(this.log, PlanTable.format(currentSlotStart(now), ZoneId.systemDefault(), priceEurPerKwh,
					this.config.baseLoadW(), result));
		}

		for (var resource : resources) {
			resource.executePlan(now);
		}
	}

	private double[] priceForecast() {
		var slotCount = this.config.planningHorizonSlots();
		var tariff = this.timeOfUseTariff;
		var fallbackPriceEurPerKwh = this.config.fallbackPriceEurPerKwh();

		if (tariff == null) {
			return flatPrice(slotCount, fallbackPriceEurPerKwh);
		}
		var pricesEurPerMwh = tariff.getPrices().asArray();
		if (pricesEurPerMwh.length == 0) {
			return flatPrice(slotCount, fallbackPriceEurPerKwh);
		}

		var out = new double[slotCount];
		for (var i = 0; i < slotCount; i++) {
			var priceEurPerMwh = i < pricesEurPerMwh.length ? pricesEurPerMwh[i]
					: pricesEurPerMwh[pricesEurPerMwh.length - 1];
			out[i] = priceEurPerMwh == null ? fallbackPriceEurPerKwh : priceEurPerMwh / MWH_PER_KWH;
		}
		return out;
	}

	private static double[] flatPrice(int slotCount, double priceEurPerKwh) {
		var out = new double[slotCount];
		Arrays.fill(out, priceEurPerKwh);
		return out;
	}

	private double[] availableCapacity(int slotCount) {
		var out = new double[slotCount];
		Arrays.fill(out, Math.max(0, this.config.gridCapacityW() - this.config.baseLoadW()));
		return out;
	}

	private double[] availableExportCapacity(int slotCount) {
		var out = new double[slotCount];
		Arrays.fill(out, Math.max(0, this.config.gridExportCapacityW() + this.config.baseLoadW()));
		return out;
	}
}
