package io.openems.edge.scheduling.algorithm;

/**
 * "A lookup table with the optimal execution mode in each slot for each of
 * the buckets" — one row per (slot, bucket, prior mode, remaining lock)
 * combination, as built by {@link PlanTableBuilder}. Package-private
 * deliberately: callers outside this package only ever hold this as an
 * opaque handle returned from {@link PlanTableBuilder#build} and passed to
 * {@link ExecutionPlanCalculator#walk} — nothing about its internal shape
 * is part of the public API.
 *
 * @param <M> the controller's own mode type
 */
final class PlanTable<M extends Enum<M>> {

	/**
	 * One cell: the cost-minimizing mode to run, and the resulting total
	 * cost-to-go from this (slot, bucket, prior mode, lock).
	 *
	 * @param <M> the controller's own mode type
	 */
	record Cell<M>(M bestMode, double costToGo) {
	}

	private final PlanableResourceModel<M> model;
	private final int slotCount;
	private final int maxLock;
	private final Cell<M>[][][][] cells;

	@SuppressWarnings("unchecked")
	PlanTable(PlanableResourceModel<M> model, int slotCount, int maxLock) {
		this.model = model;
		this.slotCount = slotCount;
		this.maxLock = maxLock;
		var bucketCount = model.buckets().count();
		var modeCount = model.modes().length;
		this.cells = new Cell[slotCount][bucketCount][modeCount][maxLock + 1];
	}

	void set(int slot, int bucket, M priorMode, int lock, M bestMode, double costToGo) {
		this.cells[slot][bucket][priorMode.ordinal()][lock] = new Cell<>(bestMode, costToGo);
	}

	/**
	 * Looks up the optimal cell for a given point in the table.
	 *
	 * @param slot      the slot index
	 * @param bucket    the state bucket index (see {@link StateBuckets})
	 * @param priorMode the mode the device was in entering this slot
	 * @param lock      remaining forced-dwell slots in {@code priorMode} (0
	 *                      if free to switch)
	 * @return the optimal mode to run and its cost-to-go
	 */
	Cell<M> get(int slot, int bucket, M priorMode, int lock) {
		return this.cells[slot][bucket][priorMode.ordinal()][Math.min(lock, this.maxLock)];
	}

	PlanableResourceModel<M> model() {
		return this.model;
	}

	int slotCount() {
		return this.slotCount;
	}

	int maxLock() {
		return this.maxLock;
	}
}
