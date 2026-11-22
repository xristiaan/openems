package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.comfortzone.ControllerComfortZoneImpl.Command;
import io.openems.edge.hvac.api.HvacMode;

class ControllerComfortZoneImplTest {

	@Test
	void theSetpointIsThePlannedEndTemperature() {
		// BOOST cut by the budget: goal 21.5, planned 20.1 -> the device stops at 20.1
		assertEquals(new Command(true, 201),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.BOOST, 21.5, 20.1, 20.0, 5, 30));
		// ON holding the target
		assertEquals(new Command(true, 200),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.ON, 20.0, 20.0, 20.0, 5, 30));
	}

	@Test
	void theSetpointNeverGoesBeyondTheModesGoal() {
		assertEquals(new Command(true, 200),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.ON, 20.0, 20.04, 20.0, 5, 30));
		assertEquals(new Command(true, 240),
				ControllerComfortZoneImpl.command(HvacMode.COOL, ComfortZoneMode.ON, 24.0, 23.96, 25.0, 5, 30),
				"cooling: not below the goal");
		assertEquals(new Command(true, 248),
				ControllerComfortZoneImpl.command(HvacMode.COOL, ComfortZoneMode.ON, 24.0, 24.8, 26.0, 5, 30),
				"cooling, power-limited: the planned end");
	}

	@Test
	void offSwitchesOffExceptAsFrostProtection() {
		assertEquals(new Command(false, null),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.OFF, null, 17.8, 18.0, 5, 30));
		assertEquals(new Command(true, 50),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.OFF, null, 5.0, 5.2, 5, 30));
		assertEquals(new Command(true, 300),
				ControllerComfortZoneImpl.command(HvacMode.COOL, ComfortZoneMode.OFF, null, 30.0, 29.8, 5, 30),
				"cooling: overheating protection");
		assertEquals(new Command(false, null),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.OFF, null, 17.0, null, 5, 30),
				"room temperature unknown: off");
	}

	@Test
	void theSetpointStaysWithinThePhysicalLimits() {
		assertEquals(new Command(true, 300),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.FORCED, 35.0, 33.0, 20.0, 5, 30));
	}

	@Test
	void gapsInTheForecastTakeThePreviousValue() {
		var filled = ControllerComfortZoneImpl.fillForecast(new Integer[] { null, 52, null, 61 }, 6);

		assertArrayEquals(new double[] { 5.2, 5.2, 5.2, 6.1, 6.1, 6.1 }, filled, 1e-9);
		assertNull(ControllerComfortZoneImpl.fillForecast(new Integer[] { null, null }, 4));
		assertNull(ControllerComfortZoneImpl.fillForecast(new Integer[0], 4));
	}
}
