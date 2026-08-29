package net.runelite.client.plugins.microbot.util.walker.door;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for resolving door interactions ahead of the player during path traversal.
 * Probe-point generation is stateless, while reachability checks consult live collision and
 * line-of-sight state and marshal client access through the client thread. This class stores no
 * walker route state.
 */
public final class Rs2DoorAheadResolver {
    private Rs2DoorAheadResolver() {
    }

    /**
     * Generates a list of probe points for door detection along a path segment.
     * For diagonal segments, includes intermediate corner points to handle doors on either axis.
     *
     * @param fromWp the starting point of the path segment
     * @param toWp the ending point of the path segment
     * @param doorWp the door's world location
     * @return a list of probe points including the door location and diagonal corners if applicable
     */
    public static List<WorldPoint> buildSegmentProbes(WorldPoint fromWp, WorldPoint toWp, WorldPoint doorWp) {
        if (fromWp == null || toWp == null || doorWp == null) {
            return List.of();
        }
        List<WorldPoint> probes = new ArrayList<>();
        probes.add(doorWp);

        boolean diagonal = Math.abs(fromWp.getX() - toWp.getX()) > 0
                && Math.abs(fromWp.getY() - toWp.getY()) > 0;
        if (diagonal) {
            probes.add(new WorldPoint(toWp.getX(), fromWp.getY(), doorWp.getPlane()));
            probes.add(new WorldPoint(fromWp.getX(), toWp.getY(), doorWp.getPlane()));
        }
        return probes;
    }

    /**
     * Checks whether a path edge between two points is blocked by collision or line-of-sight obstacles.
     *
     * @param from the starting point of the edge
     * @param to the ending point of the edge
     * @return true if the edge is blocked (unreachable or no line of sight), false otherwise
     */
    public static boolean isPathEdgeBlocked(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) {
            return false;
        }
        return !Rs2Tile.isTileReachable(to) || !hasLineOfSightBetween(from, to);
    }

    private static boolean hasLineOfSightBetween(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) {
            return false;
        }
        WorldPoint from = a;
        WorldPoint to = b;
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
                from.toWorldArea().hasLineOfSightTo(
                        Microbot.getClient().getTopLevelWorldView(),
                        to.toWorldArea()))
                .orElse(false);
    }
}
