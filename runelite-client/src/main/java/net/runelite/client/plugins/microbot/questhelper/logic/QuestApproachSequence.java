package net.runelite.client.plugins.microbot.questhelper.logic;

import java.util.List;
import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

/** Advances an explicit quest route without returning to an earlier waypoint. */
final class QuestApproachSequence
{
    private Object routeKey;
    private int waypointIndex;

    synchronized WorldPoint next(Object key, List<WorldPoint> waypoints, WorldPoint player, int radius)
    {
        if (key == null || waypoints == null || waypoints.isEmpty() || player == null)
        {
            reset();
            return null;
        }

        if (!Objects.equals(routeKey, key))
        {
            routeKey = key;
            waypointIndex = 0;
            for (int i = waypoints.size() - 1; i >= 0; i--)
            {
                if (isAt(player, waypoints.get(i), radius))
                {
                    waypointIndex = i + 1;
                    break;
                }
            }
        }

        while (waypointIndex < waypoints.size()
                && isAt(player, waypoints.get(waypointIndex), radius))
        {
            waypointIndex++;
        }
        return waypointIndex < waypoints.size() ? waypoints.get(waypointIndex) : null;
    }

    synchronized void reset()
    {
        routeKey = null;
        waypointIndex = 0;
    }

    private static boolean isAt(WorldPoint player, WorldPoint waypoint, int radius)
    {
        return waypoint != null && player.getPlane() == waypoint.getPlane()
                && player.distanceTo2D(waypoint) <= radius;
    }
}
