package io.openems.edge.meter.opendtu;

import static io.openems.common.types.MeterType.PRODUCTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.openems.common.bridge.http.api.HttpResponse;
import io.openems.common.bridge.http.api.UrlBuilder;
import io.openems.common.bridge.http.dummy.DummyBridgeHttpBundle;
import io.openems.common.channel.Level;
import io.openems.edge.bridge.http.cycle.HttpBridgeCycleServiceDefinition;
import io.openems.edge.bridge.http.cycle.dummy.DummyCycleSubscriber;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.type.Phase.SinglePhase;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.timedata.test.DummyTimedata;

public class MeterOpenDtuImplTest {

	@Test
	public void test() throws Exception {
		final var odtu = new MeterOpenDtuImpl();
		final var httpTestBundle = DummyBridgeHttpBundle.of();
		final var dummyCycleSubscriber = new DummyCycleSubscriber();
		new ComponentTest(odtu) //
				.addReference("httpBridgeFactory", httpTestBundle.factory()) //
				.addReference("httpBridgeCycleServiceDefinition",
						new HttpBridgeCycleServiceDefinition(dummyCycleSubscriber)) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.activate(MyConfig.create() //
						.setId("meterOpenDTU0") //
						.setIp("127.0.0.1") //
						.setPhase(SinglePhase.L1) //
						.setType(PRODUCTION) //
						.setSerialNumber("1234567890") //
						.build()) //
				.next(new TestCase("Successful read response") //
						.activateStrictMode() //
						.onBeforeProcessImage(() -> {
							httpTestBundle.forceNextSuccessfulResult(HttpResponse.ok("""
									{
									   "inverters":[
									      {
									         "AC":{
									            "0":{
									               "Power":{
									                  "v":123,
									                  "u":"W",
									                  "d":1
									               },
									               "Voltage":{
									                  "v":228.2,
									                  "u":"V",
									                  "d":1
									               },
									               "Current":{
									                  "v":1,
									                  "u":"A",
									                  "d":2
									               },
									               "Frequency":{
									                  "v":49.98,
									                  "u":"Hz",
									                  "d":2
									               },
									               "PowerFactor":{
									                  "v":0,
									                  "u":"",
									                  "d":3
									               },
									               "ReactivePower":{
									                  "v":0,
									                  "u":"var",
									                  "d":1
									               }
									            }
									         }
									      }
									   ]
									}
									"""));
							dummyCycleSubscriber.triggerNextCycle();
						}) //
						.onAfterProcessImage(() -> assertEquals("L1:123 W", odtu.debugLog()))
						.output(OpenemsComponent.ChannelId.STATE, Level.OK) //

						.output(ElectricityMeter.ChannelId.ACTIVE_POWER, 123) //
						.output(ElectricityMeter.ChannelId.VOLTAGE, 228200) //
						.output(ElectricityMeter.ChannelId.CURRENT, 1000) //

						.output(ElectricityMeter.ChannelId.CURRENT_L1, 1000) //
						.output(ElectricityMeter.ChannelId.CURRENT_L2, null) //
						.output(ElectricityMeter.ChannelId.CURRENT_L3, null) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L1, 228200) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L2, null) //
						.output(ElectricityMeter.ChannelId.VOLTAGE_L3, null) //
						.output(ElectricityMeter.ChannelId.FREQUENCY, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, 123) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, null) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER, null) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L1, null) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, null) //
						.output(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L1, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L2, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY_L3, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY_L1, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY_L2, null) //
						.output(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY_L3, null) //

						.output(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT, null) //
						.output(ManagedSymmetricPvInverter.ChannelId.MAX_ACTIVE_POWER, null) //
						.output(ManagedSymmetricPvInverter.ChannelId.MAX_APPARENT_POWER, null) //
						.output(ManagedSymmetricPvInverter.ChannelId.MAX_REACTIVE_POWER, null) //

						.output(MeterOpenDtu.ChannelId.SLAVE_COMMUNICATION_FAILED, false) //
						.output(MeterOpenDtu.ChannelId.LIMIT_WRITE_FAILED, false)) //
				.deactivate();
	}

	private static final String LIVEDATA_AT_FULL_LIMIT = """
			{
			   "inverters":[
			      {
			         "serial":"1234567890",
			         "limit_relative":100,
			         "limit_absolute":1600,
			         "AC":{
			            "0":{
			               "Power":{"v":400,"u":"W","d":1},
			               "Voltage":{"v":230,"u":"V","d":1},
			               "Current":{"v":1.74,"u":"A","d":2}
			            }
			         }
			      }
			   ]
			}
			""";

	@Test
	public void controlsTheLimit() throws Exception {
		final var odtu = new MeterOpenDtuImpl();
		final var httpTestBundle = DummyBridgeHttpBundle.of();
		final var dummyCycleSubscriber = new DummyCycleSubscriber();
		final var sent = new AtomicReference<String>();
		new ComponentTest(odtu) //
				.addReference("httpBridgeFactory", httpTestBundle.factory()) //
				.addReference("httpBridgeCycleServiceDefinition",
						new HttpBridgeCycleServiceDefinition(dummyCycleSubscriber)) //
				.addReference("timedata", new DummyTimedata("timedata0")) //
				.activate(MyConfig.create() //
						.setId("meterOpenDTU0") //
						.setIp("127.0.0.1") //
						.setPhase(SinglePhase.L1) //
						.setType(PRODUCTION) //
						.setSerialNumber("1234567890") //
						.setControlLimit(true) //
						.setPassword("secret") //
						.build()) //
				.next(new TestCase("No controller and the inverter is at 100 %: nothing to send") //
						.onBeforeProcessImage(() -> {
							httpTestBundle.forceNextSuccessfulResult(HttpResponse.ok(LIVEDATA_AT_FULL_LIMIT));
							dummyCycleSubscriber.triggerNextCycle();
						}) //
						.onAfterWriteCallbacks(() -> assertNull(sent.get())) //
						.output(ManagedSymmetricPvInverter.ChannelId.MAX_ACTIVE_POWER, 1600) //
						.outputReadValue(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT, 1600)) //
				.next(new TestCase("A controller asks for 800 W: 50 % goes out") //
						.onBeforeWriteCallbacks(() -> {
							httpTestBundle.fetcher().addSingleUseEndpointHandler(endpoint -> {
								sent.set(UrlBuilder.decodeFormUrlencodedBody(endpoint.body()).get("data"));
								return HttpResponse.ok("{\"type\":\"success\",\"message\":\"Settings saved!\"}");
							});
							odtu.setActivePowerLimit(800);
						}) //
						.onAfterWriteCallbacks(() -> assertEquals(
								"{\"serial\":\"1234567890\",\"limit_type\":1,\"limit_value\":50.0}", sent.get())) //
						.output(MeterOpenDtu.ChannelId.LIMIT_WRITE_FAILED, false)) //
				.deactivate();
	}
}
