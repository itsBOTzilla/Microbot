package net.runelite.client.plugins.microbot;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntBinaryOperator;

/** Shared auto-run threshold policy. Energy values use RuneLite's 0-10,000 scale. */
final class AutoRunPolicy
{
	static final int MINIMUM_PERCENT = 50;
	static final int MAXIMUM_PERCENT = 100;
	private final IntBinaryOperator thresholdSource;
	private int thresholdPercent;

	static AutoRunPolicy create()
	{
		return new AutoRunPolicy((minimum, maximum) ->
			ThreadLocalRandom.current().nextInt(minimum, maximum + 1));
	}

	AutoRunPolicy(IntBinaryOperator thresholdSource)
	{
		this.thresholdSource = Objects.requireNonNull(thresholdSource, "thresholdSource");
		sampleThreshold();
	}

	synchronized boolean shouldEnable(int rawEnergy, boolean runEnabled)
	{
		return !runEnabled && rawEnergy >= thresholdPercent * 100;
	}

	synchronized void onRunEnabled()
	{
		sampleThreshold();
	}

	synchronized int getThresholdPercent()
	{
		return thresholdPercent;
	}

	private void sampleThreshold()
	{
		int sampled = thresholdSource.applyAsInt(MINIMUM_PERCENT, MAXIMUM_PERCENT);
		thresholdPercent = Math.max(MINIMUM_PERCENT, Math.min(MAXIMUM_PERCENT, sampled));
	}
}
