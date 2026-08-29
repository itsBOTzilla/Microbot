package net.runelite.client.plugins.microbot.util.walker.door;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.walker.door.model.AwaitTicket;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Rs2WalkerAwaitsTest {
    @Test
    public void shouldAcceptIdleDoorAwait_acceptsStationaryPastMinimum() {
        assertFalse(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(false, false, 1200L, false));
        assertTrue(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(false, false, 1201L, true));
    }

    /**
     * The old implementation ended in {@code return edgeResolved}, which made this branch dead code:
     * the traversal wait returns the moment the edge resolves, so it only ever reached here with
     * edgeResolved == false. A stalled interaction burned the full 2200ms budget instead of releasing.
     */
    @Test
    public void shouldAcceptIdleDoorAwait_acceptsUnresolvedEdgeWhenStalled() {
        assertTrue(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(false, false, 1500L, false));
    }

    @Test
    public void shouldAcceptIdleDoorAwait_rejectsMovingOrAnimating() {
        assertFalse(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(true, false, 5000L, true));
        assertFalse(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(false, true, 5000L, true));
    }

    @Test
    public void shouldAcceptIdleDoorAwait_rejectsBeforeMinimumElapsed() {
        assertFalse(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(false, false, 1200L, true));
        assertFalse(Rs2WalkerAwaits.shouldAcceptIdleDoorAwait(false, false, 800L, true));
    }

    @Test
    public void doorApproachInFlight_acceptsMovementTowardNearSide() {
        assertTrue(Rs2WalkerAwaits.isDoorApproachInFlight(
                new WorldPoint(3170, 3300, 0),
                new WorldPoint(3169, 3301, 0),
                new WorldPoint(3167, 3302, 0),
                new WorldPoint(3166, 3303, 0),
                true,
                700L,
                4000L));
    }

    @Test
    public void doorApproachInFlight_rejectsMovementAwayFromDoor() {
        assertFalse(Rs2WalkerAwaits.isDoorApproachInFlight(
                new WorldPoint(3169, 3301, 0),
                new WorldPoint(3170, 3300, 0),
                new WorldPoint(3167, 3302, 0),
                new WorldPoint(3166, 3303, 0),
                true,
                700L,
                4000L));
    }

    @Test
    public void doorApproachInFlight_rejectsExpiredStationaryOrCrossedMovement() {
        WorldPoint before = new WorldPoint(3169, 3301, 0);
        WorldPoint nearSide = new WorldPoint(3167, 3302, 0);
        WorldPoint farSide = new WorldPoint(3166, 3303, 0);

        assertFalse(Rs2WalkerAwaits.isDoorApproachInFlight(
                before, new WorldPoint(3168, 3302, 0), nearSide, farSide,
                false, 700L, 4000L));
        assertFalse(Rs2WalkerAwaits.isDoorApproachInFlight(
                before, new WorldPoint(3168, 3302, 0), nearSide, farSide,
                true, 4001L, 4000L));
        assertFalse(Rs2WalkerAwaits.isDoorApproachInFlight(
                before, farSide, nearSide, farSide,
                true, 700L, 4000L));
    }

    @Test
    public void beginTicketPreservesPreDispatchPositionWhenMovementStartsImmediately() {
        WorldPoint beforeDispatch = new WorldPoint(3170, 3300, 0);
        WorldPoint afterDispatch = new WorldPoint(3169, 3301, 0);
        WorldPoint nearSide = new WorldPoint(3167, 3302, 0);
        WorldPoint farSide = new WorldPoint(3166, 3303, 0);

        AwaitTicket ticket = Rs2WalkerAwaits.beginTicket(beforeDispatch);

        assertEquals(beforeDispatch, ticket.beforePosition());
        assertTrue(Rs2WalkerAwaits.isDoorApproachInFlight(
                ticket.beforePosition(), afterDispatch, nearSide, farSide,
                true, 700L, 4000L));
    }
}
