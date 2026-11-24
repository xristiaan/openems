package io.openems.edge.controller.comfortzone;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.hvac.api.HvacMode;

@ObjectClassDefinition(//
		name = "Controller Comfort Zone", //
		description = "A Plan-able Controller for a room heated or cooled by one device (an air conditioner, radiators): "
				+ "plans the device's mode per slot against prices, the outdoor temperature forecast and the comfort cost, "
				+ "and sends it on/off, heat/cool and a set point. Single-node model (no floor or wall heating slab).")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "ctrlComfortZone0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "HVAC device-ID", description = "The heating / cooling device (an Hvac component).")
	String hvac_id();

	@AttributeDefinition(name = "Room thermometer-ID", description = "The thermometer measuring the room temperature.")
	String room_id();

	@AttributeDefinition(name = "Outdoor temperature forecast channel", description = "The channel whose prediction is the outdoor temperature per slot, e.g. 'outdoor0/Temperature' (deci-degrees Celsius).")
	String outdoorForecastChannel() default "outdoor0/Temperature";

	@AttributeDefinition(name = "Fallback outdoor temperature", description = "Used for slots without a forecast, in °C.")
	double fallbackOutdoorTemperature() default 10.0;

	@AttributeDefinition(name = "HVAC mode", description = "HEAT or COOL (OFF is only read back from a device).")
	HvacMode hvacMode() default HvacMode.HEAT;

	@AttributeDefinition(name = "Device type", description = "Modulating (inverter compressor, heat pump): switching is free. On-off (fixed-speed compressor): a start costs the Start Cost.")
	DeviceType deviceType() default DeviceType.MODULATING;

	@AttributeDefinition(name = "Thermal Capacity [kWh/°C]", description = "Energy to heat the zone by 1 °C. Floor area × light 0.030 / medium 0.046 / heavy 0.072 kWh/m²·°C.")
	double thermalCapacityKwhPerK() default 1.8;

	@AttributeDefinition(name = "TransmissionFactor [W/°C]", description = "Heat loss to the outside: U × outside surface + 0.34 × air changes × volume.")
	double transmissionWPerK() default 50.0;

	@AttributeDefinition(name = "Nominal power [W]", description = "Electrical power in OFF (frost protection), ON and FORCED.")
	int nominalPowerW() default 1000;

	@AttributeDefinition(name = "Maximum power [W]", description = "Electrical power in BOOST and FORCED_BOOST.")
	int maxPowerW() default 2000;

	@AttributeDefinition(name = "COP points", description = "Heating: outdoor/supply °C = COP, e.g. '7/25=4.0, 2/25=3.2, -7/25=2.4'. The supply temperature is the room temperature + 5 K. Empty: resistive (COP 1).")
	String copPoints() default "";

	@AttributeDefinition(name = "EER points", description = "Cooling: outdoor °C = EER, e.g. '27=4.4, 35=3.2'. Empty: EER 1.")
	String eerPoints() default "";

	@AttributeDefinition(name = "Minimum temperature [°C]", description = "Physical limit: frost protection when heating.")
	double minTemperature() default 5.0;

	@AttributeDefinition(name = "Maximum temperature [°C]", description = "Physical limit: overheating protection when cooling.")
	double maxTemperature() default 30.0;

	@AttributeDefinition(name = "Schedule", description = "Target temperatures from times of day, every day, e.g. '07:00=20.5, 22:00=17'. Empty: the default target all day.")
	String schedule() default "";

	@AttributeDefinition(name = "Default target, heating [°C]", description = "The target when the schedule is empty.")
	double defaultTargetHeat() default 20.0;

	@AttributeDefinition(name = "Default target, cooling [°C]", description = "The target when the schedule is empty.")
	double defaultTargetCool() default 24.0;

	@AttributeDefinition(name = "BoostOffset [°C]", description = "How far BOOST may go past the target to store heat (coolness).")
	double boostOffset() default 1.5;

	@AttributeDefinition(name = "Deviation cost, uncomfortable side, linear [€/°C·h]", description = "a in a·d + b·d² per hour, below the target when heating (above it when cooling).")
	double deviationCostUncomfortableA() default 0.10;

	@AttributeDefinition(name = "Deviation cost, uncomfortable side, quadratic [€/°C²·h]", description = "b in a·d + b·d² per hour, below the target when heating (above it when cooling).")
	double deviationCostUncomfortableB() default 0.10;

	@AttributeDefinition(name = "Deviation cost, other side, linear [€/°C·h]", description = "a in a·d + b·d² per hour, above the target when heating (below it when cooling).")
	double deviationCostOtherA() default 0.05;

	@AttributeDefinition(name = "Deviation cost, other side, quadratic [€/°C²·h]", description = "b in a·d + b·d² per hour, above the target when heating (below it when cooling).")
	double deviationCostOtherB() default 0.05;

	@AttributeDefinition(name = "End-of-horizon value factor", description = "What heat still stored at the end of the plan is worth, as a fraction of its price.")
	double endOfHorizonFactor() default 0.5;

	@AttributeDefinition(name = "Start Cost [€]", description = "On-off devices: the wear cost of one start.")
	double startCostEur() default 0.02;

	@AttributeDefinition(name = "Minimum On Time [slots]", description = "On-off devices.")
	int minOnSlots() default 1;

	@AttributeDefinition(name = "Minimum Off Time [slots]", description = "On-off devices.")
	int minOffSlots() default 1;

	@AttributeDefinition(name = "Manual change tolerance [°C]", description = "A set point on the device this close to the one sent is no manual change (the device rounds).")
	double manualChangeTolerance() default 0.5;

	@AttributeDefinition(name = "Manual change settling time [s]", description = "After a new command, differences on the device are ignored this long.")
	int manualChangeSettlingSeconds() default 60;

	@AttributeDefinition(name = "Maximum manual override [min]", description = "A manual change is followed until the next step in the schedule, at most this long. 0: until the next step.")
	int manualOverrideMaxMinutes() default 0;

	@AttributeDefinition(name = "HVAC device target filter", description = "This is auto-generated by 'HVAC device-ID'.")
	String hvac_target() default "(enabled=true)";

	@AttributeDefinition(name = "Room thermometer target filter", description = "This is auto-generated by 'Room thermometer-ID'.")
	String room_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Controller Comfort Zone [{id}]";
}
