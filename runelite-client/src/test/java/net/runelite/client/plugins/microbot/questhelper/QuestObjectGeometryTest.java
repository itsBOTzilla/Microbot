package net.runelite.client.plugins.microbot.questhelper;

import net.runelite.api.*;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class QuestObjectGeometryTest
{
    private final int[][] flags = new int[104][104];
    private final WorldView view = scene();
    private final WorldPoint player = new WorldPoint(1634, 4837, 0);

    @Test
    public void adjacentMisthalinWallUsesSingleTilesDespiteNeighboringCollision()
    {
        WallObject wall = mock(WallObject.class);
        WorldPoint wallPoint = new WorldPoint(1633, 4837, 0);
        when(wall.getWorldLocation()).thenReturn(wallPoint);
        // The legacy fabricated 2x2 areas overlap and RuneLite rejects their LOS.
        assertFalse(new WorldArea(wallPoint, 2, 2).hasLineOfSightTo(view, new WorldArea(player, 2, 2)));
        flags[33][38] = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
        flags[34][38] = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
        assertTrue(QuestObjectGeometry.hasLineOfSight(view, player, wall));
        WorldArea area = QuestObjectGeometry.area(view, wall);
        assertEquals(1, area.getWidth());
        assertEquals(1, area.getHeight());
        assertEquals(player, QuestScript.selectObjectApproachTile(area, player, player::equals,
                point -> QuestObjectGeometry.hasLineOfSight(view, point, wall)));
    }

    @Test
    public void realBarrierBetweenPlayerAndWallStillRejectsInteraction()
    {
        WallObject wall = mock(WallObject.class);
        when(wall.getWorldLocation()).thenReturn(new WorldPoint(1633, 4837, 0));
        flags[34][37] = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST;
        flags[33][37] = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST;
        assertFalse(QuestObjectGeometry.hasLineOfSight(view, player, wall));
    }

    @Test
    public void gameObjectKeepsFullFootprintWhenNearestTileIsObstructed()
    {
        GameObject object = mock(GameObject.class);
        when(object.getSceneMinLocation()).thenReturn(new Point(31, 36));
        when(object.sizeX()).thenReturn(2);
        when(object.sizeY()).thenReturn(3);
        when(object.getPlane()).thenReturn(0);
        flags[32][36] = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
        WorldArea area = QuestObjectGeometry.area(view, object);
        assertEquals(new WorldPoint(1631, 4836, 0), area.toWorldPoint());
        assertEquals(2, area.getWidth());
        assertEquals(3, area.getHeight());
        assertFalse(area.toWorldPoint().toWorldArea().hasLineOfSightTo(view, player.toWorldArea()));
        assertTrue(QuestObjectGeometry.hasLineOfSight(view, player, object));
        assertFalse(QuestObjectGeometry.hasLineOfSight(view, new WorldPoint(1634, 4837, 1), object));
    }

    private WorldView scene()
    {
        WorldView sceneView = mock(WorldView.class);
        Scene scene = mock(Scene.class);
        Tile[][][] tiles = new Tile[1][104][104];
        for (int x = 30; x <= 35; x++)
        {
            for (int y = 35; y <= 39; y++)
            {
                Tile tile = mock(Tile.class);
                when(tile.getPlane()).thenReturn(0);
                when(tile.getSceneLocation()).thenReturn(new Point(x, y));
                tiles[0][x][y] = tile;
            }
        }
        when(sceneView.getBaseX()).thenReturn(1600);
        when(sceneView.getBaseY()).thenReturn(4800);
        when(sceneView.getSizeX()).thenReturn(104);
        when(sceneView.getSizeY()).thenReturn(104);
        when(sceneView.getScene()).thenReturn(scene);
        when(sceneView.getCollisionMaps()).thenReturn(new CollisionData[]{() -> flags});
        when(scene.getTiles()).thenReturn(tiles);
        return sceneView;
    }
}
