package io.openems.edge.device.mqtt.hvac;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.hvac.api.Hvac;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.thermometer.api.Thermometer;

public interface HvacMqtt extends Hvac, Thermometer, ElectricityMeter, MqttDevice, OpenemsComponent {
}
