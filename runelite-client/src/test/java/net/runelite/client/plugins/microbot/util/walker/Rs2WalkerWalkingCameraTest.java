package net.runelite.client.plugins.microbot.util.walker;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
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

    @Test
    public void nullOrEmptyPathHasNoLookAhead()
    {
        assertNull(Rs2Walker.getCameraLookAhead(null, 0, PLAYER));
        assertNull(Rs2Walker.getCameraLookAhead(Collections.emptyList(), 0, PLAYER));
    }

    @Test
    public void ignoresNodesAtOrBehindCurrentPathIndex()
    {
        List<WorldPoint> path = List.of(
                point(9, 0),
                point(8, 0),
                PLAYER,
                point(6, 0));

        assertEquals(point(6, 0), Rs2Walker.getCameraLookAhead(path, 2, PLAYER));
    }

    @Test
    public void selectsDistanceClosestToNineAndBreaksTiesForward()
    {
        List<WorldPoint> path = List.of(
                PLAYER,
                point(6, 0),
                point(8, 0),
                point(10, 0),
                point(12, 0));

        assertEquals(point(10, 0), Rs2Walker.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void planeChangeEndsLookAheadSearch()
    {
        List<WorldPoint> path = List.of(
                PLAYER,
                point(6, 0),
                new WorldPoint(3207, 3200, 1),
                point(9, 0));

        assertEquals(point(6, 0), Rs2Walker.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void firstExitFromHorizonPreventsFoldBackSelection()
    {
        List<WorldPoint> path = List.of(
                PLAYER,
                point(6, 0),
                point(13, 0),
                point(9, 0));

        assertEquals(point(6, 0), Rs2Walker.getCameraLookAhead(path, 0, PLAYER));
    }

    @Test
    public void routeWithNoNodeInLookAheadBandReturnsNull()
    {
        assertNull(Rs2Walker.getCameraLookAhead(
                List.of(PLAYER, point(1, 0), point(5, 0)), 0, PLAYER));
    }

    @Test
    public void directionDifferenceWrapsAcrossNorth()
    {
        assertEquals(10, Rs2Walker.cameraDirectionDifference(355, 5));
        assertEquals(10, Rs2Walker.cameraDirectionDifference(5, 355));
    }

    @Test
    public void onlyMeaningfulTurnsBypassNormalTiming()
    {
        assertFalse(Rs2Walker.isMeaningfulCameraTurn(10, 30));
        assertTrue(Rs2Walker.isMeaningfulCameraTurn(10, 100));
    }

    @Test
    public void rejectedOrShortDispatchCannotScheduleCameraWork()
    {
        assertFalse(Rs2Walker.canScheduleWalkingCamera(
                point(9, 0), PLAYER, null, 7L, 7L, false));
        assertFalse(Rs2Walker.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(7, 0), 7L, 7L, false));
    }

    @Test
    public void acceptedLongDispatchCanScheduleCameraWork()
    {
        assertTrue(Rs2Walker.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(8, 0), 7L, 7L, false));
    }

    @Test
    public void staleGenerationCannotScheduleCameraWork()
    {
        assertFalse(Rs2Walker.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(8, 0), 6L, 7L, false));
    }

    @Test
    public void inFlightUpdatePreventsAnotherCameraWorkItem()
    {
        assertFalse(Rs2Walker.canScheduleWalkingCamera(
                point(9, 0), PLAYER, point(8, 0), 7L, 7L, true));
    }

    @Test
    public void cameraYawRequiresCurrentActiveRouteWithoutHumanInput()
    {
        assertTrue(Rs2Walker.canApplyWalkingCameraYaw(7L, 7L, true, false));
        assertFalse(Rs2Walker.canApplyWalkingCameraYaw(6L, 7L, true, false));
        assertFalse(Rs2Walker.canApplyWalkingCameraYaw(7L, 7L, false, false));
        assertFalse(Rs2Walker.canApplyWalkingCameraYaw(7L, 7L, true, true));
    }

    @Test
    public void finalYawUpdateIsGuardedInsideOneClientThreadAction() throws Exception
    {
        MethodNode applyUpdate = findWalkerMethod("applyWalkingCameraUpdate");
        MethodNode setYawIfCurrent = findWalkerMethod("setWalkingCameraYawIfCurrent");

        assertEquals("worker must not call the camera setter directly", -1,
                invocationIndex(applyUpdate, Rs2Camera.class, "setYaw"));
        assertTrue("final yaw update must be dispatched through the client thread",
                invocationIndex(setYawIfCurrent, ClientThread.class,
                        "runOnClientThreadOptional") >= 0);

        MethodNode clientThreadAction = findWalkerNestedMethod(
                "Rs2Walker$WalkingCameraYawUpdate.class", "call");
        int guard = invocationIndex(clientThreadAction, Rs2Walker.class,
                "canApplyWalkingCameraYaw");
        int cameraUpdate = invocationIndex(clientThreadAction, Rs2Camera.class, "setYaw");
        assertTrue("route and human-input guards must run before the camera update",
                guard >= 0 && guard < cameraUpdate);
    }

    @Test
    public void cameraHookRunsOnlyAfterAcceptedClickBookkeeping() throws Exception
    {
        MethodNode dispatchMovement = findRuntimeMethod("dispatchMinimap");
        int acceptedCheck = invocationIndex(dispatchMovement, WebWalkRuntime.DispatchResult.class,
                "isAccepted");
        int clickBookkeeping = invocationIndex(dispatchMovement, Rs2Walker.class,
                "markFirstMovementClick");
        int cameraUpdate = invocationIndex(dispatchMovement, Rs2Walker.class,
                "updateWalkingCamera");

        assertTrue("dispatch acceptance must be checked before click bookkeeping",
                acceptedCheck >= 0 && acceptedCheck < clickBookkeeping);
        assertTrue("camera work must begin after click bookkeeping",
                clickBookkeeping < cameraUpdate);
    }

    private static MethodNode findRuntimeMethod(String name) throws Exception
    {
        return findMethod(RuneLiteWebWalkRuntime.class, name, false);
    }

    private static MethodNode findWalkerMethod(String name) throws Exception
    {
        return findMethod(Rs2Walker.class, name, false);
    }

    private static MethodNode findWalkerNestedMethod(String resourceName, String name)
            throws Exception
    {
        try (InputStream input = Rs2Walker.class.getResourceAsStream(resourceName)) {
            assertNotNull(resourceName + " resource missing", input);
            ClassNode classNode = new ClassNode();
            new ClassReader(input).accept(classNode, 0);
            return classNode.methods.stream()
                    .filter(method -> method.name.equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " method missing"));
        }
    }

    private static MethodNode findMethod(Class<?> owner, String name, boolean prefix)
            throws Exception
    {
        String resourceName = owner.getSimpleName() + ".class";
        try (InputStream input = owner.getResourceAsStream(resourceName)) {
            assertNotNull(resourceName + " resource missing", input);
            ClassNode classNode = new ClassNode();
            new ClassReader(input).accept(classNode, 0);
            return classNode.methods.stream()
                    .filter(method -> prefix ? method.name.startsWith(name)
                            : method.name.equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " method missing"));
        }
    }

    private static int invocationIndex(MethodNode method, Class<?> owner, String name)
    {
        String internalOwner = org.objectweb.asm.Type.getInternalName(owner);
        for (int index = 0; index < method.instructions.size(); index++) {
            if (method.instructions.get(index) instanceof MethodInsnNode) {
                MethodInsnNode invocation = (MethodInsnNode) method.instructions.get(index);
                if (invocation.owner.equals(internalOwner) && invocation.name.equals(name)) {
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
}
