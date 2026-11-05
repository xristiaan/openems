package io.openems.edge.meter.acrel;

import static io.openems.common.types.MeterType.CONSUMPTION_METERED;
import static io.openems.common.types.MeterType.GRID;
import static io.openems.common.types.MeterType.PRODUCTION;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.meter.api.ElectricityMeter;

public class MeterAcrelImplTest {

	// CH1: 230 V and 50 Hz; A 4 A and 920 W, B 1 A and 230 W, C nothing.
	// CH2 as the meter reports a PV feed: 230 V, 50 Hz, 10 A and -2300 W on each
	// terminal (power flowing away from the load), -6900 W in total.
	private static ComponentTest prepareTest() throws Exception {
		return new ComponentTest(new MeterAcrelImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0x100, //
								// voltage A-C
								0x4366, 0x0000, 0x4366, 0x0000, 0x4366, 0x0000, //
								// line voltages
								0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, //
								// frequency
								0x4248, 0x0000, //
								// current A-C
								0x4080, 0x0000, 0x3F80, 0x0000, 0x0000, 0x0000, //
								// zero-sequence current
								0x0000, 0x0000, //
								// active power A-C, total
								0x4466, 0x0000, 0x4366, 0x0000, 0x0000, 0x0000, 0x448F, 0xC000, //
								// reactive power A-C
								0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000) //
						.withRegisters(0x156, //
								0x4366, 0x0000, 0x4366, 0x0000, 0x4366, 0x0000, //
								0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, //
								0x4248, 0x0000, //
								0x4120, 0x0000, 0x4120, 0x0000, 0x4120, 0x0000, //
								0x0000, 0x0000, //
								0xC50F, 0xC000, 0xC50F, 0xC000, 0xC50F, 0xC000, 0xC5D7, 0xA000, //
								0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000));
	}

	// Expected power and current on L1-L3 (null: not measured), and the totals
	private static TestCase expect(Integer p1, Integer p2, Integer p3, Integer power, Integer i1, Integer i2,
			Integer i3, Integer current) {
		return new TestCase() //
				.output(ElectricityMeter.ChannelId.VOLTAGE_L1, p1 == null ? null : 230000) //
				.output(ElectricityMeter.ChannelId.VOLTAGE_L2, p2 == null ? null : 230000) //
				.output(ElectricityMeter.ChannelId.VOLTAGE_L3, p3 == null ? null : 230000) //
				.output(ElectricityMeter.ChannelId.VOLTAGE, 230000) //
				.output(ElectricityMeter.ChannelId.FREQUENCY, 50000) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, p1) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, p2) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, p3) //
				.output(ElectricityMeter.ChannelId.ACTIVE_POWER, power) //
				.output(ElectricityMeter.ChannelId.CURRENT_L1, i1) //
				.output(ElectricityMeter.ChannelId.CURRENT_L2, i2) //
				.output(ElectricityMeter.ChannelId.CURRENT_L3, i3) //
				.output(ElectricityMeter.ChannelId.CURRENT, current) //
				.output(ElectricityMeter.ChannelId.REACTIVE_POWER, 0);
	}

	private static MyConfig.Builder config(MeterType type, String inputs) {
		return MyConfig.create() //
				.setId("meter2") //
				.setModbusId("modbus0") //
				.setInputs(inputs) //
				.setType(type);
	}

	@Test
	public void heatPumpOnAllOfCh2ProductionIsPositive() throws Exception {
		prepareTest() //
				.activate(config(PRODUCTION, "2A,2B,2C").build()) //
				.next(new TestCase()) //
				.next(expect(2300, 2300, 2300, 6900, 10000, 10000, 10000, 30000));
	}

	@Test
	public void boilerOnCh1A() throws Exception {
		prepareTest() //
				.activate(config(CONSUMPTION_METERED, "1A").build()) //
				.next(new TestCase()) //
				.next(expect(920, null, null, 920, 4000, null, null, 4000));
	}

	@Test
	public void inputsAcrossChannels() throws Exception {
		prepareTest() //
				.activate(config(CONSUMPTION_METERED, "1B,2C").build()) //
				.next(new TestCase()) //
				.next(expect(null, 230, -2300, -2070, null, 1000, 10000, 11000));
	}

	@Test
	public void inputsOnTheSamePhaseAddUp() throws Exception {
		prepareTest() //
				.activate(config(CONSUMPTION_METERED, "1A,2A").build()) //
				.next(new TestCase()) //
				.next(expect(-1380, null, null, -1380, 14000, null, null, 14000));
	}

	@Test
	public void terminalWiredToAnotherPhase() throws Exception {
		prepareTest() //
				.activate(config(CONSUMPTION_METERED, "2A=L3").build()) //
				.next(new TestCase()) //
				.next(expect(null, null, -2300, -2300, null, null, 10000, 10000));
	}

	@Test
	public void invertIsAQuickFix() throws Exception {
		prepareTest() //
				.activate(config(PRODUCTION, "2A,2B,2C").setInvert(true).build()) //
				.next(new TestCase()) //
				.next(expect(-2300, -2300, -2300, -6900, 10000, 10000, 10000, 30000));
	}

	@Test
	public void ctTurnsDivideCurrentAndPower() throws Exception {
		prepareTest() //
				.activate(config(GRID, "2A,2B,2C").setCtTurns(2).build()) //
				.next(new TestCase()) //
				.next(expect(-1150, -1150, -1150, -3450, 5000, 5000, 5000, 15000));
	}

	@Test
	public void parseInputs() throws Exception {
		assertEquals(List.of(new Input(1, 0, SinglePhase.L1)), Input.parse("1A"));
		assertEquals(List.of(new Input(1, 2, SinglePhase.L3), new Input(2, 0, SinglePhase.L2)),
				Input.parse(" 1c , 2A=L2 "));
		assertThrows(OpenemsException.class, () -> Input.parse(""));
		assertThrows(OpenemsException.class, () -> Input.parse("5A"));
		assertThrows(OpenemsException.class, () -> Input.parse("1D"));
		assertThrows(OpenemsException.class, () -> Input.parse("1A=L4"));
		assertThrows(OpenemsException.class, () -> Input.parse("1A,1A=L2"));
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
