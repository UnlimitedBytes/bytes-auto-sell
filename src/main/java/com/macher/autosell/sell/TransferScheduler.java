package com.macher.autosell.sell;

import java.util.random.RandomGenerator;

/**
 * Computes the delay (in client ticks) between transfer bursts.
 * With randomization enabled the delay is {@code base + [0..base]}, i.e. between
 * 1x and 2x the configured base, to make the transfer cadence less robotic.
 */
public final class TransferScheduler {
	private final RandomGenerator random;

	public TransferScheduler() {
		this(new java.util.Random());
	}

	public TransferScheduler(RandomGenerator random) {
		this.random = random;
	}

	public int nextDelayTicks(int baseTicks, boolean randomized) {
		int base = Math.max(0, baseTicks);
		if (!randomized) {
			return base;
		}
		return base + random.nextInt(base + 1);
	}
}
