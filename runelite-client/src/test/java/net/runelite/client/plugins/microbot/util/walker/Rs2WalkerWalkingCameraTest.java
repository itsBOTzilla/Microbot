package net.runelite.client.plugins.microbot.util.walker;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Rs2WalkerWalkingCameraTest
{
    private static final WorldPoint PLAYER = new WorldPoint(3200, 3200, 0);
    private static final WorldPoint GOAL = new WorldPoint(3250, 3250, 0);

    @Test
    public void nullOrEmptyPathHasNoLookAhead()
    {
        assertNull(RuneLiteWebWalkRuntime.getCameraLookAhead(null, 0, PLAYER));
        assertNull(RuneLiteWebWalkRuntime.getCameraLookAhead(
                Collections.emptyList(), 0, PLAYER));
    }

    @Test
    public void ignoresNodesAtOrBehindCurrentPathIndex()
    {
        List<WorldPoint> path = List.of(point(9, 0), point(8, 0), PLAYER, point(6, 0));

        assertEquals(point(6, 0),
                RuneLiteWebWalkRuntime.getCameraLookAhead(path, 2, PLAYER));
    }

    @Test
    public void selectsDistanceClosestToNineAndBreaksTiesForward()
    {
        List<WorldPoint> path = List.of(
                PLAYER, point(6, 0), point(8, 0), point(10, 0), point(12, 0));

        assertEquals(point(10, 0),
                RuneLiteWebWalkRuntime.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void planeChangeEndsLookAheadSearch()
    {
        List<WorldPoint> path = List.of(
                PLAYER, point(6, 0), new WorldPoint(3207, 3200, 1), point(9, 0));

        assertEquals(point(6, 0),
                RuneLiteWebWalkRuntime.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void firstExitFromHorizonPreventsFoldBackSelection()
    {
        List<WorldPoint> path = List.of(PLAYER, point(6, 0), point(13, 0), point(9, 0));

        assertEquals(point(6, 0),
                RuneLiteWebWalkRuntime.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void accumulatedRouteHorizonStopsAnEntirelyNearbyUShapeFoldingForward()
    {
        List<WorldPoint> path = new ArrayList<>();
        path.add(PLAYER);
        for (int x = 1; x <= 9; x++)
        {
            path.add(point(x, 0));
        }
        for (int y = 1; y <= 6; y++)
        {
            path.add(point(9, y));
        }
        for (int x = 8; x >= 0; x--)
        {
            path.add(point(x, 6));
        }

        assertEquals(point(9, 3),
                RuneLiteWebWalkRuntime.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void routeWithNoNodeInLookAheadBandReturnsNull()
    {
        assertNull(RuneLiteWebWalkRuntime.getCameraLookAhead(
                List.of(PLAYER, point(1, 0), point(5, 0)), 0, PLAYER));
    }

    @Test
    public void directionDifferenceWrapsAcrossNorth()
    {
        assertEquals(10, RuneLiteWebWalkRuntime.cameraDirectionDifference(355, 5));
        assertEquals(10, RuneLiteWebWalkRuntime.cameraDirectionDifference(5, 355));
    }

    @Test
    public void initialDirectionNeverBypassesStartupGrace()
    {
        assertFalse(RuneLiteWebWalkRuntime.isMeaningfulCameraTurn(-1, 180));
        assertFalse(RuneLiteWebWalkRuntime.isMeaningfulCameraTurn(10, 30));
        assertTrue(RuneLiteWebWalkRuntime.isMeaningfulCameraTurn(10, 100));
    }

    @Test
    public void startupGraceDefersUntilItsDeadlineIncludingNanoTimeZero()
    {
        long deadline = RuneLiteWebWalkRuntime.walkingCameraDeadlineNanos(0L, 6_000);

        assertTrue(RuneLiteWebWalkRuntime.isWalkingCameraUpdateDeferred(
                0L, deadline, true, false));
        assertTrue(RuneLiteWebWalkRuntime.isWalkingCameraUpdateDeferred(
                deadline - 1L, deadline, true, false));
        assertFalse(RuneLiteWebWalkRuntime.isWalkingCameraUpdateDeferred(
                deadline, deadline, true, false));
    }

    @Test
    public void deadlineComparisonSurvivesNanoTimeWraparound()
    {
        long deadline = Long.MAX_VALUE - 5L;

        assertTrue(RuneLiteWebWalkRuntime.isWalkingCameraUpdateDeferred(
                deadline - 1L, deadline, true, false));
        assertFalse(RuneLiteWebWalkRuntime.isWalkingCameraUpdateDeferred(
                Long.MIN_VALUE + 5L, deadline, true, false));
    }

    @Test
    public void startupCadenceToleranceAndYawBoundsRemainConservative() throws Exception
    {
        assertEquals(5_000, runtimeIntConstant("CAMERA_MIN_STARTUP_GRACE_MS"));
        assertEquals(10_000, runtimeIntConstant("CAMERA_MAX_STARTUP_GRACE_MS"));
        assertEquals(8_000, runtimeIntConstant("CAMERA_MIN_UPDATE_INTERVAL_MS"));
        assertEquals(15_000, runtimeIntConstant("CAMERA_MAX_UPDATE_INTERVAL_MS"));
        assertEquals(45, runtimeIntConstant("CAMERA_MIN_TOLERANCE_PERCENT"));
        assertEquals(60, runtimeIntConstant("CAMERA_MAX_TOLERANCE_PERCENT"));
        assertEquals(96, runtimeIntConstant("CAMERA_MIN_YAW_STEP"));
        assertEquals(160, runtimeIntConstant("CAMERA_MAX_YAW_STEP"));

        long now = 2_000L;
        assertEquals(now + TimeUnit.SECONDS.toNanos(5),
                RuneLiteWebWalkRuntime.walkingCameraDeadlineNanos(now, 5_000));
        assertEquals(now + TimeUnit.SECONDS.toNanos(15),
                RuneLiteWebWalkRuntime.walkingCameraDeadlineNanos(now, 15_000));
    }

    @Test
    public void yawCorrectionUsesTheShortestBoundedStep()
    {
        assertEquals(72, RuneLiteWebWalkRuntime.boundedCameraYaw(2_000, 100, 120));
        assertEquals(2_028, RuneLiteWebWalkRuntime.boundedCameraYaw(100, 2_000, 120));
        assertEquals(550, RuneLiteWebWalkRuntime.boundedCameraYaw(500, 550, 120));
    }

    @Test
    public void schedulingRequiresLongCurrentSamePlaneDispatch()
    {
        assertFalse(RuneLiteWebWalkRuntime.canScheduleWalkingCamera(
                point(9, 0), PLAYER, null, 7L, 7L, false));
        assertFalse(RuneLiteWebWalkRuntime.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(7, 0), 7L, 7L, false));
        assertFalse(RuneLiteWebWalkRuntime.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(8, 0), 6L, 7L, false));
        assertFalse(RuneLiteWebWalkRuntime.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(8, 0), 7L, 7L, true));
        assertTrue(RuneLiteWebWalkRuntime.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(8, 0), 7L, 7L, false));
    }

    @Test
    public void sameTargetReplanSuppressesQueuedYaw() throws Exception
    {
        try (CameraHarness harness = new CameraHarness())
        {
            assertTrue(harness.queue());
            harness.runtime.invalidateWalkingCameraRoute();
            harness.runNext();

            assertEquals(0, harness.yawWrites.get());
        }
    }

    @Test
    public void stopOrCancelSuppressesQueuedYaw() throws Exception
    {
        try (CameraHarness harness = new CameraHarness())
        {
            assertTrue(harness.queue());
            harness.runtime.stopWalkingCamera();
            harness.runNext();

            assertEquals(0, harness.yawWrites.get());
        }
    }

    @Test
    public void humanTakeoverSuppressesQueuedYaw() throws Exception
    {
        try (CameraHarness harness = new CameraHarness())
        {
            assertTrue(harness.queue());
            harness.human.set(true);
            harness.runNext();

            assertEquals(0, harness.yawWrites.get());
        }
    }

    @Test
    public void routeProgressPastLookAheadSuppressesQueuedYaw() throws Exception
    {
        try (CameraHarness harness = new CameraHarness())
        {
            assertTrue(harness.queue());
            harness.runtime.installWalkingCameraRoute(harness.path, 2);
            harness.runNext();

            assertEquals(0, harness.yawWrites.get());
        }
    }

    @Test
    public void callbackFailureReleasesQueuedGate() throws Exception
    {
        try (CameraHarness harness = new CameraHarness())
        {
            harness.failPlayerRead.set(true);
            assertTrue(harness.queue());
            harness.runNext();
            harness.failPlayerRead.set(false);

            assertTrue(harness.queue());
        }
    }

    @Test
    public void queuedCallbackBlocksDuplicateWork() throws Exception
    {
        try (CameraHarness harness = new CameraHarness())
        {
            assertTrue(harness.queue());
            assertFalse(harness.queue());
        }
    }

    @Test
    public void acceptedDispatchQueuesCameraAfterClickBookkeeping() throws Exception
    {
        MethodNode dispatch = findRuntimeMethod("dispatchMinimap");
        int accepted = invocationIndex(dispatch, WebWalkRuntime.DispatchResult.class,
                "isAccepted");
        int click = invocationIndex(dispatch, Rs2Walker.class, "markFirstMovementClick");
        int camera = invocationIndex(dispatch, RuneLiteWebWalkRuntime.class,
                "scheduleWalkingCamera");

        assertTrue(accepted >= 0 && accepted < click);
        assertTrue(click < camera);
    }

    @Test
    public void replanInvalidatesCameraBeforeRestartingPathfinder() throws Exception
    {
        MethodNode replan = findRuntimeMethod("replan");
        int invalidate = invocationIndex(replan, RuneLiteWebWalkRuntime.class,
                "invalidateWalkingCameraRoute");
        int recalculate = invocationIndex(replan, Rs2Walker.class, "recalculatePath");

        assertTrue(invalidate >= 0 && invalidate < recalculate);
    }

    private static int runtimeIntConstant(String name) throws Exception
    {
        java.lang.reflect.Field field = RuneLiteWebWalkRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static MethodNode findRuntimeMethod(String name) throws Exception
    {
        try (InputStream input = RuneLiteWebWalkRuntime.class.getResourceAsStream(
                "RuneLiteWebWalkRuntime.class"))
        {
            assertNotNull(input);
            ClassNode classNode = new ClassNode();
            new ClassReader(input).accept(classNode, 0);
            return classNode.methods.stream()
                    .filter(method -> method.name.equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " method missing"));
        }
    }

    private static int invocationIndex(MethodNode method, Class<?> owner, String name)
    {
        String internalOwner = org.objectweb.asm.Type.getInternalName(owner);
        for (int index = 0; index < method.instructions.size(); index++)
        {
            if (method.instructions.get(index) instanceof MethodInsnNode)
            {
                MethodInsnNode invocation = (MethodInsnNode) method.instructions.get(index);
                if (invocation.owner.equals(internalOwner) && invocation.name.equals(name))
                {
                    return index;
                }
            }
        }
        return -1;
    }

    private static WorldPoint point(int xOffset, int yOffset)
    {
        return new WorldPoint(PLAYER.getX() + xOffset, PLAYER.getY() + yOffset,
                PLAYER.getPlane());
    }

    private static final class CameraHarness implements AutoCloseable
    {
        private final Queue<Runnable> callbacks = new ArrayDeque<>();
        private final AtomicBoolean human = new AtomicBoolean();
        private final AtomicBoolean failPlayerRead = new AtomicBoolean();
        private final AtomicInteger yawWrites = new AtomicInteger();
        private final List<WorldPoint> path = List.of(
                PLAYER, point(6, 0), point(9, 0), point(12, 0));
        private final RuneLiteWebWalkRuntime runtime;

        private CameraHarness()
        {
            long generation = Rs2Walker.updateCurrentTargetOwnership(GOAL);
            runtime = new RuneLiteWebWalkRuntime(GOAL, 0, generation,
                    callbacks::add,
                    () ->
                    {
                        if (failPlayerRead.get())
                        {
                            throw new IllegalStateException("test player read failed");
                        }
                        return PLAYER;
                    },
                    human::get, ignored -> yawWrites.incrementAndGet(),
                    System.nanoTime() - 1L);
            runtime.installWalkingCameraRoute(path, 0);
        }

        private boolean queue()
        {
            return runtime.scheduleWalkingCamera(PLAYER, point(8, 0));
        }

        private void runNext()
        {
            callbacks.remove().run();
        }

        @Override
        public void close()
        {
            Rs2Walker.clearWalkingRoute("walking-camera-test-cleanup");
        }
    }
}
