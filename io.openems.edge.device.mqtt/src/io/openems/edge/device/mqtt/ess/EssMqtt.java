package io.openems.edge.device.mqtt.ess;

import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.device.mqtt.common.MqttDevice;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;

public interface EssMqtt extends ManagedSymmetricEss, SymmetricEss, MqttDevice, OpenemsComponent {
}
