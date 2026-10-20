package io.openems.edge.controller.scheduling.dp;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import io.openems.edge.scheduling.api.PlanSummary;

/**
 * Formats an arbitration result as a plain-text table, one row per slot: the
 * buy and sell price, each resource's planned mode, power (and state, if it reports one),
 * and the resulting grid power. For diagnostics (the {@code logPlan} option),
 * not for any decision.
 */
final class PlanTable {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final Duration SLOT = Duration.ofMinutes(15);

	private PlanTable() {
	}

	/**
	 * Formats the plan.
	 *
	 * @param planStart      the start of the first slot
	 * @param zone           the time zone to show slot times in
	 * @param priceEurPerKwh the buy price per slot
	 * @param sellEurPerKwh  the sell price per slot
	 * @param baseLoadW      the flat base load included in the grid power
	 * @param result         the arbitration result
	 * @return the table, one line per slot after a header
	 */
	static String format(Instant planStart, ZoneId zone, double[] priceEurPerKwh, double[] sellEurPerKwh,
			double baseLoadW, DpArbitration.Result result) {
		var plans = result.plans();
		var out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "Plan from %s, %d slots, %d rounds%n",
				TIME.format(planStart.atZone(zone)), priceEurPerKwh.length, result.rounds()));

		out.append("time  |     buy |    sell");
		for (var entry : plans.entrySet()) {
			out.append(String.format(Locale.ROOT, " | %-24s", entry.getKey()));
		}
		out.append(" |  grid W").append(System.lineSeparator());

		for (var i = 0; i < priceEurPerKwh.length; i++) {
			out.append(TIME.format(planStart.plus(SLOT.multipliedBy(i)).atZone(zone)));
			out.append(String.format(Locale.ROOT, " | %7.4f | %7.4f", priceEurPerKwh[i], value(sellEurPerKwh, i)));
			var gridW = baseLoadW;
			for (var plan : plans.values()) {
				out.append(" | ").append(cell(plan, i));
				gridW += value(plan.powerPerSlotW(), i);
			}
			out.append(String.format(Locale.ROOT, " | %7.0f", gridW)).append(System.lineSeparator());
		}
		return out.toString();
	}

	private static String cell(PlanSummary plan, int slot) {
		var mode = plan.modePerSlot() != null && slot < plan.modePerSlot().length ? plan.modePerSlot()[slot] : "?";
		var cell = String.format(Locale.ROOT, "%-7s %6.0f W", mode, value(plan.powerPerSlotW(), slot));
		if (plan.endStatePerSlot() != null && slot < plan.endStatePerSlot().length) {
			cell += String.format(Locale.ROOT, " %5.3f", plan.endStatePerSlot()[slot]);
		}
		return String.format(Locale.ROOT, "%-24s", cell);
	}

	private static double value(double[] values, int slot) {
		return slot < values.length ? values[slot] : 0;
	}
}
