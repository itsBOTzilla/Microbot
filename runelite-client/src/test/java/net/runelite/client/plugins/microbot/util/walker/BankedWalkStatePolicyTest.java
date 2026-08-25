package net.runelite.client.plugins.microbot.util.walker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankedWalkStatePolicyTest {

    @Test
    public void movingIsNotATerminalDirectWalkFailure() {
        assertFalse(Rs2Walker.isTerminalBankedDirectWalkFailure(WalkerState.MOVING));
    }

    @Test
    public void unreachableAndExitAreTerminalDirectWalkFailures() {
        assertTrue(Rs2Walker.isTerminalBankedDirectWalkFailure(WalkerState.UNREACHABLE));
        assertTrue(Rs2Walker.isTerminalBankedDirectWalkFailure(WalkerState.EXIT));
    }
}
