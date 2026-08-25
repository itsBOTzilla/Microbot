package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShortestPathWalkTaskPolicyTest {

    @Test
    public void movingSameTargetKeepsCurrentWalkTaskAlive() {
        WorldPoint target = new WorldPoint(3200, 3200, 0);

        assertTrue(ShortestPathScript.shouldContinueWalkTask(target, target, WalkerState.MOVING));
    }

    @Test
    public void terminalOrReplacedTargetStopsCurrentWalkTask() {
        WorldPoint target = new WorldPoint(3200, 3200, 0);

        assertFalse(ShortestPathScript.shouldContinueWalkTask(target, target, WalkerState.ARRIVED));
        assertFalse(ShortestPathScript.shouldContinueWalkTask(
                target,
                new WorldPoint(3201, 3200, 0),
                WalkerState.MOVING));
    }
}
