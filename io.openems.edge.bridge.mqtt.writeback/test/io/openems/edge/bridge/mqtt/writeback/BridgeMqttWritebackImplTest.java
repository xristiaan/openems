package io.openems.edge.bridge.mqtt.writeback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.mqtt.test.DummyBridgeMqtt;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractDummyOpenemsComponent;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;

class BridgeMqttWritebackImplTest {

	private enum TargetChannelId implements io.openems.edge.common.channel.ChannelId {
		MEASURED_VALUE(Doc.of(io.openems.common.types.OpenemsType.DOUBLE));

		private final Doc doc;

		TargetChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	private static final class TargetComponent extends AbstractDummyOpenemsComponent<TargetComponent> {
		TargetComponent(String id) {
			super(id, OpenemsComponent.ChannelId.values(), TargetChannelId.values());
		}

		@Override
		protected TargetComponent self() {
			return this;
		}
	}

	@Test
	void writesIncomingPayloadIntoTheTargetChannel() throws Exception {
		var target = new TargetComponent("target0");
		var componentManager = new DummyComponentManager().addComponent(target);
		var mqtt = new DummyBridgeMqtt("mqtt0");

		new ComponentTest(new BridgeMqttWritebackImpl()) //
				.addReference("componentManager", componentManager) //
				.addReference("mqtt", mqtt) //
				.activate(new MyConfig("mqttWriteback0", "energypro/homey/kantoor/temperature", "target0/MeasuredValue",
						"mqtt0"));

		assertNull(target.channel(TargetChannelId.MEASURED_VALUE).getNextValue().get());

		mqtt.simulateMessage("energypro/homey/kantoor/temperature", "21.5");

		assertEquals(21.5, (Double) target.channel(TargetChannelId.MEASURED_VALUE).getNextValue().get());
	}
}
