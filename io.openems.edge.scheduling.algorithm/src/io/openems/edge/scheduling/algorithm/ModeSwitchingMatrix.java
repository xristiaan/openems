package io.openems.edge.scheduling.algorithm;

/**
 * "Shows allowed mode transitions and the cost of those transitions" plus
 * each mode's own minimum dwell time — the two things the Mode Switching
 * Matrix table captures together (e.g. the Electrical Hot Water Boiler's
 * "Min in Mode" column alongside its from/to grid).
 *
 * @param <M> the controller's own mode type
 */
public interface ModeSwitchingMatrix<M> {

	/**
	 * Looks up whether a transition is allowed and what it costs.
	 *
	 * @param priorMode   the mode the device is currently in
	 * @param currentMode the candidate mode to switch to (or stay in, if
	 *                        equal to {@code priorMode})
	 * @return whether that transition is allowed, and its cost
	 */
	ModeTransition transition(M priorMode, M currentMode);

	/**
	 * The minimum number of slots the device must remain in {@code mode}
	 * once entered, before any other transition is allowed — real heat
	 * pumps often can't cycle compressors rapidly; a plain resistive element
	 * typically returns 1 (no lock).
	 *
	 * @param mode the mode
	 * @return the minimum dwell time in slots, &gt;= 1
	 */
	int minSlotsInMode(M mode);
}
