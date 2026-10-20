package io.openems.edge.simulator.replay.tariff;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Time-Of-Use Tariff Replay", //
		description = "Replays the day-ahead prices of recorded test days (test-data/days/*.json) against the real clock, as buy and sell prices.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "timeOfUseTariff0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Test-day directory", description = "Directory holding the YYYY-MM-DD.json test days.")
	String directory();

	@AttributeDefinition(name = "Test days", description = "Dates to play in order, one per real day, looping (e.g. 2026-10-20). Empty: every file in the directory, oldest first.")
	String[] dates() default {};

	@AttributeDefinition(name = "Time zone", description = "Local time zone of the test days.")
	String timeZone() default "Europe/Amsterdam";

	@AttributeDefinition(name = "Buy price", description = "Price for buying from the grid, in Euro/MWh, as an expression: [x] is the spot price, [y] the surcharge from the schedule, both in Euro/MWh. E.g. \"(x + y + 130) * 1.21\" for a supplier markup and energy tax of 130 Euro/MWh plus VAT.")
	String buyExpression() default "x + y";

	@AttributeDefinition(name = "Sell price", description = "Price for selling to the grid, in Euro/MWh, as an expression of [x] and [y] as above. E.g. \"0.5 * x - 20\" for half the spot price minus a feed-in fee (no taxes are refunded). Empty: no sell prices are given, and consumers of the tariff fall back to the buy price.")
	String sellExpression() default "";

	@AttributeDefinition(name = "Surcharge schedule", description = "Time-of-day and seasonal surcharges, the [y] above, in Euro/MWh: a JSON array in the format of OpenEMS's ancillary-cost schedule (per year: low, standard and high rates; per quarter: daily time ranges with the rate that applies). Empty: [y] is 0.")
	String surchargeSchedule() default "";

	@AttributeDefinition(name = "Prices published at", description = "Local hour from which the next day's prices are known, as on the day-ahead market.")
	int pricesPublishedAtHour() default 13;

	String webconsole_configurationFactory_nameHint() default "Time-Of-Use Tariff Replay [{id}]";
}
