package io.openems.edge.scheduling.algorithm;

/**
 * Resolves which target a given mode's own physics should seek in a given
 * slot — e.g. for the Electrical Hot Water Boiler, On/Maximum Power seek
 * {@code T_scheduleTarget(slot)}, Maximum Temperature seeks {@code T_Max},
 * Set-Point seeks an externally supplied value, and Off seeks nothing. This
 * is separate from {@link SlotTarget}, which is the fixed per-slot value
 * every mode is <em>scored</em> against for deviation cost, regardless of
 * what any one mode's physics is aiming for.
 *
 * <p>
 * An externally supplied set-point is optional in <em>every</em> mode,
 * including Normal/ON — not only in a dedicated Set-Point mode. When one is
 * provided, a resolver must return it for every mode that has a target at
 * all (only Off/Blocked-style modes return {@code null}); the mode's own
 * physics still enforces its physical limits around it.
 *
 * @param <M> the controller's own mode type
 */
public interface TargetSetpointResolver<M> {

	/**
	 * Resolves the target this mode's physics should seek in this slot.
	 *
	 * @param mode the candidate mode
	 * @param slot the slot index
	 * @return the target this mode's physics should seek, or {@code null}
	 *             if this mode has no target (e.g. Off)
	 */
	Double resolve(M mode, int slot);
}
