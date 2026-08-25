package ovh.unlimitedbytes.autosell.sell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffTest {

	@Test
	void firstFailureWaitsBaseDelay() {
		assertEquals(RetryBackoff.BASE_TICKS, RetryBackoff.cooldownTicks(1));
	}

	@Test
	void nonPositiveFailureCountsAreBaseDelay() {
		assertEquals(RetryBackoff.BASE_TICKS, RetryBackoff.cooldownTicks(0));
		assertEquals(RetryBackoff.BASE_TICKS, RetryBackoff.cooldownTicks(-3));
	}

	@Test
	void doublesPerFailureUntilCap() {
		assertEquals(2 * RetryBackoff.BASE_TICKS, RetryBackoff.cooldownTicks(2));
		assertEquals(4 * RetryBackoff.BASE_TICKS, RetryBackoff.cooldownTicks(3));
	}

	@Test
	void capsAtMaxTicks() {
		assertEquals(RetryBackoff.MAX_TICKS, RetryBackoff.cooldownTicks(4));
		assertEquals(RetryBackoff.MAX_TICKS, RetryBackoff.cooldownTicks(100));
		// no int overflow even for absurd inputs
		assertEquals(RetryBackoff.MAX_TICKS, RetryBackoff.cooldownTicks(Integer.MAX_VALUE));
	}

	@Test
	void neverDecreasesAndNeverExceedsCap() {
		int previous = 0;
		for (int failures = 1; failures <= 64; failures++) {
			int delay = RetryBackoff.cooldownTicks(failures);
			assertTrue(delay >= previous, "delay decreased at " + failures);
			assertTrue(delay <= RetryBackoff.MAX_TICKS, "delay exceeded cap at " + failures);
			previous = delay;
		}
	}
}
