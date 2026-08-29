package net.runelite.client.plugins.microbot.util.walker.door;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.walker.door.model.DoorEdge;

import java.util.Map;

/**
 * Stateless throttling and cooldown management for door interactions during path traversal.
 * Tracks per-edge door attempts and stationary door open events to prevent redundant interactions
 * and respect game timing constraints. Extracted from {@code Rs2Walker} — pure functions over
 * cooldown maps, no walker state.
 */
public final class Rs2DoorHandler {
    private Rs2DoorHandler() {
    }

    /**
     * Generates a unique key for a door interaction attempt based on the door location and the
     * path segment (from/to). Uses normalized edge keys when both endpoints are available.
     *
     * @param doorTile the door's world location
     * @param fromWp the starting point of the path segment
     * @param toWp the ending point of the path segment
     * @return a unique string key representing this door attempt
     */
    public static String doorAttemptKey(WorldPoint doorTile, WorldPoint fromWp, WorldPoint toWp) {
        if (fromWp != null && toWp != null) {
            return new DoorEdge(fromWp, toWp).normalizedKey();
        }
        return compactWorldPoint(doorTile) + "|" + compactWorldPoint(fromWp) + "->" + compactWorldPoint(toWp);
    }

    /**
     * Checks whether a door interaction attempt should be throttled based on recent attempts.
     * Cleans up expired entries from the cooldown map as a side effect.
     *
     * @param recentDoorAttemptByEdge map tracking recent door attempt timestamps by edge key
     * @param cooldownMs the cooldown duration in milliseconds
     * @param doorTile the door's world location
     * @param fromWp the starting point of the path segment
     * @param toWp the ending point of the path segment
     * @return true if the door attempt should be throttled, false if it can proceed
     */
    public static boolean shouldThrottleDoorAttempt(Map<String, Long> recentDoorAttemptByEdge,
                                                    long cooldownMs,
                                                    WorldPoint doorTile,
                                                    WorldPoint fromWp,
                                                    WorldPoint toWp) {
        String key = doorAttemptKey(doorTile, fromWp, toWp);
        long now = System.currentTimeMillis();
        recentDoorAttemptByEdge.entrySet().removeIf(entry -> now - entry.getValue() > cooldownMs);
        Long last = recentDoorAttemptByEdge.get(key);
        return last != null && now - last < cooldownMs;
    }

    /**
     * Records a door interaction attempt with the current timestamp in the cooldown map.
     *
     * @param recentDoorAttemptByEdge map tracking recent door attempt timestamps by edge key
     * @param doorTile the door's world location
     * @param fromWp the starting point of the path segment
     * @param toWp the ending point of the path segment
     */
    public static void markDoorAttempt(Map<String, Long> recentDoorAttemptByEdge,
                                       WorldPoint doorTile,
                                       WorldPoint fromWp,
                                       WorldPoint toWp) {
        recentDoorAttemptByEdge.put(doorAttemptKey(doorTile, fromWp, toWp), System.currentTimeMillis());
    }

    /**
     * Records that a stationary door at the given location was opened with the current timestamp.
     *
     * @param recentlyOpenedStationaryDoors map tracking recently opened stationary doors by location
     * @param doorTile the door's world location, or null to skip recording
     */
    public static void markStationaryDoorOpened(Map<WorldPoint, Long> recentlyOpenedStationaryDoors, WorldPoint doorTile) {
        if (doorTile != null) {
            recentlyOpenedStationaryDoors.put(doorTile, System.currentTimeMillis());
        }
    }

    /**
     * Checks whether a stationary door was recently opened near the given path segment.
     * Cleans up expired entries from the tracking map as a side effect.
     *
     * @param recentlyOpenedStationaryDoors map tracking recently opened stationary doors by location
     * @param suppressMs the time window in milliseconds to consider a door "recently opened"
     * @param fromWp the starting point of the path segment
     * @param toWp the ending point of the path segment
     * @return true if a recently opened door is within 2 tiles of either segment endpoint
     */
    public static boolean recentlyOpenedStationaryDoorOnSegment(Map<WorldPoint, Long> recentlyOpenedStationaryDoors,
                                                                long suppressMs,
                                                                WorldPoint fromWp,
                                                                WorldPoint toWp) {
        if (fromWp == null || toWp == null) {
            return false;
        }
        final int segmentDoorSuppressDist = 2;
        long now = System.currentTimeMillis();
        recentlyOpenedStationaryDoors.entrySet().removeIf(entry -> now - entry.getValue() > suppressMs);
        return recentlyOpenedStationaryDoors.keySet().stream()
                .anyMatch(door -> door != null
                        && door.getPlane() == fromWp.getPlane()
                        && (door.distanceTo2D(fromWp) <= segmentDoorSuppressDist || door.distanceTo2D(toWp) <= segmentDoorSuppressDist));
    }

    /**
     * Checks whether the global door interaction cooldown is still active.
     *
     * @param nextDoorInteractionAllowedAtMs the earliest timestamp when the next interaction is allowed
     * @return true if the cooldown is still active and interactions should be throttled
     */
    public static boolean shouldThrottleGlobalDoorInteraction(long nextDoorInteractionAllowedAtMs) {
        return System.currentTimeMillis() < nextDoorInteractionAllowedAtMs;
    }

    /**
     * Computes the timestamp when the next global door interaction should be allowed.
     *
     * @param cooldownMs the cooldown duration in milliseconds
     * @return the timestamp (in milliseconds since epoch) when the next interaction is allowed
     */
    public static long markGlobalDoorInteractionCooldown(long cooldownMs) {
        return System.currentTimeMillis() + cooldownMs;
    }

    private static String compactWorldPoint(WorldPoint wp) {
        if (wp == null) {
            return "?";
        }
        return wp.getX() + "," + wp.getY() + ",p" + wp.getPlane();
    }
}
