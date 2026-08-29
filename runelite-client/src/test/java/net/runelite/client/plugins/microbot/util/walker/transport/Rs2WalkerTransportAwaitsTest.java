package net.runelite.client.plugins.microbot.util.walker.transport;

import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Rs2WalkerTransportAwaitsTest
{
    private static final WorldPoint BEFORE = new WorldPoint(3200, 3200, 0);
    private static final WorldPoint DESTINATION = new WorldPoint(3203, 3200, 0);
    private static final WorldPoint TARGET = new WorldPoint(3210, 3200, 0);

    @Test
    public void openDialogueCountsAsTransportProgressWithoutMovement()
    {
        assertTrue(Rs2WalkerTransportAwaits.hasTransportProgress(
                BEFORE, BEFORE, DESTINATION, TARGET, false, true));
    }

    @Test
    public void preexistingDialogueDoesNotCountAsTransportProgress()
    {
        assertFalse(Rs2WalkerTransportAwaits.hasTransportProgress(
                BEFORE, BEFORE, DESTINATION, TARGET, true, true));
    }

    @Test
    public void noDialogueOrMovementDoesNotCountAsTransportProgress()
    {
        assertFalse(Rs2WalkerTransportAwaits.hasTransportProgress(
                BEFORE, BEFORE, DESTINATION, TARGET, false, false));
    }

    @Test
    public void movementStillCountsAsTransportProgress()
    {
        assertTrue(Rs2WalkerTransportAwaits.hasTransportProgress(
                BEFORE, DESTINATION, DESTINATION, TARGET, false, false));
    }

    @Test
    public void progressObservedDuringWaitIsNotLostWhenDialogueCloses()
    {
        assertTrue(Rs2WalkerTransportAwaits.resolveTransportProgress(
                true, BEFORE, BEFORE, DESTINATION, TARGET, false, false));
    }

    @Test
    public void newlyOpenedDialogueCountsWhenPositionSnapshotIsMissing()
    {
        assertTrue(Rs2WalkerTransportAwaits.hasProgressWithoutPositionSnapshot(false, true));
    }

    @Test
    public void preexistingDialogueDoesNotCountWhenPositionSnapshotIsMissing()
    {
        assertFalse(Rs2WalkerTransportAwaits.hasProgressWithoutPositionSnapshot(true, true));
    }

    @Test
    public void delayedDialogueOpeningCountsWhenPositionSnapshotIsMissing()
    {
        AtomicInteger samples = new AtomicInteger();

        assertTrue(Rs2WalkerTransportAwaits.waitForProgressWithoutPositionSnapshot(
                false,
                () -> samples.incrementAndGet() >= 2,
                condition -> !condition.getAsBoolean() && condition.getAsBoolean()));
        assertTrue("the dialogue must be polled rather than sampled once", samples.get() >= 2);
    }
}
