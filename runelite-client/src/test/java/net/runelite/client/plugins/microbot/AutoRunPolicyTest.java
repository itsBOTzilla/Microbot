package net.runelite.client.plugins.microbot;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoRunPolicyTest
{
	@Test
	public void thresholdIsInclusiveAndRerolledAfterActivation()
	{
		AtomicInteger sample = new AtomicInteger(50);
		AutoRunPolicy policy = new AutoRunPolicy((minimum, maximum) -> sample.get());

		assertEquals(50, policy.getThresholdPercent());
		assertFalse(policy.shouldEnable(4999, false));
		assertTrue(policy.shouldEnable(5000, false));
		assertFalse(policy.shouldEnable(10000, true));

		sample.set(100);
		policy.onRunEnabled();
		assertEquals(100, policy.getThresholdPercent());
		assertFalse(policy.shouldEnable(9999, false));
		assertTrue(policy.shouldEnable(10000, false));
	}

	@Test
	public void thresholdSamplesAreClampedToFiftyThroughOneHundred()
	{
		AutoRunPolicy low = new AutoRunPolicy((minimum, maximum) -> 1);
		AutoRunPolicy high = new AutoRunPolicy((minimum, maximum) -> 500);

		assertEquals(50, low.getThresholdPercent());
		assertEquals(100, high.getThresholdPercent());
	}
}
