package net.runelite.client.plugins.microbot.questhelper;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestLocalApproachTest
{
    @Test
    public void openSceneSearchUsesPhysicalCoordinatesAndBoundedSteps()
    {
        WorldView scene = scene(new int[104][104]);
        WorldPoint start = point(50, 50);
        Set<WorldPoint> reachable = QuestLocalApproach.reachable(scene, start, 2);
        assertEquals(25, reachable.size());
        assertTrue(reachable.contains(point(52, 52)));
        assertFalse(reachable.contains(point(53, 50)));
        assertEquals(Collections.singleton(start), QuestLocalApproach.reachable(scene, start, 0));
    }

    @Test
    public void solidWallPreventsCrossingEvenWithDiagonalDetours()
    {
        int[][] flags = new int[104][104];
        for (int y = 0; y < 104; y++)
        {
            flags[51][y] = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
            flags[50][y] = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        }
        Set<WorldPoint> reachable = QuestLocalApproach.reachable(scene(flags), point(50, 50), 8);
        assertFalse(reachable.contains(point(51, 50)));
        assertTrue(reachable.contains(point(49, 50)));
        assertTrue(reachable.stream().allMatch(p -> p.getX() <= point(50, 50).getX()));
    }

    @Test
    public void cannotCutDiagonalCorner()
    {
        int[][] flags = new int[104][104];
        flags[51][50] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        flags[50][51] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        assertFalse(QuestLocalApproach.reachable(scene(flags), point(50, 50), 1).contains(point(51, 51)));
    }

    @Test
    public void sceneBoundaryAndMissingCollisionFailClosed()
    {
        assertEquals(4, QuestLocalApproach.reachable(scene(new int[104][104]), point(0, 0), 1).size());
        assertTrue(QuestLocalApproach.reachable(scene(null), point(50, 50), 2).isEmpty());
        assertTrue(QuestLocalApproach.reachable(scene(new int[104][104]), new WorldPoint(6450, 8050, 1), 2).isEmpty());
    }

    @Test
    public void objectApproachRequiresCardinalPerimeterOnSamePlane()
    {
        WorldArea object = new WorldArea(6450, 8050, 2, 2, 0);
        assertTrue(QuestLocalApproach.adjacentReachable(object, Collections.singleton(point(49, 51))));
        assertFalse(QuestLocalApproach.adjacentReachable(object, Collections.singleton(point(50, 50))));
        assertFalse(QuestLocalApproach.adjacentReachable(object, Collections.singleton(point(49, 49))));
        assertFalse(QuestLocalApproach.adjacentReachable(object,
            Collections.singleton(new WorldPoint(6449, 8051, 1))));
    }

    private static WorldPoint point(int sceneX, int sceneY)
    {
        return new WorldPoint(6400 + sceneX, 8000 + sceneY, 0);
    }

    private static WorldView scene(int[][] flags)
    {
        CollisionData[] maps = flags == null ? null : new CollisionData[]{() -> flags};
        return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(),
            new Class<?>[]{WorldView.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getBaseX": return 6400;
                    case "getBaseY": return 8000;
                    case "getSizeX":
                    case "getSizeY": return 104;
                    case "getPlane": return 0;
                    case "getId": return -1;
                    case "getCollisionMaps": return maps;
                    // Any attempted canonical instance conversion fails this test.
                    default: throw new AssertionError("Unexpected world view read: " + method.getName());
                }
            });
    }
}
