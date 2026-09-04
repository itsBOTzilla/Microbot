package net.runelite.client.plugins.microbot.shortestpath;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PathTileOverlayTest
{
    @Test
    public void everyRenderedWalkingTileHasAContinuousDisplayNumber() throws Exception
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3204, 3202, 0);
        List<?> tiles = visualizationTiles(List.of(start, end), Map.of());

        assertEquals(5, tiles.size());
        assertTile(tiles.get(0), start, 0, 1);
        assertTile(tiles.get(1), new WorldPoint(3201, 3201, 0), -1, 2);
        assertTile(tiles.get(2), new WorldPoint(3202, 3202, 0), -1, 3);
        assertTile(tiles.get(3), new WorldPoint(3203, 3202, 0), -1, 4);
        assertTile(tiles.get(4), end, 1, 5);
    }

    @Test
    public void visualizationDoesNotMutateLogicalWalkablePath() throws Exception
    {
        List<WorldPoint> anchors = new ArrayList<>(List.of(
                new WorldPoint(3200, 3200, 0),
                new WorldPoint(3204, 3202, 0)));
        List<WorldPoint> before = List.copyOf(anchors);

        List<?> tiles = visualizationTiles(anchors, Map.of());

        assertEquals(before, anchors);
        assertEquals(2, anchors.size());
        assertEquals(5, tiles.size());
    }

    @Test
    public void transportGapIsNotRasterizedAndDisplayNumberingContinues() throws Exception
    {
        WorldPoint start = new WorldPoint(3029, 3217, 0);
        WorldPoint landing = new WorldPoint(2956, 3143, 0);
        WorldPoint nextWalkingTile = new WorldPoint(2958, 3143, 0);
        Transport ship = new Transport(start, landing, "ship", TransportType.SHIP, false, 10);

        List<?> tiles = visualizationTiles(
                List.of(start, landing, nextWalkingTile), Map.of(start, Set.of(ship)));

        assertEquals(4, tiles.size());
        assertTile(tiles.get(0), start, 0, 1);
        assertTile(tiles.get(1), landing, 1, 2);
        assertTile(tiles.get(2), new WorldPoint(2957, 3143, 0), -1, 3);
        assertTile(tiles.get(3), nextWalkingTile, 2, 4);
    }

    @Test
    public void crossPlaneGapIsNotRasterized() throws Exception
    {
        WorldPoint lower = new WorldPoint(3200, 3200, 0);
        WorldPoint upper = new WorldPoint(3204, 3202, 1);

        List<?> tiles = visualizationTiles(List.of(lower, upper), Map.of());

        assertEquals(2, tiles.size());
        assertTile(tiles.get(0), lower, 0, 1);
        assertTile(tiles.get(1), upper, 1, 2);
    }

    private static List<?> visualizationTiles(List<WorldPoint> anchors, Map<WorldPoint, Set<Transport>> transports)
            throws Exception
    {
        Method visualizer;
        try
        {
            visualizer = PathTileOverlay.class.getDeclaredMethod("visualizationTiles", List.class, Map.class);
        }
        catch (NoSuchMethodException e)
        {
            fail("scene overlay must fill ordinary walking tiles between cached walkable anchors");
            return List.of();
        }
        visualizer.setAccessible(true);
        return (List<?>) visualizer.invoke(null, anchors, transports);
    }

    private static void assertTile(Object tile, WorldPoint expectedPoint, int expectedAnchorIndex,
                                   int expectedDisplayIndex)
            throws Exception
    {
        Method point = tile.getClass().getDeclaredMethod("point");
        Method anchorIndex = tile.getClass().getDeclaredMethod("anchorIndex");
        Method displayIndex;
        try
        {
            displayIndex = tile.getClass().getDeclaredMethod("displayIndex");
        }
        catch (NoSuchMethodException e)
        {
            fail("every visualization tile must have a display-only index");
            return;
        }
        point.setAccessible(true);
        anchorIndex.setAccessible(true);
        displayIndex.setAccessible(true);
        assertEquals(expectedPoint, point.invoke(tile));
        assertEquals(expectedAnchorIndex, anchorIndex.invoke(tile));
        assertEquals(expectedDisplayIndex, displayIndex.invoke(tile));
    }
}
