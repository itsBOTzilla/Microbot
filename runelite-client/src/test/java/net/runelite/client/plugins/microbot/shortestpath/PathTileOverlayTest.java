package net.runelite.client.plugins.microbot.shortestpath;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.PathfinderConfig;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
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
        assertTile(tiles.get(0), start, 0, 0);
        assertTile(tiles.get(1), new WorldPoint(3201, 3201, 0), -1, 1);
        assertTile(tiles.get(2), new WorldPoint(3202, 3202, 0), -1, 2);
        assertTile(tiles.get(3), new WorldPoint(3203, 3202, 0), -1, 3);
        assertTile(tiles.get(4), end, 1, 4);
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
        assertTile(tiles.get(0), start, 0, 0);
        assertTile(tiles.get(1), landing, 1, 1);
        assertTile(tiles.get(2), new WorldPoint(2957, 3143, 0), -1, 2);
        assertTile(tiles.get(3), nextWalkingTile, 2, 3);
    }

    @Test
    public void crossPlaneGapIsNotRasterized() throws Exception
    {
        WorldPoint lower = new WorldPoint(3200, 3200, 0);
        WorldPoint upper = new WorldPoint(3204, 3202, 1);

        List<?> tiles = visualizationTiles(List.of(lower, upper), Map.of());

        assertEquals(2, tiles.size());
        assertTile(tiles.get(0), lower, 0, 0);
        assertTile(tiles.get(1), upper, 1, 1);
    }

    @Test
    public void visualizationSnapshotIsSharedAndFreezesTransportEdges() throws Exception
    {
        WorldPoint start = new WorldPoint(3029, 3217, 0);
        WorldPoint landing = new WorldPoint(2956, 3143, 0);
        WorldPoint nextWalkingTile = new WorldPoint(2958, 3143, 0);
        Set<Transport> liveEdges = new HashSet<>();
        liveEdges.add(new Transport(start, landing, "ship", TransportType.SHIP, false, 10));
        Map<WorldPoint, Set<Transport>> liveTransports = new HashMap<>();
        liveTransports.put(start, liveEdges);
        List<WorldPoint> anchors = List.of(start, landing, nextWalkingTile);

        List<?> snapshot = visualizationTiles(anchors, liveTransports);
        assertSame("both overlays must reuse the shared immutable visualization snapshot",
                snapshot, visualizationTiles(anchors, liveTransports));
        liveEdges.clear();

        assertSame("rendering must not revisit a live mutable transport edge set", snapshot,
                visualizationTiles(anchors, liveTransports));
        assertEquals("the frozen ship edge must remain a boundary in the cached visualization", 4, snapshot.size());
    }

    @Test
    public void travelledAndRemainingCountersAreZeroBasedAtBothEnds() throws Exception
    {
        assertEquals(0, visualizationCounter(TileCounter.TRAVELLED, 0, 5));
        assertEquals(4, visualizationCounter(TileCounter.TRAVELLED, 4, 5));
        assertEquals(4, visualizationCounter(TileCounter.REMAINING, 0, 5));
        assertEquals(0, visualizationCounter(TileCounter.REMAINING, 4, 5));
    }

    @Test
    public void lineCountersUseExpandedDistanceForSparseWalkableAnchors() throws Exception
    {
        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3204, 3202, 0);
        Object snapshot = visualizationSnapshot(List.of(start, end), Map.of());

        assertEquals(0, visualizationCounter(TileCounter.TRAVELLED, displayIndexForAnchor(snapshot, 0), snapshotSize(snapshot)));
        assertEquals(4, visualizationCounter(TileCounter.TRAVELLED, displayIndexForAnchor(snapshot, 1), snapshotSize(snapshot)));
        assertEquals(4, visualizationCounter(TileCounter.REMAINING, displayIndexForAnchor(snapshot, 0), snapshotSize(snapshot)));
        assertEquals(0, visualizationCounter(TileCounter.REMAINING, displayIndexForAnchor(snapshot, 1), snapshotSize(snapshot)));
    }

    @Test
    public void lineRendererReadsExpandedDisplayIndexes() throws Exception
    {
        String snapshotOwner = Type.getInternalName(PathVisualization.VisualizationSnapshot.class);
        AtomicBoolean displayIndexRead = new AtomicBoolean();
        new ClassReader(PathTileOverlay.class.getResourceAsStream("PathTileOverlay.class")).accept(
                new ClassVisitor(Opcodes.ASM9)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions)
                    {
                        if (!"render".equals(name))
                        {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name,
                                                        String descriptor, boolean isInterface)
                            {
                                if (snapshotOwner.equals(owner) && "displayIndexForAnchor".equals(name))
                                {
                                    displayIndexRead.set(true);
                                }
                            }
                        };
                    }
                }, 0);
        assertTrue("line rendering must count expanded display tiles, not sparse anchors", displayIndexRead.get());
    }

    @Test
    public void producerPublishesImmutableSnapshotsDuringConcurrentTeleportRefresh() throws Exception
    {
        WorldPoint start = new WorldPoint(3029, 3217, 0);
        WorldPoint landing = new WorldPoint(2956, 3143, 0);
        Transport teleport = new Transport(landing, "teleport", TransportType.TELEPORTATION_SPELL,
                false, 30, Set.of());
        PathfinderConfig config = new PathfinderConfig(null, Map.of(), List.of(), null, null);
        config.setUsableTeleports(Set.of(teleport));
        int packedStart = WorldPointUtil.packWorldPoint(start);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> refreshes = executor.submit(() ->
        {
            for (int i = 0; i < 250; i++)
            {
                config.refreshTeleports(packedStart, 0);
            }
        });
        try
        {
            while (!refreshes.isDone())
            {
                for (Set<Transport> edges : config.getTransportVisualizationSnapshot().values())
                {
                    assertEquals(Set.of(teleport), edges);
                }
            }
            refreshes.get();
            Set<Transport> frozen = config.getTransportVisualizationSnapshot().get(start);
            assertEquals(Set.of(teleport), frozen);
            try
            {
                frozen.clear();
                fail("producer snapshot must not retain a mutable transport edge set");
            }
            catch (UnsupportedOperationException expected)
            {
                // Expected: rendering only receives the producer-published immutable snapshot.
            }
        }
        finally
        {
            executor.shutdownNow();
        }
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

    private static int visualizationCounter(TileCounter counterMode, int displayIndex, int visualizationSize)
            throws Exception
    {
        Method counter = PathTileOverlay.class.getDeclaredMethod(
                "visualizationCounter", TileCounter.class, int.class, int.class);
        counter.setAccessible(true);
        return (int) counter.invoke(null, counterMode, displayIndex, visualizationSize);
    }

    private static Object visualizationSnapshot(List<WorldPoint> anchors, Map<WorldPoint, Set<Transport>> transports)
            throws Exception
    {
        Method snapshot = PathVisualization.class.getDeclaredMethod("snapshot", List.class, Map.class);
        snapshot.setAccessible(true);
        return snapshot.invoke(null, anchors, transports);
    }

    private static int displayIndexForAnchor(Object snapshot, int anchorIndex)
            throws Exception
    {
        Method displayIndex = snapshot.getClass().getDeclaredMethod("displayIndexForAnchor", int.class);
        displayIndex.setAccessible(true);
        return (int) displayIndex.invoke(snapshot, anchorIndex);
    }

    private static int snapshotSize(Object snapshot) throws Exception
    {
        Method size = snapshot.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (int) size.invoke(snapshot);
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
