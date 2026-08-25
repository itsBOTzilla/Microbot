package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.walker.state.WalkerRouteState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DoorApproachOwnershipTest {
    @Test
    public void failedInteractionDoesNotClaimApproachOwnership() {
        WalkerRouteState state = new WalkerRouteState();
        WorldPoint player = new WorldPoint(3170, 3300, 0);
        WorldPoint from = new WorldPoint(3167, 3302, 0);
        WorldPoint to = new WorldPoint(3166, 3303, 0);

        Rs2Walker.recordDoorApproachOwnership(state, false, player, from, to, 1000L);

        assertNull(state.lastDoorAttemptPlayerPosition);
        assertNull(state.lastDoorAttemptFrom);
        assertNull(state.lastDoorAttemptTo);
        assertEquals(0L, state.lastDoorAttemptAtMs);
    }

    @Test
    public void successfulInteractionClaimsApproachOwnership() {
        WalkerRouteState state = new WalkerRouteState();
        WorldPoint player = new WorldPoint(3170, 3300, 0);
        WorldPoint from = new WorldPoint(3167, 3302, 0);
        WorldPoint to = new WorldPoint(3166, 3303, 0);

        Rs2Walker.recordDoorApproachOwnership(state, true, player, from, to, 1000L);

        assertEquals(player, state.lastDoorAttemptPlayerPosition);
        assertEquals(from, state.lastDoorAttemptFrom);
        assertEquals(to, state.lastDoorAttemptTo);
        assertEquals(1000L, state.lastDoorAttemptAtMs);
    }
}
