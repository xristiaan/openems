package io.openems.edge.meter.acrel;

import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.List;
import java.util.stream.Collectors;

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
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.FloatDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.Task;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * One measured device on the Acrel DTSD1352-4S four-channel three-phase meter,
 * over Modbus RTU: any combination of the meter's current transformer inputs
 * (see {@link Input}), e.g. CH1 A for a boiler or CH2 A-C for a heat pump.
 *
 * <p>
 * Register map confirmed on the meter (2026-11-04): every value is a big-endian
 * float32 in V, A, Hz, W and var. Each channel has a block of 0x56 registers,
 * CH1 starting at 0x100. The meter measures power flowing towards the load as
 * positive; a production device is inverted so it reports production as
 * positive, the OpenEMS convention. Per grid phase, the inputs on that phase are
 * added up; the totals are the sum of the phases, not the meter's channel
 * totals. Energy (kWh) registers are not mapped yet: their addresses are still
 * unknown.
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

	// Offsets in a channel's block; per phase, terminal A's value, B and C follow
	// 2 apart
	private static final int VOLTAGE = 0x00;
	private static final int FREQUENCY = 0x0C;
	private static final int CURRENT = 0x0E;
	private static final int ACTIVE_POWER = 0x16;
	private static final int REACTIVE_POWER = 0x1E;
	private static final int READ_LENGTH = 0x24;

	private static final ElectricityMeter.ChannelId[] VOLTAGE_CHANNELS = { //
			ElectricityMeter.ChannelId.VOLTAGE_L1, //
			ElectricityMeter.ChannelId.VOLTAGE_L2, //
			ElectricityMeter.ChannelId.VOLTAGE_L3 };
	private static final ElectricityMeter.ChannelId[] CURRENT_CHANNELS = { //
			ElectricityMeter.ChannelId.CURRENT_L1, //
			ElectricityMeter.ChannelId.CURRENT_L2, //
			ElectricityMeter.ChannelId.CURRENT_L3 };
	private static final ElectricityMeter.ChannelId[] ACTIVE_POWER_CHANNELS = { //
			ElectricityMeter.ChannelId.ACTIVE_POWER_L1, //
			ElectricityMeter.ChannelId.ACTIVE_POWER_L2, //
			ElectricityMeter.ChannelId.ACTIVE_POWER_L3 };
	private static final ElectricityMeter.ChannelId[] REACTIVE_POWER_CHANNELS = { //
			ElectricityMeter.ChannelId.REACTIVE_POWER_L1, //
			ElectricityMeter.ChannelId.REACTIVE_POWER_L2, //
			ElectricityMeter.ChannelId.REACTIVE_POWER_L3 };

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private MeterType meterType = MeterType.GRID;
	private List<Input> inputs = List.of();
	private int ctTurns = 1;
	private boolean invert = false;
	// The meter's readings, by channel and offset in its block
	private final Float[][] readings = new Float[5][READ_LENGTH];

	public MeterAcrelImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values() //
		);
		ElectricityMeter.calculateSumActivePowerFromPhases(this);
		ElectricityMeter.calculateSumReactivePowerFromPhases(this);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
		ElectricityMeter.calculateAverageVoltageFromPhases(this);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (config.ctTurns() < 1) {
			throw new OpenemsException("CT turns must be at least 1, not " + config.ctTurns());
		}
		this.meterType = config.type();
		this.inputs = Input.parse(config.inputs());
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

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		// One read per channel in use, of all three terminals
		var tasks = this.inputs.stream() //
				.map(Input::channel) //
				.distinct() //
				.map(this::readTask) //
				.toArray(Task[]::new);
		return new ModbusProtocol(this, tasks);
	}

	private Task readTask(int channel) {
		var a = blockStart(channel);
		return new FC3ReadRegistersTask(a, Priority.HIGH, //
				this.reading(channel, VOLTAGE), //
				this.reading(channel, VOLTAGE + 2), //
				this.reading(channel, VOLTAGE + 4), //
				new DummyRegisterElement(a + 0x06, a + 0x0B), // line voltages
				this.reading(channel, FREQUENCY), //
				this.reading(channel, CURRENT), //
				this.reading(channel, CURRENT + 2), //
				this.reading(channel, CURRENT + 4), //
				new DummyRegisterElement(a + 0x14, a + 0x15), // zero-sequence current
				this.reading(channel, ACTIVE_POWER), //
				this.reading(channel, ACTIVE_POWER + 2), //
				this.reading(channel, ACTIVE_POWER + 4), //
				new DummyRegisterElement(a + 0x1C, a + 0x1D), // the channel's total active power
				this.reading(channel, REACTIVE_POWER), //
				this.reading(channel, REACTIVE_POWER + 2), //
				this.reading(channel, REACTIVE_POWER + 4));
	}

	private FloatDoublewordElement reading(int channel, int offset) {
		return new FloatDoublewordElement(blockStart(channel) + offset).onUpdateCallback(value -> {
			synchronized (this.readings) {
				this.readings[channel][offset] = value;
				this.update();
			}
		});
	}

	// Sums one quantity over the inputs, scaled; null if a reading is missing
	private Integer sum(List<Input> inputs, int offset, double factor) {
		var sum = 0.0;
		for (var input : inputs) {
			var value = this.readings[input.channel()][offset + 2 * input.terminal()];
			if (value == null) {
				return null;
			}
			sum += value;
		}
		return (int) Math.round(sum * factor);
	}

	private void update() {
		var power = powerFactor(this.meterType, this.invert, this.ctTurns);
		for (var phase : SinglePhase.values()) {
			var on = this.inputs.stream().filter(input -> input.phase() == phase).toList();
			if (on.isEmpty()) {
				continue; // not measured: stays null
			}
			var i = phase.ordinal();
			// Inputs on the same phase share its voltage: take the first
			this.channel(VOLTAGE_CHANNELS[i]).setNextValue(this.sum(on.subList(0, 1), VOLTAGE, 1000));
			this.channel(CURRENT_CHANNELS[i]).setNextValue(this.sum(on, CURRENT, 1000.0 / this.ctTurns));
			this.channel(ACTIVE_POWER_CHANNELS[i]).setNextValue(this.sum(on, ACTIVE_POWER, power));
			this.channel(REACTIVE_POWER_CHANNELS[i]).setNextValue(this.sum(on, REACTIVE_POWER, power));
		}
		var frequency = this.readings[this.inputs.get(0).channel()][FREQUENCY];
		this._setFrequency(frequency == null ? null : Math.round(frequency * 1000));
	}

	@Override
	public String debugLog() {
		return this.inputs.stream() //
				.map(Input::terminalName) //
				.collect(Collectors.joining(",")) + " L:" + this.getActivePower().asString();
	}
}
