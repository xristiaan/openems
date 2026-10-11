package io.openems.edge.scheduling.algorithm;

/**
 * The discretization of continuous state (SoC, temperature) into buckets for
 * DP table indexing — "Min Bucket, Max Bucket, Bucket Size" input to
 * Calculate DP Table. Bucketing is for table lookup only; the forward walk
 * always advances the true continuous state, never a snapped bucket value,
 * so rounding error never compounds across slots.
 *
 * @param min  the lowest state value the table covers
 * @param max  the highest state value the table covers
 * @param size the width of one bucket
 */
public record StateBuckets(double min, double max, double size) {

	/**
	 * Counts how many buckets this range is divided into.
	 *
	 * @return the number of buckets this range is divided into
	 */
	public int count() {
		return (int) Math.round((this.max - this.min) / this.size) + 1;
	}

	/**
	 * Snaps a continuous state value to its nearest bucket index, clamped to
	 * the covered range. Used only to index into the table — never to
	 * advance state.
	 *
	 * @param state the continuous state value
	 * @return the nearest bucket index, in [0, count()-1]
	 */
	public int indexOf(double state) {
		var clamped = Math.max(this.min, Math.min(this.max, state));
		var index = (int) Math.round((clamped - this.min) / this.size);
		return Math.max(0, Math.min(this.count() - 1, index));
	}

	/**
	 * Converts a bucket index back to its continuous state value.
	 *
	 * @param index a bucket index
	 * @return the continuous state value that bucket represents
	 */
	public double valueOf(int index) {
		return this.min + index * this.size;
	}
}
