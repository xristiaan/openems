package io.openems.edge.hvac.api;

import io.openems.common.types.OptionsEnum;

/**
 * Whether a heating / cooling device heats or cools. {@link #OFF} is only
 * read back, from devices that switch off through their mode (e.g. MELCloud's
 * thermostat_mode "off"); a Controller switches off through the ON Channel.
 */
public enum HvacMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	HEAT(0, "Heat"), //
	COOL(1, "Cool"), //
	OFF(2, "Off");

	private final int value;
	private final String name;

	HvacMode(int value, String name) {
		this.value = value;
		this.name = name;
	}

	@Override
	public int getValue() {
		return this.value;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public OptionsEnum getUndefined() {
		return UNDEFINED;
	}
}
