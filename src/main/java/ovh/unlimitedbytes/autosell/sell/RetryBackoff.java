package ovh.unlimitedbytes.autosell.sell;

/**
 * Capped exponential backoff for retrying failed cycle starts (sell GUI did not
 * open, empty command). The mod never gives up, so the backoff keeps the retry
 * rate polite on a flaky or high-ping connection instead of escalating forever:
 * {@code base, 2*base, 4*base, ... capped at MAX_TICKS}.
 */
public final class RetryBackoff {
	public static final int BASE_TICKS = 100;
	public static final int MAX_TICKS = 600;

	private RetryBackoff() {
	}

	/**
	 * Cooldown in ticks after the {@code consecutiveFailures}-th consecutive failed
	 * start. Values {@code <= 1} (including 0 and negatives) return the base delay;
	 * the result never exceeds {@link #MAX_TICKS} and is safe against overflow for
	 * any input.
	 */
	public static int cooldownTicks(int consecutiveFailures) {
		if (consecutiveFailures <= 1) {
			return BASE_TICKS;
		}
		int shift = Math.min(consecutiveFailures - 1, 16);
		long delay = (long) BASE_TICKS << shift;
		return (int) Math.min(delay, MAX_TICKS);
	}
}
