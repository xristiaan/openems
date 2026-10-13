package io.openems.edge.controller.scheduling.dp;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Controller Scheduling Dp", //
		description = "The DP (Dynamic Programming) Controller: allocates power budget to every "
				+ "registered Plan-able Controller via iterative Least-Slack-Time/Tier-1-Tier-2 "
				+ "water-filling, then commits each resource's resulting plan.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlSchedulingDp0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Grid capacity", description = "The installation's max grid import capacity, in watts (e.g. 3x25A at 230V ~= 17250W).")
	double gridCapacityW() default 17_250;

	@AttributeDefinition(name = "Base load", description = "Fixed deduction for small, not-individually-planned usage (lights, computers, etc.), in watts. The MVP's flat stand-in for the production product's domestic usage forecast.")
	double baseLoadW() default 500;

	@AttributeDefinition(name = "Planning horizon", description = "Number of 15-minute slots to plan ahead — 92, the guaranteed-minimum slot count accounting for a short DST day, not 96.")
	int planningHorizonSlots() default 92;

	@AttributeDefinition(name = "Fallback energy price", description = "Flat price used, in Euro/kWh, when no TimeOfUseTariff is available.")
	double fallbackPriceEurPerKwh() default 0.30;

	@AttributeDefinition(name = "Max rounds", description = "n: the maximum number of Least-Slack-Time/water-filling rounds per re-plan.")
	int maxRounds() default 4;

	@AttributeDefinition(name = "Convergence threshold", description = "y: stop iterating once no resource's allocated budget changes by more than this fraction (e.g. 0.05 = 5%) between rounds.")
	double convergenceThresholdFraction() default 0.05;

	String webconsole_configurationFactory_nameHint() default "Controller Scheduling Dp [{id}]";

}
