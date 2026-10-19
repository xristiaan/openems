package io.openems.edge.simulator.replay.predictor;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.predictor.api.prediction.LogVerbosity;

@ObjectClassDefinition(//
		name = "Predictor Replay", //
		description = "Predicts PV production from the forecast of recorded test days (test-data/days/*.json), replayed against the real clock.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "predictor0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Channel-Addresses", description = "Channels this Predictor predicts, e.g. 'pv0/ActivePower'.")
	String[] channelAddresses() default { "pv0/ActivePower" };

	@AttributeDefinition(name = "Test-day directory", description = "Directory holding the YYYY-MM-DD.json test days.")
	String directory();

	@AttributeDefinition(name = "Test days", description = "Dates to play in order, one per real day, looping. Empty: every file in the directory, oldest first. Use the same list as the tariff and the PV inverter.")
	String[] dates() default {};

	@AttributeDefinition(name = "Time zone", description = "Local time zone of the test days.")
	String timeZone() default "Europe/Amsterdam";

	@AttributeDefinition(name = "Scale", description = "Multiplies the recorded PV values, e.g. to try another system size. Use the same value as the PV inverter.")
	double scale() default 1.0;

	@AttributeDefinition(name = "Log-Verbosity", description = "The log verbosity.")
	LogVerbosity logVerbosity() default LogVerbosity.NONE;

	String webconsole_configurationFactory_nameHint() default "Predictor Replay [{id}]";
}
