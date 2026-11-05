package io.openems.edge.meter.acrel;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(//
		name = "Meter Acrel DTSD1352-4S", //
		description = "One measuring channel (CH1-CH4) of the Acrel DTSD1352-4S four-channel three-phase meter, "
				+ "over Modbus RTU. Configure one Component per channel in use, all on the same Modbus bridge.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Channel", description = "The meter's measuring channel: 1-4 for CH1-CH4")
	int channel() default 1;

	@AttributeDefinition(name = "Meter-Type", description = "What is measured by this channel? "
			+ "The meter measures power flowing towards the load as positive, so a production channel "
			+ "(e.g. PV) reads negative and is reported as positive production.")
	MeterType type() default MeterType.GRID;

	@AttributeDefinition(name = "CT turns", description = "How many times the wire is looped through each "
			+ "current transformer. More turns measure small currents more accurately; the measured "
			+ "current and power are divided by this number.")
	int ctTurns() default 1;

	@AttributeDefinition(name = "Invert power", description = "Quick fix for current transformers fitted the "
			+ "wrong way round: multiplies power with -1. Better: turn the transformers.")
	boolean invert() default false;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of the Modbus bridge (Bridge.Modbus.Serial).")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The meter's Modbus address (1-247).")
	int modbusUnitId() default 1;

	String webconsole_configurationFactory_nameHint() default "Meter Acrel DTSD1352-4S [{id}]";

}
