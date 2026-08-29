package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    @Test
    public void cancellingBeforeWorkerStartsReleasesGate() {
        ShortestPathScript.WalkTaskGate gate = new ShortestPathScript.WalkTaskGate();
        WorldPoint target = new WorldPoint(3200, 3200, 0);
        assertTrue(gate.tryAcquire(target));
        ShortestPathScript.GateReleasingFutureTask task =
                new ShortestPathScript.GateReleasingFutureTask(gate, () -> { });

        assertTrue(task.cancel(false));

        assertTrue(gate.tryAcquire(target));
    }

    @Test
    public void cancellingRunningWorkerKeepsGateUntilWorkerActuallyExits() throws Exception {
        ShortestPathScript.WalkTaskGate gate = new ShortestPathScript.WalkTaskGate();
        WorldPoint target = new WorldPoint(3200, 3200, 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        assertTrue(gate.tryAcquire(target));
        ShortestPathScript.GateReleasingFutureTask task =
                new ShortestPathScript.GateReleasingFutureTask(gate, () -> {
                    entered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = allowExit.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            // Deliberately remain in-flight to prove cancellation does not release early.
                        }
                    }
                });
        Thread worker = new Thread(task);
        worker.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        assertTrue(task.cancel(true));
        assertFalse(gate.tryAcquire(target));

        allowExit.countDown();
        worker.join(1_000L);
        assertFalse(worker.isAlive());
        assertTrue(gate.tryAcquire(target));
    }

    @Test
    public void replacedTargetCannotBeClearedByThePreviousWorker() {
        ShortestPathScript.WalkTargetState targets = new ShortestPathScript.WalkTargetState();
        WorldPoint firstTarget = new WorldPoint(3200, 3200, 0);
        WorldPoint replacementTarget = new WorldPoint(3210, 3210, 0);

        ShortestPathScript.WalkTargetSnapshot first = targets.publish(firstTarget);
        ShortestPathScript.WalkTargetSnapshot replacement = targets.publish(replacementTarget);

        assertFalse(targets.clearIfOwned(first));
        assertEquals(replacementTarget, targets.get());
        assertTrue(targets.clearIfOwned(replacement));
        assertNull(targets.get());
    }

    @Test
    public void replacementCannotPublishUntilOwnedRouteCleanupFinishes() throws Exception {
        ShortestPathScript.WalkTargetState targets = new ShortestPathScript.WalkTargetState();
        WorldPoint firstTarget = new WorldPoint(3200, 3200, 0);
        WorldPoint replacementTarget = new WorldPoint(3210, 3210, 0);
        ShortestPathScript.WalkTargetSnapshot first = targets.publish(firstTarget);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanupToFinish = new CountDownLatch(1);
        CountDownLatch replacementAttempted = new CountDownLatch(1);
        CountDownLatch replacementPublished = new CountDownLatch(1);

        Thread terminalWorker = new Thread(() -> targets.clearIfOwned(first, () -> {
            cleanupStarted.countDown();
            try {
                allowCleanupToFinish.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        terminalWorker.start();
        assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));

        Thread replacementWorker = new Thread(() -> {
            replacementAttempted.countDown();
            targets.publish(replacementTarget);
            replacementPublished.countDown();
        });
        replacementWorker.start();
        assertTrue(replacementAttempted.await(1, TimeUnit.SECONDS));
        assertFalse("replacement publication must wait until old route cleanup finishes",
                replacementPublished.await(100, TimeUnit.MILLISECONDS));

        allowCleanupToFinish.countDown();
        terminalWorker.join(1_000L);
        replacementWorker.join(1_000L);
        assertFalse(terminalWorker.isAlive());
        assertFalse(replacementWorker.isAlive());
        assertEquals(replacementTarget, targets.get());
    }

    @Test
    public void replacementCannotPublishUntilStopCleanupFinishes() throws Exception {
        ShortestPathScript.WalkTargetState targets = new ShortestPathScript.WalkTargetState();
        WorldPoint replacementTarget = new WorldPoint(3210, 3210, 0);
        targets.publish(new WorldPoint(3200, 3200, 0));
        ShortestPathScript.WalkTargetSnapshot stopped = targets.publish(null);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanupToFinish = new CountDownLatch(1);
        CountDownLatch replacementAttempted = new CountDownLatch(1);
        CountDownLatch replacementPublished = new CountDownLatch(1);

        Thread stopWorker = new Thread(() -> targets.clearIfOwned(stopped, () -> {
            cleanupStarted.countDown();
            try {
                allowCleanupToFinish.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        stopWorker.start();
        assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));

        Thread replacementWorker = new Thread(() -> {
            replacementAttempted.countDown();
            targets.publishIfChanged(replacementTarget);
            replacementPublished.countDown();
        });
        replacementWorker.start();
        assertTrue(replacementAttempted.await(1, TimeUnit.SECONDS));
        assertFalse("replacement publication must wait until stop cleanup finishes",
                replacementPublished.await(100, TimeUnit.MILLISECONDS));

        allowCleanupToFinish.countDown();
        stopWorker.join(1_000L);
        replacementWorker.join(1_000L);
        assertFalse(stopWorker.isAlive());
        assertFalse(replacementWorker.isAlive());
        assertEquals(replacementTarget, targets.get());
    }

    @Test
    public void shutdownTargetStateRejectsLaterPublication() {
        ShortestPathScript.WalkTargetState targets = new ShortestPathScript.WalkTargetState();
        targets.publish(new WorldPoint(3200, 3200, 0));

        targets.shutdown(null);

        assertNull(targets.publishIfChanged(new WorldPoint(3210, 3210, 0)));
        assertNull(targets.get());
    }
}
