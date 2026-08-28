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

    @Test
    public void stoppedWorkerMustFinishBeforeAReplacementCanStart() {
        ShortestPathScript.WalkTaskGate gate = new ShortestPathScript.WalkTaskGate();
        WorldPoint target = new WorldPoint(3200, 3200, 0);

        assertTrue(gate.tryAcquire(target));
        assertFalse("clearing/cancelling a target must not release a worker that is still unwinding",
                gate.tryAcquire(target));

        gate.release();
        assertTrue(gate.tryAcquire(target));
    }

    @Test
    public void shutdownPermanentlyRejectsWalkWorkers() {
        ShortestPathScript.WalkTaskGate gate = new ShortestPathScript.WalkTaskGate();
        WorldPoint target = new WorldPoint(3200, 3200, 0);

        assertTrue(gate.tryAcquire(target));
        gate.shutdown();
        gate.release();

        assertTrue(gate.isShutdown());
        assertFalse(gate.tryAcquire(target));
    }
}
