package io.openems.edge.device.mqtt.thermometer;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.thermometer.api.Thermometer;

public interface ThermometerMqtt extends Thermometer, MqttDevice, OpenemsComponent {
}
