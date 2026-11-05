package io.openems.edge.meter.acrel;

import static io.openems.common.types.MeterType.GRID;
import static io.openems.common.types.MeterType.PRODUCTION;
import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Test;

import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.meter.api.ElectricityMeter;

public class MeterAcrelImplTest {

	// CH2's block as the meter reports a PV feed: 230 V, 50 Hz, 10 A and -2300 W
	// on each phase (power flowing away from the load), -6900 W in total.
	private static ComponentTest prepareTest() throws Exception {
		return new ComponentTest(new MeterAcrelImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0x156, //
								// voltage L1-L3
								0x4366, 0x0000, 0x4366, 0x0000, 0x4366, 0x0000, //
								// line voltages
								0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, //
								// frequency
								0x4248, 0x0000, //
								// current L1-L3
								0x4120, 0x0000, 0x4120, 0x0000, 0x4120, 0x0000, //
								// zero-sequence current
								0x0000, 0x0000, //
								// active power L1-L3, total
								0xC50F, 0xC000, 0xC50F, 0xC000, 0xC50F, 0xC000, 0xC5D7, 0xA000, //
								// reactive power L1-L3, total
								0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000));
	}

	private static TestCase expect(int phasePower, int totalPower, int phaseCurrent) {
		return new TestCase() //
				.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 230000) //
				.output(ElectricityMeter.ChannelId.VOLTAGE_L3, 230000) //
				.output(ElectricityMeter.ChannelId.FREQUENCY, 50000) //
				.output(ElectricityMeter.ChannelId.CURRENT_L1, phaseCurrent) //
				.output(ElectricityMeter.ChannelId.CURRENT, 3 * phaseCurrent) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, phasePower) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, phasePower) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER, totalPower) //
				.output(ElectricityMeter.ChannelId.REACTIVE_POWER, 0);
	}

	private static MyConfig.Builder config(MeterType type) {
		return MyConfig.create() //
				.setId("meter2") //
				.setModbusId("modbus0") //
				.setChannel(2) //
				.setType(type);
	}

	@Test
	public void productionIsPositive() throws Exception {
		prepareTest() //
				.activate(config(PRODUCTION).build()) //
				.next(new TestCase()) //
				.next(expect(2300, 6900, 10000));
	}

	@Test
	public void invertIsAQuickFix() throws Exception {
		prepareTest() //
				.activate(config(PRODUCTION).setInvert(true).build()) //
				.next(new TestCase()) //
				.next(expect(-2300, -6900, 10000));
	}

	@Test
	public void ctTurnsDivideCurrentAndPower() throws Exception {
		prepareTest() //
				.activate(config(GRID).setCtTurns(2).build()) //
				.next(new TestCase()) //
				.next(expect(-1150, -3450, 5000));
	}

	@Test
	public void blockStart() {
		assertEquals(0x100, MeterAcrelImpl.blockStart(1));
		assertEquals(0x156, MeterAcrelImpl.blockStart(2));
		assertEquals(0x1AC, MeterAcrelImpl.blockStart(3));
		assertEquals(0x202, MeterAcrelImpl.blockStart(4));
	}

	@Test
	public void powerFactor() {
		assertEquals(1.0, MeterAcrelImpl.powerFactor(GRID, false, 1), 0);
		assertEquals(-1.0, MeterAcrelImpl.powerFactor(PRODUCTION, false, 1), 0);
		assertEquals(1.0, MeterAcrelImpl.powerFactor(PRODUCTION, true, 1), 0);
		assertEquals(1.0, MeterAcrelImpl.powerFactor(MeterType.CONSUMPTION_METERED, false, 1), 0);
		assertEquals(-1.0 / 3, MeterAcrelImpl.powerFactor(PRODUCTION, false, 3), 1e-9);
	}
}
