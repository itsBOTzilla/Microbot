package net.runelite.client.plugins.microbot.util.walker;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WalkingCameraCoordinatorTest
{
    private static final WorldPoint PLAYER = new WorldPoint(3200, 3200, 0);
    private static final long TARGET_GENERATION = 41L;

    @Test
    public void sameTargetReplanInvalidatesQueuedYaw() throws Exception
    {
        try (Scenario scenario = new Scenario())
        {
            scenario.scheduleYaw();
            scenario.clientThread.awaitAction();

            long replanRevision = scenario.coordinator.invalidateRoute();
            long replacementRevision = scenario.coordinator.publishRoute(
                    replanRevision, TARGET_GENERATION, replacementPath(), 0);
            assertTrue(replacementRevision > scenario.request.getRouteRevision());

            scenario.clientThread.releaseAction();
            scenario.awaitIdle();
            assertEquals(0, scenario.yawUpdates.get());
        }
    }

    @Test
    public void changedPathInvalidatesQueuedYaw() throws Exception
    {
        try (Scenario scenario = new Scenario())
        {
            scenario.scheduleYaw();
            scenario.clientThread.awaitAction();

            long replacementRevision = scenario.coordinator.publishRoute(
                    scenario.request.getRouteRevision(), TARGET_GENERATION,
                    replacementPath(), 0);
            assertTrue(replacementRevision > scenario.request.getRouteRevision());

            scenario.clientThread.releaseAction();
            scenario.awaitIdle();
            assertEquals(0, scenario.yawUpdates.get());
        }
    }

    @Test
    public void stalePublisherCannotReactivateInvalidatedRoute()
    {
        WalkingCameraCoordinator coordinator = new WalkingCameraCoordinator(
                Runnable::run, new ImmediateClientThread());
        long observedRevision = coordinator.getRouteRevision();

        coordinator.invalidateRoute();

        assertEquals(-1L, coordinator.publishRoute(
                observedRevision, TARGET_GENERATION, initialPath(), 0));
        assertNull(coordinator.request(point(9), TARGET_GENERATION, observedRevision));
    }

    @Test
    public void cancelledWalkInvalidatesQueuedYaw() throws Exception
    {
        try (Scenario scenario = new Scenario())
        {
            scenario.scheduleYaw();
            scenario.clientThread.awaitAction();

            scenario.coordinator.stopTravelling(TARGET_GENERATION);

            scenario.clientThread.releaseAction();
            scenario.awaitIdle();
            assertEquals(0, scenario.yawUpdates.get());
        }
    }

    @Test
    public void humanTakeoverBeforeClientActionSuppressesYaw() throws Exception
    {
        try (Scenario scenario = new Scenario())
        {
            scenario.scheduleYaw();
            scenario.clientThread.awaitAction();

            scenario.humanInput.set(true);

            scenario.clientThread.releaseAction();
            scenario.awaitIdle();
            assertEquals(0, scenario.yawUpdates.get());
        }
    }

    @Test
    public void lookAheadThatIsNoLongerFutureSuppressesYaw() throws Exception
    {
        try (Scenario scenario = new Scenario())
        {
            scenario.scheduleYaw();
            scenario.clientThread.awaitAction();

            scenario.coordinator.publishRoute(scenario.request.getRouteRevision(),
                    TARGET_GENERATION, initialPath(), 2);

            scenario.clientThread.releaseAction();
            scenario.awaitIdle();
            assertEquals(0, scenario.yawUpdates.get());
        }
    }

    @Test
    public void workerFailureReleasesInFlightGate() throws Exception
    {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            WalkingCameraCoordinator coordinator = new WalkingCameraCoordinator(
                    executor, new ImmediateClientThread());
            long revision = coordinator.publishRoute(coordinator.getRouteRevision(),
                    TARGET_GENERATION, initialPath(), 0);
            WalkingCameraCoordinator.Request request = coordinator.request(
                    point(9), TARGET_GENERATION, revision);
            assertNotNull(request);

            CountDownLatch failedTaskRan = new CountDownLatch(1);
            assertTrue(coordinator.trySchedule(request, () ->
            {
                failedTaskRan.countDown();
                throw new IllegalStateException("expected test failure");
            }));
            assertTrue(failedTaskRan.await(5, TimeUnit.SECONDS));
            awaitIdle(coordinator);

            CountDownLatch replacementRan = new CountDownLatch(1);
            assertTrue(coordinator.trySchedule(request, replacementRan::countDown));
            assertTrue(replacementRan.await(5, TimeUnit.SECONDS));
            awaitIdle(coordinator);
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void nanoTimeZeroIsNotUsedAsDeadlineSentinel()
    {
        assertFalse(Rs2Walker.isWalkingCameraUpdateDeferred(0L, 0L, false, false));
        assertTrue(Rs2Walker.isWalkingCameraUpdateDeferred(0L, 1L, true, false));
        assertFalse(Rs2Walker.isWalkingCameraUpdateDeferred(0L, 1L, true, true));
    }

    @Test
    public void deadlineComparisonSurvivesNanoTimeWraparound()
    {
        long beforeWrap = Long.MAX_VALUE - 2L;
        long afterWrap = Long.MIN_VALUE + 2L;

        assertFalse(Rs2Walker.isWalkingCameraUpdateDeferred(
                afterWrap, beforeWrap, true, false));
    }

    private static void awaitIdle(WalkingCameraCoordinator coordinator) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (coordinator.isUpdateInFlight() && System.nanoTime() - deadline < 0L)
        {
            Thread.yield();
        }
        assertFalse("camera update did not release its in-flight gate",
                coordinator.isUpdateInFlight());
    }

    private static List<WorldPoint> initialPath()
    {
        return List.of(PLAYER, point(6), point(9), point(12));
    }

    private static List<WorldPoint> replacementPath()
    {
        return List.of(PLAYER, new WorldPoint(3200, 3206, 0),
                new WorldPoint(3200, 3209, 0));
    }

    private static WorldPoint point(int xOffset)
    {
        return new WorldPoint(PLAYER.getX() + xOffset, PLAYER.getY(), PLAYER.getPlane());
    }

    private static final class Scenario implements AutoCloseable
    {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final BlockingClientThread clientThread = new BlockingClientThread();
        private final WalkingCameraCoordinator coordinator =
                new WalkingCameraCoordinator(executor, clientThread);
        private final AtomicBoolean humanInput = new AtomicBoolean();
        private final AtomicInteger yawUpdates = new AtomicInteger();
        private final WalkingCameraCoordinator.FinalState finalState =
                new WalkingCameraCoordinator.FinalState()
                {
                    @Override
                    public WorldPoint getPlayerLocation()
                    {
                        return PLAYER;
                    }

                    @Override
                    public boolean isTargetCurrent(long targetGeneration)
                    {
                        return targetGeneration == TARGET_GENERATION;
                    }

                    @Override
                    public boolean isHumanInput()
                    {
                        return humanInput.get();
                    }
                };
        private final WalkingCameraCoordinator.Request request;

        private Scenario()
        {
            long revision = coordinator.publishRoute(coordinator.getRouteRevision(),
                    TARGET_GENERATION, initialPath(), 0);
            request = coordinator.request(point(9), TARGET_GENERATION, revision);
            assertNotNull(request);
        }

        private void scheduleYaw()
        {
            assertTrue(coordinator.trySchedule(request,
                    () -> coordinator.dispatchIfCurrent(
                            request, finalState, yawUpdates::incrementAndGet)));
        }

        private void awaitIdle() throws Exception
        {
            WalkingCameraCoordinatorTest.awaitIdle(coordinator);
        }

        @Override
        public void close() throws Exception
        {
            clientThread.releaseAction();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class BlockingClientThread
            implements WalkingCameraCoordinator.ClientThreadDispatcher
    {
        private final CountDownLatch actionReady = new CountDownLatch(1);
        private final CountDownLatch actionRelease = new CountDownLatch(1);

        @Override
        public Optional<Boolean> dispatch(Callable<Boolean> action)
        {
            actionReady.countDown();
            try
            {
                if (!actionRelease.await(5, TimeUnit.SECONDS))
                {
                    throw new AssertionError("client action was not released");
                }
                return Optional.ofNullable(action.call());
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex);
            }
        }

        private void awaitAction() throws Exception
        {
            assertTrue("camera update never reached the client-thread boundary",
                    actionReady.await(5, TimeUnit.SECONDS));
        }

        private void releaseAction()
        {
            actionRelease.countDown();
        }
    }

    private static final class ImmediateClientThread
            implements WalkingCameraCoordinator.ClientThreadDispatcher
    {
        @Override
        public Optional<Boolean> dispatch(Callable<Boolean> action)
        {
            try
            {
                return Optional.ofNullable(action.call());
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex);
            }
        }
    }
}
