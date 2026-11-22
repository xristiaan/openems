package io.openems.edge.controller.comfortzone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The device's efficiency from datasheet points: the COP for heating, the EER
 * for cooling. Without points the efficiency is 1.0 (resistive heating).
 *
 * <p>
 * COP: one efficiency factor {@code η} is fitted to the points (least squares)
 * in {@code COP = η · T_supply,K / (T_supply − T_out)}, a Carnot fraction;
 * this works with two or three points and stays sensible outside the
 * datasheet range. The result is clamped between 1.0 and the best datasheet
 * value. EER: linear between the points, the nearest point's value outside
 * them.
 */
public final class Efficiency {

	private static final double KELVIN = 273.15;
	/** The smallest temperature lift used in the Carnot fraction, in K. */
	private static final double MIN_LIFT = 1.0;

	private record CopPoint(double outdoorC, double supplyC, double cop) {
	}

	private record EerPoint(double outdoorC, double eer) {
	}

	private final double eta;
	private final double maxCop;
	private final List<EerPoint> eerPoints;

	private Efficiency(double eta, double maxCop, List<EerPoint> eerPoints) {
		this.eta = eta;
		this.maxCop = maxCop;
		this.eerPoints = eerPoints;
	}

	/**
	 * Parses the datasheet points.
	 *
	 * @param copPoints e.g. {@code "7/35=4.5, 2/35=3.6"}: outdoor / supply
	 *                      temperature (°C) = COP; empty for resistive heating
	 * @param eerPoints e.g. {@code "27=4.5, 35=3.2"}: outdoor temperature (°C) =
	 *                      EER; empty for an EER of 1.0
	 * @return the efficiency
	 * @throws IllegalArgumentException if a point can't be parsed
	 */
	public static Efficiency parse(String copPoints, String eerPoints) {
		var cops = new ArrayList<CopPoint>();
		for (var item : items(copPoints)) {
			var parts = item.split("[/=]");
			if (parts.length != 3) {
				throw new IllegalArgumentException("COP point must be outdoor/supply=COP, not [" + item + "]");
			}
			cops.add(new CopPoint(number(parts[0]), number(parts[1]), number(parts[2])));
		}
		var eers = new ArrayList<EerPoint>();
		for (var item : items(eerPoints)) {
			var parts = item.split("=");
			if (parts.length != 2) {
				throw new IllegalArgumentException("EER point must be outdoor=EER, not [" + item + "]");
			}
			eers.add(new EerPoint(number(parts[0]), number(parts[1])));
		}
		eers.sort(Comparator.comparingDouble(EerPoint::outdoorC));

		var eta = 0.0;
		var maxCop = 1.0;
		if (!cops.isEmpty()) {
			var sumCopTimesCarnot = 0.0;
			var sumCarnotSquared = 0.0;
			for (var point : cops) {
				var carnot = carnot(point.outdoorC(), point.supplyC());
				sumCopTimesCarnot += point.cop() * carnot;
				sumCarnotSquared += carnot * carnot;
				maxCop = Math.max(maxCop, point.cop());
			}
			eta = sumCopTimesCarnot / sumCarnotSquared;
		}
		return new Efficiency(eta, maxCop, List.copyOf(eers));
	}

	/**
	 * The COP for heating.
	 *
	 * @param outdoorC the outdoor temperature, in °C
	 * @param supplyC  the supply temperature, in °C
	 * @return the COP, at least 1.0
	 */
	public double cop(double outdoorC, double supplyC) {
		if (this.eta == 0) {
			return 1.0;
		}
		return Math.max(1.0, Math.min(this.maxCop, this.eta * carnot(outdoorC, supplyC)));
	}

	/**
	 * The EER for cooling.
	 *
	 * @param outdoorC the outdoor temperature, in °C
	 * @return the EER
	 */
	public double eer(double outdoorC) {
		var points = this.eerPoints;
		if (points.isEmpty()) {
			return 1.0;
		}
		if (outdoorC <= points.get(0).outdoorC()) {
			return points.get(0).eer();
		}
		for (var i = 1; i < points.size(); i++) {
			var a = points.get(i - 1);
			var b = points.get(i);
			if (outdoorC <= b.outdoorC()) {
				var fraction = (outdoorC - a.outdoorC()) / (b.outdoorC() - a.outdoorC());
				return a.eer() + fraction * (b.eer() - a.eer());
			}
		}
		return points.get(points.size() - 1).eer();
	}

	private static double carnot(double outdoorC, double supplyC) {
		return (supplyC + KELVIN) / Math.max(MIN_LIFT, supplyC - outdoorC);
	}

	private static List<String> items(String points) {
		var items = new ArrayList<String>();
		if (points == null) {
			return items;
		}
		for (var item : points.split(",")) {
			if (!item.isBlank()) {
				items.add(item.trim());
			}
		}
		return items;
	}

	private static double number(String text) {
		try {
			return Double.parseDouble(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("not a number: [" + text + "]");
		}
	}
}
