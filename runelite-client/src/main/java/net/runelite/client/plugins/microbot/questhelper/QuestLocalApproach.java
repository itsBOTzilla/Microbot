package net.runelite.client.plugins.microbot.questhelper;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import net.runelite.api.CollisionData;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/** Small collision search in physical scene coordinates, including inside instances. */
final class QuestLocalApproach
{
    private QuestLocalApproach()
    {
    }

    /** Must be called on the client thread with a physical, non-template player position. */
    static Set<WorldPoint> reachable(WorldView worldView, WorldPoint scenePlayer, int maxSteps)
    {
        if (worldView == null || scenePlayer == null || maxSteps < 0
            || scenePlayer.getPlane() != worldView.getPlane())
        {
            return Collections.emptySet();
        }
        CollisionData[] maps = worldView.getCollisionMaps();
        int plane = scenePlayer.getPlane();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
        {
            return Collections.emptySet();
        }
        int[][] flags = maps[plane].getFlags();
        int baseX = worldView.getBaseX();
        int baseY = worldView.getBaseY();
        if (!inScene(worldView, flags, scenePlayer.getX() - baseX, scenePlayer.getY() - baseY))
        {
            return Collections.emptySet();
        }
        Set<WorldPoint> visited = new HashSet<>();
        Queue<WorldPoint> frontier = new ArrayDeque<>();
        visited.add(scenePlayer);
        frontier.add(scenePlayer);
        for (int depth = 0; depth < maxSteps && !frontier.isEmpty(); depth++)
        {
            int layerSize = frontier.size();
            for (int index = 0; index < layerSize; index++)
            {
                WorldPoint from = frontier.remove();
                WorldArea tile = new WorldArea(from, 1, 1);
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dy = -1; dy <= 1; dy++)
                    {
                        if (dx == 0 && dy == 0)
                        {
                            continue;
                        }
                        WorldPoint to = new WorldPoint(from.getX() + dx, from.getY() + dy, plane);
                        int x = to.getX() - baseX;
                        int y = to.getY() - baseY;
                        // WorldArea also reads both orthogonal cells for diagonal moves.
                        if (visited.contains(to) || !inScene(worldView, flags, x, y)
                            || !inScene(worldView, flags, x - dx, y)
                            || !inScene(worldView, flags, x, y - dy))
                        {
                            continue;
                        }
                        if (tile.canTravelInDirection(worldView, dx, dy))
                        {
                            visited.add(to);
                            frontier.add(to);
                        }
                    }
                }
            }
        }
        return visited;
    }

    /** Only cardinal perimeter tiles count; an occupied footprint or diagonal corner does not. */
    static boolean adjacentReachable(WorldArea objectArea, Set<WorldPoint> reachable)
    {
        if (objectArea == null || reachable == null)
        {
            return false;
        }
        int left = objectArea.getX();
        int bottom = objectArea.getY();
        int right = left + objectArea.getWidth();
        int top = bottom + objectArea.getHeight();
        for (WorldPoint tile : reachable)
        {
            if (tile.getPlane() == objectArea.getPlane()
                && (((tile.getX() == left - 1 || tile.getX() == right)
                    && tile.getY() >= bottom && tile.getY() < top)
                || ((tile.getY() == bottom - 1 || tile.getY() == top)
                    && tile.getX() >= left && tile.getX() < right)))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean inScene(WorldView worldView, int[][] flags, int x, int y)
    {
        return flags != null && x >= 0 && y >= 0 && x < worldView.getSizeX() && y < worldView.getSizeY()
            && x < flags.length && flags[x] != null && y < flags[x].length;
    }
}
