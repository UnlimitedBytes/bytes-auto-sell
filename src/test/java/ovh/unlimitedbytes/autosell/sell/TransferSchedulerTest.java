package ovh.unlimitedbytes.autosell.sell;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferSchedulerTest {

	@Test
	void withoutRandomizationReturnsBase() {
		TransferScheduler scheduler = new TransferScheduler(new Random(1));
		assertEquals(0, scheduler.nextDelayTicks(0, false));
		assertEquals(1, scheduler.nextDelayTicks(1, false));
		assertEquals(20, scheduler.nextDelayTicks(20, false));
	}

	@Test
	void randomizedDelayStaysWithinBaseAndDoubleBase() {
		TransferScheduler scheduler = new TransferScheduler(new Random(42));
		for (int base = 0; base <= 20; base++) {
			for (int i = 0; i < 500; i++) {
				int delay = scheduler.nextDelayTicks(base, true);
				assertTrue(delay >= base, "delay below base for base=" + base);
				assertTrue(delay <= 2 * base, "delay above 2x base for base=" + base);
			}
		}
	}

	@Test
	void negativeBaseIsTreatedAsZero() {
		TransferScheduler scheduler = new TransferScheduler(new Random(7));
		assertEquals(0, scheduler.nextDelayTicks(-5, false));
		assertEquals(0, scheduler.nextDelayTicks(-5, true));
	}
}
