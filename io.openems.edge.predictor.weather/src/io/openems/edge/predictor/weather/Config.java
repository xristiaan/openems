package io.openems.edge.predictor.weather;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.predictor.api.prediction.LogVerbosity;

@ObjectClassDefinition(//
		name = "Predictor Weather", //
		description = "Predicts the outdoor temperature from the weather forecast (a Weather component, e.g. Weather Open-Meteo), per quarter, in deci-degrees Celsius like a Thermometer's Temperature.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "predictorWeather0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Channel-Addresses", description = "Channels this Predictor predicts, e.g. 'outdoor0/Temperature' (the Comfort Zone Controller's outdoor temperature forecast channel).")
	String[] channelAddresses() default { "outdoor0/Temperature" };

	@AttributeDefinition(name = "Log-Verbosity", description = "The log verbosity.")
	LogVerbosity logVerbosity() default LogVerbosity.NONE;

	String webconsole_configurationFactory_nameHint() default "Predictor Weather [{id}]";
}
