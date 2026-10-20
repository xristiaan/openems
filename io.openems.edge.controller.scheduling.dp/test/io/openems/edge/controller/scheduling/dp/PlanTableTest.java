package io.openems.edge.controller.scheduling.dp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.openems.edge.scheduling.api.PlanSummary;

class PlanTableTest {

	@Test
	void oneRowPerSlotWithModePowerStateAndGrid() {
		var plans = new LinkedHashMap<String, PlanSummary>();
		plans.put("ctrlEssBattery0", new PlanSummary(new double[] { 2_500, 0 }, new double[2],
				new String[] { "ON", "OFF" }, new double[] { 0.55, 0.55 }));
		plans.put("ctrlPv0", new PlanSummary(new double[] { -3_000, -200 }, new double[2],
				new String[] { "ON", "OFF" }, null));
		var result = new DpArbitration.Result(Map.of(), plans, 2);

		var table = PlanTable.format(Instant.parse("2026-10-20T11:00:00Z"), ZoneOffset.UTC,
				new double[] { 0.10, -0.05 }, new double[] { 0.04, -0.06 }, 500, result);
		var lines = table.split("\\R");

		assertEquals(4, lines.length, "title, header and one row per slot");
		assertTrue(lines[0].startsWith("Plan from 11:00, 2 slots, 2 rounds"), lines[0]);
		assertTrue(lines[1].startsWith("time  |     buy |    sell |"), lines[1]);
		assertTrue(lines[2].matches(
				"11:00 \\|  0\\.1000 \\|  0\\.0400 \\| ON\\s+2500 W 0\\.550\\s+\\| ON\\s+-3000 W\\s+\\|\\s+0"),
				lines[2]);
		assertTrue(lines[3].endsWith("|     300"), "grid = base load + battery + PV: " + lines[3]);
	}

	@Test
	void aResourceWithoutModesShowsAQuestionMark() {
		var plans = Map.of("x", new PlanSummary(new double[] { 100 }, new double[1]));
		var table = PlanTable.format(Instant.EPOCH, ZoneOffset.UTC, new double[] { 0.2 }, new double[] { 0.2 }, 0,
				new DpArbitration.Result(Map.of(), plans, 1));

		assertTrue(table.contains("| ?          100 W"), table);
	}
}
