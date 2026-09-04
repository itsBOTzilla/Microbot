package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cache-only expansion of smoothed path anchors for overlay rendering. */
final class PathVisualization
{
    private static volatile CachedVisualization cachedVisualization;

    private PathVisualization()
    {
    }

    static List<VisualizationTile> expand(
            List<WorldPoint> anchors,
            Map<WorldPoint, Set<Transport>> transports)
    {
        if (anchors == null || anchors.isEmpty())
        {
            return Collections.emptyList();
        }

        CachedVisualization cached = cachedVisualization;
        if (cached != null && cached.matches(anchors, transports))
        {
            return cached.tiles;
        }

        synchronized (PathVisualization.class)
        {
            cached = cachedVisualization;
            if (cached != null && cached.matches(anchors, transports))
            {
                return cached.tiles;
            }

            Map<WorldPoint, Set<WorldPoint>> transportEdges = snapshotTransportEdges(anchors, transports);
            List<VisualizationTile> tiles = expandUncached(anchors, transportEdges);
            cachedVisualization = new CachedVisualization(anchors, transports, transportEdges, tiles);
            return tiles;
        }
    }

    private static List<VisualizationTile> expandUncached(
            List<WorldPoint> anchors,
            Map<WorldPoint, Set<WorldPoint>> transportEdges)
    {
        List<VisualizationTile> tiles = new ArrayList<>();
        tiles.add(new VisualizationTile(anchors.get(0), 0, 0));
        for (int anchorIndex = 1; anchorIndex < anchors.size(); anchorIndex++)
        {
            WorldPoint from = anchors.get(anchorIndex - 1);
            WorldPoint to = anchors.get(anchorIndex);
            if (shouldRasterizeFrozenWalkingSegment(from, to, transportEdges))
            {
                List<WorldPoint> segment = rasterizeWalkingSegment(from, to);
                for (int i = 1; i + 1 < segment.size(); i++)
                {
                    tiles.add(new VisualizationTile(segment.get(i), -1, tiles.size()));
                }
            }
            tiles.add(new VisualizationTile(to, anchorIndex, tiles.size()));
        }
        return List.copyOf(tiles);
    }

    static boolean shouldRasterizeWalkingSegment(
            WorldPoint from,
            WorldPoint to,
            Map<WorldPoint, Set<Transport>> transports)
    {
        return shouldRasterizeFrozenWalkingSegment(from, to,
                snapshotTransportEdges(List.of(from), transports));
    }

    private static boolean shouldRasterizeFrozenWalkingSegment(
            WorldPoint from,
            WorldPoint to,
            Map<WorldPoint, Set<WorldPoint>> transportEdges)
    {
        if (from.getPlane() != to.getPlane())
        {
            return false;
        }
        return !transportEdges.getOrDefault(from, Collections.emptySet()).contains(to);
    }

    private static Map<WorldPoint, Set<WorldPoint>> snapshotTransportEdges(
            List<WorldPoint> anchors,
            Map<WorldPoint, Set<Transport>> transports)
    {
        if (transports == null || transports.isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<WorldPoint, Set<WorldPoint>> snapshot = new HashMap<>();
        for (WorldPoint anchor : anchors)
        {
            Set<Transport> liveEdges = transports.get(anchor);
            if (liveEdges == null || liveEdges.isEmpty())
            {
                continue;
            }
            Set<WorldPoint> destinations = new HashSet<>();
            for (Transport transport : liveEdges)
            {
                if (transport != null && transport.getDestination() != null)
                {
                    destinations.add(transport.getDestination());
                }
            }
            if (!destinations.isEmpty())
            {
                snapshot.put(anchor, Set.copyOf(destinations));
            }
        }
        return snapshot.isEmpty() ? Collections.emptyMap() : Map.copyOf(snapshot);
    }

    /**
     * Replays the same integer step sequence whose legality PathSmoother checked before retaining
     * the two anchors. This is deliberately not generic line drawing: a different rasterization
     * could display tiles across an edge that the smoother never validated.
     */
    static List<WorldPoint> rasterizeWalkingSegment(WorldPoint start, WorldPoint end)
    {
        if (start.getPlane() != end.getPlane())
        {
            return List.of(start, end);
        }

        int x = start.getX();
        int y = start.getY();
        List<WorldPoint> points = new ArrayList<>(
                Math.max(Math.abs(end.getX() - x), Math.abs(end.getY() - y)) + 1);
        points.add(start);
        while (x != end.getX() || y != end.getY())
        {
            x += Integer.signum(end.getX() - x);
            y += Integer.signum(end.getY() - y);
            points.add(new WorldPoint(x, y, start.getPlane()));
        }
        return List.copyOf(points);
    }

    static final class VisualizationTile
    {
        private final WorldPoint point;
        private final int anchorIndex;
        private final int displayIndex;

        private VisualizationTile(WorldPoint point, int anchorIndex, int displayIndex)
        {
            this.point = point;
            this.anchorIndex = anchorIndex;
            this.displayIndex = displayIndex;
        }

        WorldPoint point()
        {
            return point;
        }

        int anchorIndex()
        {
            return anchorIndex;
        }

        int displayIndex()
        {
            return displayIndex;
        }
    }

    private static final class CachedVisualization
    {
        private final List<WorldPoint> anchors;
        private final Map<WorldPoint, Set<Transport>> transports;
        @SuppressWarnings("unused")
        private final Map<WorldPoint, Set<WorldPoint>> transportEdges;
        private final List<VisualizationTile> tiles;

        private CachedVisualization(List<WorldPoint> anchors, Map<WorldPoint, Set<Transport>> transports,
                                    Map<WorldPoint, Set<WorldPoint>> transportEdges,
                                    List<VisualizationTile> tiles)
        {
            this.anchors = anchors;
            this.transports = transports;
            this.transportEdges = transportEdges;
            this.tiles = tiles;
        }

        private boolean matches(List<WorldPoint> candidateAnchors, Map<WorldPoint, Set<Transport>> candidateTransports)
        {
            return anchors == candidateAnchors && transports == candidateTransports;
        }
    }
}
