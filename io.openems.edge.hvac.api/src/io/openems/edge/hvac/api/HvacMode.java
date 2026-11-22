package io.openems.edge.hvac.api;

import io.openems.common.types.OptionsEnum;

/**
 * Whether a heating / cooling device heats or cools.
 */
public enum HvacMode implements OptionsEnum {
	UNDEFINED(-1, "Undefined"), //
	HEAT(0, "Heat"), //
	COOL(1, "Cool");

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
