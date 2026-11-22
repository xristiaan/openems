package io.openems.edge.device.mqtt.hvac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.hvac.api.HvacMode;

class HvacMqttImplTest {

	@Test
	void theModeTextMapsToHeatOrCool() {
		assertEquals(HvacMode.HEAT, HvacMqttImpl.modeOf("heat"));
		assertEquals(HvacMode.COOL, HvacMqttImpl.modeOf(" Cool "));
		assertEquals(HvacMode.OFF, HvacMqttImpl.modeOf("off"));
		assertEquals(HvacMode.UNDEFINED, HvacMqttImpl.modeOf("auto"));
		assertEquals(HvacMode.UNDEFINED, HvacMqttImpl.modeOf("dry"));
		assertEquals(HvacMode.UNDEFINED, HvacMqttImpl.modeOf(null));
	}

	@Test
	void modeOffMeansSwitchedOff() {
		assertTrue(HvacMqttImpl.isOn(true, "heat"));
		assertFalse(HvacMqttImpl.isOn(true, "off"));
		assertFalse(HvacMqttImpl.isOn(false, "heat"));
		assertNull(HvacMqttImpl.isOn(null, "heat"));
		assertTrue(HvacMqttImpl.isOn(true, null));
	}

	@Test
	void theEnergyCounterIsWattHours() {
		assertEquals(57_081L, HvacMqttImpl.toWattHours(57.081));
		assertNull(HvacMqttImpl.toWattHours(null));
	}

	@Test
	void temperaturesAreDeciDegrees() {
		assertEquals(195, HvacMqttImpl.toDeciDegrees(19.5));
		assertEquals(-23, HvacMqttImpl.toDeciDegrees(-2.34));
		assertNull(HvacMqttImpl.toDeciDegrees(null));
	}
}
