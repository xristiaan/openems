package io.openems.edge.meter.acrel;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.common.types.MeterType;

@ObjectClassDefinition(//
		name = "Meter Acrel DTSD1352-4S", //
		description = "One measured device on the Acrel DTSD1352-4S four-channel three-phase meter, over Modbus RTU: "
				+ "any combination of the meter's current transformer inputs. Configure one Component per device, "
				+ "all on the same Modbus bridge.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Inputs", description = "The current transformer inputs that measure this device, "
			+ "as channel (1-4) and terminal (A-C), comma-separated: e.g. '1A' for a single-phase boiler on CH1 A, "
			+ "'2A,2B,2C' for a heat pump on all of CH2. Terminal A measures grid phase L1, B L2 and C L3; "
			+ "write e.g. '2A=L2' when a channel's terminal is wired to another phase.")
	String inputs() default "1A,1B,1C";

	@AttributeDefinition(name = "Meter-Type", description = "What is this device? "
			+ "The meter measures power flowing towards the load as positive, so a production device "
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
