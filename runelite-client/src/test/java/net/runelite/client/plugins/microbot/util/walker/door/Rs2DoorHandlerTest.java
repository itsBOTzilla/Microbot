package net.runelite.client.plugins.microbot.util.walker.door;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Rs2DoorHandlerTest
{
    private static final long SUPPRESS_MS = 10_000L;

    @Test
    public void recentlyOpenedGateDoesNotSuppressNextStrongholdGate()
    {
        Map<WorldPoint, Long> recentlyOpened = new HashMap<>();
        recentlyOpened.put(new WorldPoint(1859, 5238, 0), System.currentTimeMillis());

        assertFalse(Rs2DoorHandler.recentlyOpenedStationaryDoorOnSegment(
                recentlyOpened,
                SUPPRESS_MS,
                new WorldPoint(1859, 5236, 0),
                new WorldPoint(1859, 5235, 0)));
    }

    @Test
    public void recentlyOpenedGateStillSuppressesItsOwnContinuationEdge()
    {
        Map<WorldPoint, Long> recentlyOpened = new HashMap<>();
        recentlyOpened.put(new WorldPoint(1865, 5226, 0), System.currentTimeMillis());

        assertTrue(Rs2DoorHandler.recentlyOpenedStationaryDoorOnSegment(
                recentlyOpened,
                SUPPRESS_MS,
                new WorldPoint(1865, 5226, 0),
                new WorldPoint(1866, 5226, 0)));
    }
}
