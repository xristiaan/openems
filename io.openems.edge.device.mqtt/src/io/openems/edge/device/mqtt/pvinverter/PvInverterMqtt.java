package io.openems.edge.device.mqtt.pvinverter;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterMqtt extends ManagedSymmetricPvInverter, ElectricityMeter, MqttDevice, OpenemsComponent {
}
