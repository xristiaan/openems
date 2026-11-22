package io.openems.edge.controller.comfortzone.fixed;

import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.controller.api.Controller;
import io.openems.edge.hvac.api.Hvac;
import io.openems.edge.hvac.api.HvacMode;

/**
 * Holds a heating / cooling device at a fixed on/off, mode and set point,
 * written every cycle. Changing the configuration (e.g. in the web console)
 * takes effect at once.
 */
@Designate(ocd = Config.class, factory = true)
@GenerateTargetsFromReferences("hvac")
@Component(//
		name = "Controller.Hvac.Fixed", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class ControllerHvacFixedImpl extends AbstractOpenemsComponent implements Controller, OpenemsComponent {

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.hvac_id})(enabled=true))")
	private Hvac hvac;

	private Config config;

	public ControllerHvacFixedImpl() {
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

	@Modified
	private void modified(ComponentContext context, Config config) {
		super.modified(context, config.id(), config.alias(), config.enabled());
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void run() throws OpenemsNamedException {
		this.hvac.getOnChannel().setNextWriteValue(this.config.on());
		if (this.config.mode() == HvacMode.HEAT || this.config.mode() == HvacMode.COOL) {
			this.hvac.getModeChannel().setNextWriteValue(this.config.mode());
		}
		this.hvac.getSetpointChannel().setNextWriteValue(toDeciDegrees(this.config.setpoint()));
	}

	/**
	 * Degrees Celsius to deci-degrees.
	 *
	 * @param celsius the temperature
	 * @return deci-degrees
	 */
	static int toDeciDegrees(double celsius) {
		return (int) Math.round(celsius * 10);
	}
}
