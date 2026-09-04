package net.runelite.client.plugins.microbot.shortestpath;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PathMinimapOverlayTest
{
    @Test
    @SuppressWarnings("unchecked")
    public void sparseWalkableSegmentProducesContinuousMinimapTiles() throws Exception
    {
        Method rasterizer;
        try
        {
            rasterizer = PathMinimapOverlay.class.getDeclaredMethod(
                    "rasterizeWalkingSegment", WorldPoint.class, WorldPoint.class);
        }
        catch (NoSuchMethodException e)
        {
            fail("minimap overlay must render the walking segment between sparse walkable anchors");
            return;
        }
        rasterizer.setAccessible(true);

        WorldPoint start = new WorldPoint(3185, 3440, 0);
        WorldPoint end = new WorldPoint(3176, 3449, 0);
        List<WorldPoint> tiles = (List<WorldPoint>) rasterizer.invoke(null, start, end);

        assertEquals(start, tiles.get(0));
        assertEquals(end, tiles.get(tiles.size() - 1));
        assertEquals(10, tiles.size());
        for (int i = 1; i < tiles.size(); i++)
        {
            assertTrue("visual segment contains a gap at index " + i,
                    tiles.get(i - 1).distanceTo(tiles.get(i)) <= 1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rasterizationUsesTheCollisionCheckedSmootherStepOrder() throws Exception
    {
        Method rasterizer = PathMinimapOverlay.class.getDeclaredMethod(
                "rasterizeWalkingSegment", WorldPoint.class, WorldPoint.class);
        rasterizer.setAccessible(true);

        WorldPoint start = new WorldPoint(3200, 3200, 0);
        WorldPoint end = new WorldPoint(3204, 3202, 0);
        List<WorldPoint> tiles = (List<WorldPoint>) rasterizer.invoke(null, start, end);

        assertEquals(List.of(
                new WorldPoint(3200, 3200, 0),
                new WorldPoint(3201, 3201, 0),
                new WorldPoint(3202, 3202, 0),
                new WorldPoint(3203, 3202, 0),
                new WorldPoint(3204, 3202, 0)), tiles);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void crossPlaneBoundaryIsNotInventedAsWalkingTiles() throws Exception
    {
        Method rasterizer;
        try
        {
            rasterizer = PathMinimapOverlay.class.getDeclaredMethod(
                    "rasterizeWalkingSegment", WorldPoint.class, WorldPoint.class);
        }
        catch (NoSuchMethodException e)
        {
            fail("minimap overlay must distinguish walking segments from cross-plane boundaries");
            return;
        }
        rasterizer.setAccessible(true);

        WorldPoint start = new WorldPoint(2956, 3143, 1);
        WorldPoint end = new WorldPoint(2956, 3146, 0);
        List<WorldPoint> tiles = (List<WorldPoint>) rasterizer.invoke(null, start, end);

        assertEquals(List.of(start, end), tiles);
    }

    @Test
    public void transportBoundaryIsNotRasterizedAsWalking() throws Exception
    {
        Method classifier;
        try
        {
            classifier = PathMinimapOverlay.class.getDeclaredMethod(
                    "shouldRasterizeWalkingSegment", WorldPoint.class, WorldPoint.class, Map.class);
        }
        catch (NoSuchMethodException e)
        {
            fail("minimap overlay must preserve transport boundaries while filling walking segments");
            return;
        }
        classifier.setAccessible(true);

        WorldPoint origin = new WorldPoint(3029, 3217, 0);
        WorldPoint destination = new WorldPoint(2956, 3143, 1);
        Transport ship = new Transport(origin, destination, "ship", TransportType.SHIP, false, 10);

        boolean walking = (boolean) classifier.invoke(
                null, origin, destination, Map.of(origin, Set.of(ship)));

        assertFalse(walking);
    }

    @Test
    public void overlaysRenderTheWalkableAnchorsRatherThanTheRawPath() throws IOException
    {
        assertRenderUsesWalkablePath(PathMinimapOverlay.class);
        assertRenderUsesWalkablePath(PathTileOverlay.class);
    }

    private static void assertRenderUsesWalkablePath(Class<?> overlayType) throws IOException
    {
        AtomicBoolean walkablePathRead = new AtomicBoolean();
        String pathfinderOwner = Type.getInternalName(
                net.runelite.client.plugins.microbot.shortestpath.pathfinder.Pathfinder.class);
        try (InputStream stream = overlayType.getResourceAsStream(overlayType.getSimpleName() + ".class"))
        {
            assertTrue("compiled overlay must be available", stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
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
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                    boolean isInterface)
                        {
                            if (pathfinderOwner.equals(owner) && "getWalkablePath".equals(name))
                            {
                                walkablePathRead.set(true);
                            }
                        }
                    };
                }
            }, 0);
        }
        assertTrue(overlayType.getSimpleName() + " must expand sparse walkable anchors, not raw path tiles",
                walkablePathRead.get());
    }
}
