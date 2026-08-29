package net.runelite.client.plugins.microbot.util.walker.door.model;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable ticket representing a pending door interaction await state.
 * Captures the timestamp when the await began and the player's position before the interaction,
 * used to detect timeout conditions and verify successful transitions.
 */
public final class AwaitTicket {
    private final long startedAtMs;
    private final WorldPoint beforePosition;

    /**
     * Creates a new await ticket.
     *
     * @param startedAtMs the timestamp (in milliseconds since epoch) when the await began
     * @param beforePosition the player's world location before the door interaction
     */
    public AwaitTicket(long startedAtMs, WorldPoint beforePosition) {
        this.startedAtMs = startedAtMs;
        this.beforePosition = beforePosition;
    }

    /**
     * Returns the timestamp when this await began.
     *
     * @return the start timestamp in milliseconds since epoch
     */
    public long startedAtMs() {
        return startedAtMs;
    }

    /**
     * Returns the player's position before the door interaction.
     *
     * @return the world location before the interaction
     */
    public WorldPoint beforePosition() {
        return beforePosition;
    }
}
