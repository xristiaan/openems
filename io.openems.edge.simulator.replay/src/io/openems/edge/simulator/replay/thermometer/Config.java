package io.openems.edge.simulator.replay.thermometer;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Simulator Thermometer Replay", //
		description = "An outdoor thermometer reading the recorded outdoor temperature of test days (test-data/days/*.json), replayed against the real clock.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "outdoor0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Test-day directory", description = "Directory holding the YYYY-MM-DD.json test days.")
	String directory();

	@AttributeDefinition(name = "Test days", description = "Dates to play in order, one per real day, looping. Empty: every file in the directory, oldest first. Use the same list as the tariff, the PV inverter and the predictors.")
	String[] dates() default {};

	@AttributeDefinition(name = "Time zone", description = "Local time zone of the test days.")
	String timeZone() default "Europe/Amsterdam";

	String webconsole_configurationFactory_nameHint() default "Simulator Thermometer Replay [{id}]";
}
