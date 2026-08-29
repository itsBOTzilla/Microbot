package net.runelite.client.plugins.microbot.util.walker.door.model;

import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable candidate representing a door/gate object identified for potential interaction
 * during path traversal. Contains the door object, probe point, path segment endpoints,
 * intended action, and detection mode for debugging and decision-making.
 */
public final class DoorCandidate {
    private final TileObject object;
    private final WorldPoint probe;
    private final WorldPoint from;
    private final WorldPoint to;
    private final String action;
    private final String mode;

    /**
     * Creates a new door candidate.
     *
     * @param object the door/gate tile object
     * @param probe the probe point used for door detection
     * @param from the starting point of the path segment
     * @param to the ending point of the path segment
     * @param action the menu action to use for interacting with the door
     * @param mode the detection mode (e.g., "ahead", "blocking") for debugging
     */
    public DoorCandidate(TileObject object, WorldPoint probe, WorldPoint from, WorldPoint to, String action, String mode) {
        this.object = object;
        this.probe = probe;
        this.from = from;
        this.to = to;
        this.action = action;
        this.mode = mode;
    }

    /**
     * Returns the door/gate tile object.
     *
     * @return the tile object
     */
    public TileObject object() {
        return object;
    }

    /**
     * Returns the probe point used for door detection.
     *
     * @return the probe world location
     */
    public WorldPoint probe() {
        return probe;
    }

    /**
     * Returns the starting point of the path segment.
     *
     * @return the from world location
     */
    public WorldPoint from() {
        return from;
    }

    /**
     * Returns the ending point of the path segment.
     *
     * @return the to world location
     */
    public WorldPoint to() {
        return to;
    }

    /**
     * Returns the menu action to use for interacting with the door.
     *
     * @return the action string (e.g., "Open", "Walk-through")
     */
    public String action() {
        return action;
    }

    /**
     * Returns the detection mode for debugging purposes.
     *
     * @return the mode string describing how this candidate was detected
     */
    public String mode() {
        return mode;
    }
}
