package net.runelite.client.plugins.microbot.util.walker.door.model;

/**
 * Enumeration of door interaction resolution states during path traversal.
 * Indicates whether a door interaction succeeded, is still awaiting completion,
 * or failed for a specific reason.
 */
public enum DoorResolution {
    /** The door interaction completed successfully and the path is now passable. */
    RESOLVED,

    /** The door interaction is in progress and awaiting completion. */
    AWAITING,

    /** The door interaction failed due to timeout. */
    FAILED_TIMEOUT,

    /** The door interaction was cancelled before completion. */
    FAILED_CANCELLED,

    /** The door interaction failed due to invalid state or preconditions. */
    FAILED_INVALID
}
