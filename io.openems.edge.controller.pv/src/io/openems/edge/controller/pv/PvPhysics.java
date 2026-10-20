package io.openems.edge.controller.pv;

import io.openems.edge.scheduling.algorithm.StateTransitionFunction;
import io.openems.edge.scheduling.algorithm.StateTransitionResult;

/**
 * The PV Controller's State-Transition Function, per the PV Controller
 * specification. State is the PV output power in W, production negative.
 *
 * <p>
 * PV is a <em>forced</em>, stateless device: {@code currentState} is
 * ignored, and the forecast is looked up by {@code slot}. The budget from
 * the DP Controller is negative ("produce at most this much"); only its
 * magnitude is used. Works in W/Wh like the rest of the scheduling core.
 *
 * <pre>
 * P_cap   = OFF ? P_min : (P_target != null ? |P_target| : P_max)
 * P_limit = clamp(min(P_cap, |P_budget|), P_min, P_max)   (P_max if curtailment unsupported)
 * P_prod  = min(P_avail, P_limit)
 * nextState = −P_prod, energyUsed = −P_prod · Δt, deviceCost = 0
 * </pre>
 */
public final class PvPhysics implements StateTransitionFunction<PvMode> {

	static final double SLOT_DURATION_SECONDS = 900;

	private final PvPhysicsConfig config;

	/**
	 * Creates the physics for one planning cycle.
	 *
	 * @param config a snapshot of this planning cycle's parameters,
	 *                   including the live-read forecast
	 */
	public PvPhysics(PvPhysicsConfig config) {
		this.config = config;
	}

	@Override
	public StateTransitionResult apply(double currentState, PvMode mode, int slot, double availablePowerW,
			Double targetSetpointW) {
		var limitW = this.limitW(mode, availablePowerW, targetSetpointW);
		var producedW = Math.min(this.config.availableW(slot), limitW);
		var slotHours = SLOT_DURATION_SECONDS / 3600.0;
		return new StateTransitionResult(-producedW * slotHours, -producedW);
	}

	/**
	 * The output limit {@code P_limit} for a mode, budget and optional
	 * set-point — what Set-Point Translation turns into the inverter command.
	 *
	 * @param mode            the mode
	 * @param budgetW         the DP Controller's budget (negative for a
	 *                            producer; only the magnitude is used)
	 * @param targetSetpointW the optional set-point (production negative;
	 *                            only the magnitude is used), or null
	 * @return the limit, in W, within [P_min, P_max]
	 */
	public double limitW(PvMode mode, double budgetW, Double targetSetpointW) {
		var pMax = this.config.pMaxW();
		if (!this.config.curtailmentSupported()) {
			return pMax;
		}
		var pMin = this.config.pMinW();
		return clamp(Math.min(this.capW(mode, targetSetpointW), Math.abs(budgetW)), pMin, pMax);
	}

	/**
	 * The mode's own cap {@code P_cap}, before the budget is applied.
	 *
	 * @param mode            the mode
	 * @param targetSetpointW the optional set-point, or null
	 * @return the cap, in W
	 */
	public double capW(PvMode mode, Double targetSetpointW) {
		if (mode == PvMode.OFF) {
			return this.config.pMinW();
		}
		// ON, BOOST: optional set-point. FORCED always gets one — the external
		// one, or else the site's consumption (see ControllerPvImpl).
		return targetSetpointW != null ? Math.abs(targetSetpointW) : this.config.pMaxW();
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
