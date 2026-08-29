package net.runelite.client.plugins.microbot.util.walker.door.model;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable bidirectional edge between two world points, representing a door passage.
 * Generates normalized keys for use in throttling maps, where the same physical door
 * edge should produce the same key regardless of traversal direction.
 */
public final class DoorEdge {
    private final WorldPoint from;
    private final WorldPoint to;

    /**
     * Creates a new door edge between two world points.
     *
     * @param from the starting point of the edge
     * @param to the ending point of the edge
     */
    public DoorEdge(WorldPoint from, WorldPoint to) {
        this.from = from;
        this.to = to;
    }

    /**
     * Generates a normalized key for this edge. The key is direction-independent:
     * an edge from A to B produces the same key as an edge from B to A.
     *
     * @return a normalized string key representing this bidirectional edge
     */
    public String normalizedKey() {
        String a = compact(from);
        String b = compact(to);
        return a.compareTo(b) <= 0 ? a + "<->" + b : b + "<->" + a;
    }

    private static String compact(WorldPoint wp) {
        if (wp == null) {
            return "?";
        }
        return wp.getX() + "," + wp.getY() + ",p" + wp.getPlane();
    }
}
