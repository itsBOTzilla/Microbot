package net.runelite.client.plugins.microbot.util.mouse.naturalmouse;

import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DefaultMouseMotionNature;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DefaultSpeedManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NaturalMouseSpeedProfileTest
{
    @Test
    public void configuredIntensitiesHaveProgressivelyFasterBaseTimes()
    {
        assertBaseTime(ActivityIntensity.VERY_LOW, 1000);
        assertBaseTime(ActivityIntensity.LOW, 400);
        assertBaseTime(ActivityIntensity.MODERATE, 150);
        assertBaseTime(ActivityIntensity.HIGH, 120);
        assertBaseTime(ActivityIntensity.EXTREME, 90);
    }

    private static void assertBaseTime(ActivityIntensity intensity, long expected)
    {
        DefaultSpeedManager manager = (DefaultSpeedManager) NaturalMouse
                .createFactoryForIntensity(intensity, new DefaultMouseMotionNature())
                .getSpeedManager();
        assertEquals(expected, manager.getMouseMovementBaseTimeMs());
    }
}
