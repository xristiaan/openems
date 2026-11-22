package io.openems.edge.controller.comfortzone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.controller.comfortzone.ControllerComfortZoneImpl.Command;
import io.openems.edge.hvac.api.HvacMode;

class ControllerComfortZoneImplTest {

	@Test
	void onSendsTheSlotsSetpoint() {
		assertEquals(new Command(true, 215),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.BOOST, 21.5, 20.0, 5, 30));
	}

	@Test
	void offSwitchesOffExceptAsFrostProtection() {
		assertEquals(new Command(false, null),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.OFF, null, 18.0, 5, 30));
		assertEquals(new Command(true, 50),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.OFF, null, 5.2, 5, 30));
		assertEquals(new Command(true, 300),
				ControllerComfortZoneImpl.command(HvacMode.COOL, ComfortZoneMode.OFF, null, 29.8, 5, 30),
				"cooling: overheating protection");
		assertEquals(new Command(false, null),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.OFF, null, null, 5, 30),
				"room temperature unknown: off");
	}

	@Test
	void theSetpointStaysWithinThePhysicalLimits() {
		assertEquals(new Command(true, 300),
				ControllerComfortZoneImpl.command(HvacMode.HEAT, ComfortZoneMode.FORCED, 35.0, 20.0, 5, 30));
	}

	@Test
	void gapsInTheForecastTakeThePreviousValue() {
		var filled = ControllerComfortZoneImpl.fillForecast(new Integer[] { null, 52, null, 61 }, 6);

		assertArrayEquals(new double[] { 5.2, 5.2, 5.2, 6.1, 6.1, 6.1 }, filled, 1e-9);
		assertNull(ControllerComfortZoneImpl.fillForecast(new Integer[] { null, null }, 4));
		assertNull(ControllerComfortZoneImpl.fillForecast(new Integer[0], 4));
	}
}
