package net.runelite.client.plugins.microbot.util.walker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RuneLiteWebWalkRuntimeTest
{
    @Test
    public void selectsFurthestReachableForwardTileInsideMinimapRange()
    {
        List<WorldPoint> path = line(0, 16);
        Set<WorldPoint> reachable = new HashSet<>(line(0, 12));

        RuneLiteWebWalkRuntime.ForwardCandidate candidate =
                RuneLiteWebWalkRuntime.selectForwardCandidate(
                        path, point(0), reachable, 12, index -> false);

        assertEquals(point(11), candidate.getTarget());
        assertEquals(11, candidate.getPathIndex());
    }

    @Test
    public void neverSelectsPastExactTransportEdge()
    {
        List<WorldPoint> path = line(0, 16);

        RuneLiteWebWalkRuntime.ForwardCandidate candidate =
                RuneLiteWebWalkRuntime.selectForwardCandidate(
                        path, point(0), new HashSet<>(path), 12, index -> index == 6);

        assertEquals(point(6), candidate.getTarget());
        assertEquals(6, candidate.getPathIndex());
    }

    @Test
    public void blockedForwardPathDoesNotRedispatchCurrentTile()
    {
        List<WorldPoint> path = line(0, 5);
        Set<WorldPoint> reachable = Set.of(point(0));

        RuneLiteWebWalkRuntime.ForwardCandidate candidate =
                RuneLiteWebWalkRuntime.selectForwardCandidate(
                        path, point(0), reachable, 12, index -> false);

        assertNull(candidate);
    }

    private static List<WorldPoint> line(int fromInclusive, int toExclusive)
    {
        List<WorldPoint> points = new ArrayList<>();
        for (int x = fromInclusive; x < toExclusive; x++)
        {
            points.add(point(x));
        }
        return points;
    }

    private static WorldPoint point(int x)
    {
        return new WorldPoint(3200 + x, 3200, 0);
    }
}
