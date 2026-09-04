package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cache-only expansion of smoothed path anchors for overlay rendering. */
final class PathVisualization
{
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

        List<VisualizationTile> tiles = new ArrayList<>();
        tiles.add(new VisualizationTile(anchors.get(0), 0, 1));
        for (int anchorIndex = 1; anchorIndex < anchors.size(); anchorIndex++)
        {
            WorldPoint from = anchors.get(anchorIndex - 1);
            WorldPoint to = anchors.get(anchorIndex);
            if (shouldRasterizeWalkingSegment(from, to, transports))
            {
                List<WorldPoint> segment = rasterizeWalkingSegment(from, to);
                for (int i = 1; i + 1 < segment.size(); i++)
                {
                    tiles.add(new VisualizationTile(segment.get(i), -1, tiles.size() + 1));
                }
            }
            tiles.add(new VisualizationTile(to, anchorIndex, tiles.size() + 1));
        }
        return List.copyOf(tiles);
    }

    static boolean shouldRasterizeWalkingSegment(
            WorldPoint from,
            WorldPoint to,
            Map<WorldPoint, Set<Transport>> transports)
    {
        if (from.getPlane() != to.getPlane())
        {
            return false;
        }
        if (transports == null)
        {
            return true;
        }
        Set<Transport> fromTransports = transports.get(from);
        if (fromTransports == null)
        {
            return true;
        }
        for (Transport transport : fromTransports)
        {
            if (to.equals(transport.getDestination()))
            {
                return false;
            }
        }
        return true;
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
}
