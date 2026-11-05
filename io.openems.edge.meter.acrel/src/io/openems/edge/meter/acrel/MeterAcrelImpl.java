package io.openems.edge.meter.acrel;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_3;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * One measuring channel of the Acrel DTSD1352-4S four-channel three-phase
 * meter, over Modbus RTU.
 *
 * <p>
 * Register map confirmed on the meter (2026-11-04): every value is a big-endian
 * float32 in V, A, Hz, W and var. Each channel has a block of 0x56 registers,
 * CH1 starting at 0x100. The meter measures power flowing towards the load as
 * positive; a production channel is inverted so it reports production as
 * positive, the OpenEMS convention. Energy (kWh) registers are not mapped yet:
 * their addresses are still unknown.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Meter.Acrel.Dtsd1352", //
		immediate = true, //
		configurationPolicy = REQUIRE)
@GenerateTargetsFromReferences("Modbus")
public class MeterAcrelImpl extends AbstractOpenemsModbusComponent
		implements ElectricityMeter, ModbusComponent, OpenemsComponent {

	/** Address of CH1's block. */
	static final int FIRST_BLOCK = 0x100;
	/** Registers between two channels' blocks. */
	static final int BLOCK_SIZE = 0x56;

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private MeterType meterType = MeterType.GRID;
	private int channel = 1;
	private int ctTurns = 1;
	private boolean invert = false;

	public MeterAcrelImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values() //
		);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
		ElectricityMeter.calculateAverageVoltageFromPhases(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (config.channel() < 1 || config.channel() > 4) {
			throw new OpenemsException("Channel must be 1-4, not " + config.channel());
		}
		if (config.ctTurns() < 1) {
			throw new OpenemsException("CT turns must be at least 1, not " + config.ctTurns());
		}
		this.meterType = config.type();
		this.channel = config.channel();
		this.ctTurns = config.ctTurns();
		this.invert = config.invert();
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public MeterType getMeterType() {
		return this.meterType;
	}

	/**
	 * The first register of a channel's block.
	 *
	 * @param channel 1-4
	 * @return the address
	 */
	static int blockStart(int channel) {
		return FIRST_BLOCK + (channel - 1) * BLOCK_SIZE;
	}

	/**
	 * The factor from the meter's power reading to OpenEMS's: the meter counts
	 * power towards the load as positive, OpenEMS counts production as positive on
	 * a production meter. Divided by the CT turns.
	 *
	 * @param meterType the meter type
	 * @param invert    the quick fix for CTs fitted the wrong way round
	 * @param ctTurns   the CT turns
	 * @return the factor
	 */
	static double powerFactor(MeterType meterType, boolean invert, int ctTurns) {
		var production = meterType == MeterType.PRODUCTION || meterType == MeterType.PRODUCTION_AND_CONSUMPTION;
		var sign = production != invert ? -1 : 1;
		return (double) sign / ctTurns;
	}

	private static ElementToChannelConverter times(double factor) {
		return new ElementToChannelConverter(value -> value == null //
				? null //
				: Math.round(((Number) value).doubleValue() * factor));
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var a = blockStart(this.channel);
		var current = times(1000.0 / this.ctTurns); // A to mA
		var power = times(powerFactor(this.meterType, this.invert, this.ctTurns));
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(a, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new FloatDoublewordElement(a), SCALE_FACTOR_3), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new FloatDoublewordElement(a + 0x02), SCALE_FACTOR_3), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new FloatDoublewordElement(a + 0x04), SCALE_FACTOR_3), //
						new DummyRegisterElement(a + 0x06, a + 0x0B), // line voltages
						m(ElectricityMeter.ChannelId.FREQUENCY, new FloatDoublewordElement(a + 0x0C), SCALE_FACTOR_3), //
						m(ElectricityMeter.ChannelId.CURRENT_L1, new FloatDoublewordElement(a + 0x0E), current), //
						m(ElectricityMeter.ChannelId.CURRENT_L2, new FloatDoublewordElement(a + 0x10), current), //
						m(ElectricityMeter.ChannelId.CURRENT_L3, new FloatDoublewordElement(a + 0x12), current), //
						new DummyRegisterElement(a + 0x14, a + 0x15), // zero-sequence current
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new FloatDoublewordElement(a + 0x16), power), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new FloatDoublewordElement(a + 0x18), power), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new FloatDoublewordElement(a + 0x1A), power), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER, new FloatDoublewordElement(a + 0x1C), power), //
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, new FloatDoublewordElement(a + 0x1E), power), //
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, new FloatDoublewordElement(a + 0x20), power), //
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, new FloatDoublewordElement(a + 0x22), power), //
						m(ElectricityMeter.ChannelId.REACTIVE_POWER, new FloatDoublewordElement(a + 0x24), power)));
	}

	@Override
	public String debugLog() {
		return "CH" + this.channel + " L:" + this.getActivePower().asString();
	}
}
