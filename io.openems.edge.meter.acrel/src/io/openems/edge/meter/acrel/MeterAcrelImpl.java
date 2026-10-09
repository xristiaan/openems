package io.openems.edge.meter.acrel;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Acrel DTSD1352-4S three-phase Modbus-RTU energy meter.
 *
 * <p>
 * Register map sourced from the publicly available DTSD1352-C installation
 * manual (the "-4S" variant's register map has NOT been separately confirmed
 * — verify against the actual device or its specific manual once hardware is
 * available). Only registers actually located in the accessible manual pages
 * are mapped below; everything else is left as an explicit TODO rather than
 * guessed. See raspberry-pi/openems-meter-acrel/README.md for the full list
 * of open items (scaling factors, missing power/power-factor registers,
 * possible off-by-one address conventions).
 * </p>
 */
@Designate(ocd = Config.class, factory = true)
@Component(
		name = "Meter.Acrel.Dtsd1352",
		immediate = true,
		configurationPolicy = ConfigurationPolicy.REQUIRE
)
public class MeterAcrelImpl extends AbstractOpenemsModbusComponent
		implements ElectricityMeter, OpenemsComponent, ModbusComponent {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY,
			cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config = null;

	public MeterAcrelImpl() {
		super(
				OpenemsComponent.ChannelId.values(),
				ModbusComponent.ChannelId.values(),
				ElectricityMeter.ChannelId.values()
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.config = config;
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public MeterType getMeterType() {
		return this.config.type();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				// Phase voltages, 0060H-0062H (decimal 96-98) — CONFIRMED addresses from the
				// manual, 1 register (16-bit) each. Resolution/scale NOT confirmed — assuming
				// 0.1V per unit (common for this meter family) and converting to the
				// ElectricityMeter Nature's MILLIVOLT unit accordingly (register * 100).
				// VERIFY against real hardware before trusting these values.
				new FC3ReadRegistersTask(96, Priority.HIGH,
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(96),
								new ElementToChannelConverter(value -> scale(value, 100))),
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(97),
								new ElementToChannelConverter(value -> scale(value, 100))),
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(98),
								new ElementToChannelConverter(value -> scale(value, 100))),
						// Phase currents, 0063H-0065H (decimal 99-101) — CONFIRMED addresses.
						// Resolution NOT confirmed — assuming 0.001A per unit (i.e. register
						// value already equals milliamps, the ElectricityMeter Nature's unit),
						// so no scaling is applied here. VERIFY against real hardware.
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(99)),
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(100)),
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(101))
				),
				// Frequency, 0077H (decimal 119) — CONFIRMED address, 1 register. Resolution
				// NOT confirmed — assuming 0.01Hz per unit, converting to the Nature's
				// MILLIHERTZ unit (register * 10). VERIFY against real hardware.
				new FC3ReadRegistersTask(119, Priority.LOW,
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(119),
								new ElementToChannelConverter(value -> scale(value, 10)))
				),
				// Forward (import) active total energy, 000AH (decimal 10) — CONFIRMED address,
				// 2 registers (32-bit). Data type (integer vs. float) and scale NOT confirmed —
				// assuming an unsigned 32-bit integer in units of 0.01 kWh, converting to the
				// Nature's CUMULATED_WATT_HOURS unit (register * 10). "Forward" is assumed to
				// mean energy imported from the grid (= consumption) — reasonable given the
				// terminology, but not explicitly confirmed against OpenEMS's own convention.
				// VERIFY against real hardware.
				new FC3ReadRegistersTask(10, Priority.LOW,
						m(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY,
								new UnsignedDoublewordElement(10),
								new ElementToChannelConverter(value -> scale(value, 10)))
				),
				// Reversing (export) active total energy, 0014H (decimal 20) — same caveats as
				// above. "Reversing" is assumed to mean energy exported to the grid
				// (= production, from this grid meter's perspective).
				new FC3ReadRegistersTask(20, Priority.LOW,
						m(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY,
								new UnsignedDoublewordElement(20),
								new ElementToChannelConverter(value -> scale(value, 10)))
				)

		// TODO: ACTIVE_POWER / ACTIVE_POWER_L1-L3, REACTIVE_POWER / REACTIVE_POWER_L1-L3
		// — register addresses not located in the accessible manual pages (checked the
		// full instantaneous-parameter section, registers 0066H-0076H are marked
		// "Reserve"). Needs either the complete manual or on-hardware Modbus scanning to
		// find the correct addresses before these channels can be mapped.
		);
	}

	/**
	 * Placeholder for the register-to-channel scaling assumptions documented above
	 * — deliberately a plain multiply, not one of OpenEMS's ElementToChannelConverter
	 * SCALE_FACTOR_* constants, so the actual arithmetic and its direction (multiply,
	 * not divide) is explicit and easy to correct once verified, rather than resting
	 * on a remembered enum convention that wasn't independently confirmed.
	 *
	 * @param value  the raw register value
	 * @param factor the scaling factor to multiply by
	 * @return the scaled value, or null if value was null
	 */
	private static Object scale(Object value, int factor) {
		if (value == null) {
			return null;
		}
		return ((Number) value).longValue() * factor;
	}

	@Override
	public String debugLog() {
		return "L1:" + this.getVoltageL1().asString() + " L2:" + this.getVoltageL2().asString()
				+ " L3:" + this.getVoltageL3().asString();
	}
}
