package io.openems.edge.simulator.replay.tariff;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Time-Of-Use Tariff Replay", //
		description = "Replays the day-ahead prices of recorded test days (test-data/days/*.json) against the real clock.")
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

	@AttributeDefinition(name = "Price add-on", description = "Added to the spot price before the factor, in Euro/kWh (e.g. energy tax and supplier markup).")
	double priceAddEurPerKwh() default 0.0;

	@AttributeDefinition(name = "Price factor", description = "Multiplies the spot price plus add-on (e.g. 1.21 for VAT).")
	double priceFactor() default 1.0;

	@AttributeDefinition(name = "Prices published at", description = "Local hour from which the next day's prices are known, as on the day-ahead market.")
	int pricesPublishedAtHour() default 13;

	String webconsole_configurationFactory_nameHint() default "Time-Of-Use Tariff Replay [{id}]";
}
