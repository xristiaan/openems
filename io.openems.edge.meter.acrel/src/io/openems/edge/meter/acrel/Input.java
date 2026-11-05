package io.openems.edge.meter.acrel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.type.Phase.SinglePhase;

/**
 * One current transformer input of the meter: a channel (1-4) and a terminal
 * (A, B or C), and the grid phase it measures.
 *
 * <p>
 * Written as {@code 1A}, or {@code 2A=L2} when the channel's terminal A is
 * wired to another grid phase than L1. A device is a list of inputs, e.g.
 * {@code 1A} for a boiler, {@code 2A,2B,2C} for a heat pump or
 * {@code 1C,2A,2B} across channels.
 *
 * @param channel  1-4 for CH1-CH4
 * @param terminal 0-2 for A-C
 * @param phase    the grid phase
 */
record Input(int channel, int terminal, SinglePhase phase) {

	private static final Pattern INPUT = Pattern.compile("([1-4])([ABC])(?:=(L[123]))?");

	/**
	 * Parses a comma-separated list of inputs.
	 *
	 * @param inputs e.g. "1A" or "1C,2A=L2"
	 * @return the inputs, in the given order
	 * @throws OpenemsException on a malformed, duplicate or empty list
	 */
	static List<Input> parse(String inputs) throws OpenemsException {
		var result = new ArrayList<Input>();
		var seen = new HashSet<String>();
		for (var token : inputs.split(",")) {
			var text = token.trim().toUpperCase();
			if (text.isEmpty()) {
				continue;
			}
			var match = INPUT.matcher(text);
			if (!match.matches()) {
				throw new OpenemsException("Input must be like 1A or 2A=L2, not " + token.trim());
			}
			var channel = Integer.parseInt(match.group(1));
			var terminal = match.group(2).charAt(0) - 'A';
			var phase = match.group(3) != null //
					? SinglePhase.valueOf(match.group(3)) //
					: SinglePhase.values()[terminal];
			if (!seen.add(match.group(1) + match.group(2))) {
				throw new OpenemsException("Input " + match.group(1) + match.group(2) + " is listed twice");
			}
			result.add(new Input(channel, terminal, phase));
		}
		if (result.isEmpty()) {
			throw new OpenemsException("No inputs configured");
		}
		return result;
	}

	/**
	 * The input as printed on the meter.
	 *
	 * @return e.g. "2A"
	 */
	String terminalName() {
		return "" + this.channel + (char) ('A' + this.terminal);
	}

	@Override
	public String toString() {
		return this.terminalName() + "=" + this.phase;
	}
}
