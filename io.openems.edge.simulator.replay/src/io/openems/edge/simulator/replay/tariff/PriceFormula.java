package io.openems.edge.simulator.replay.tariff;

import com.fathzer.soft.javaluator.DoubleEvaluator;
import com.fathzer.soft.javaluator.StaticVariableSet;

/**
 * A price as an expression of the spot price {@code x} and a surcharge
 * {@code y}, both in Euro/MWh; e.g. {@code (x + y + 130) * 1.21}. The same
 * notation as OpenEMS's ENTSO-E tariffs, so a test tariff and a real one are
 * configured alike.
 */
final class PriceFormula {

	private final String expression;
	private final DoubleEvaluator evaluator = new DoubleEvaluator();
	private final StaticVariableSet<Double> variables = new StaticVariableSet<>();

	private PriceFormula(String expression) {
		this.expression = expression;
	}

	/**
	 * Parses an expression.
	 *
	 * @param expression the expression, using {@code x} and {@code y}
	 * @return the formula
	 * @throws IllegalArgumentException if the expression can't be evaluated
	 */
	static PriceFormula parse(String expression) {
		var formula = new PriceFormula(expression);
		try {
			formula.apply(0, 0);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid price expression [" + expression + "]: " + e.getMessage(), e);
		}
		return formula;
	}

	/**
	 * Evaluates the formula.
	 *
	 * @param spotEurPerMwh      the spot price, {@code x}
	 * @param surchargeEurPerMwh the surcharge, {@code y}
	 * @return the price in Euro/MWh
	 */
	synchronized double apply(double spotEurPerMwh, double surchargeEurPerMwh) {
		this.variables.set("x", spotEurPerMwh);
		this.variables.set("y", surchargeEurPerMwh);
		return this.evaluator.evaluate(this.expression, this.variables);
	}
}
