package net.runelite.client.plugins.microbot.util.grounditem;

import net.runelite.client.plugins.microbot.Microbot;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Rs2GroundItemPauseTest {

    @Test
    public void exceptionRestoresUnpausedState() {
        boolean original = Microbot.pauseAllScripts.get();
        try {
            Microbot.pauseAllScripts.set(false);

            try {
                Rs2GroundItem.runWhilePaused(() -> {
                    throw new IllegalStateException("loot failed");
                });
                fail("Expected loot failure");
            } catch (IllegalStateException expected) {
                // Expected: the assertion below verifies cleanup on the exceptional path.
            }

            assertFalse(Microbot.pauseAllScripts.get());
        } finally {
            Microbot.pauseAllScripts.set(original);
        }
    }

    @Test
    public void preExistingPauseRemainsPaused() {
        boolean original = Microbot.pauseAllScripts.get();
        try {
            Microbot.pauseAllScripts.set(true);

            assertTrue(Rs2GroundItem.runWhilePaused(() -> true));

            assertTrue(Microbot.pauseAllScripts.get());
        } finally {
            Microbot.pauseAllScripts.set(original);
        }
    }

    @Test
    public void nestedPauseRestoresUnpausedStateAfterOuterScope() {
        boolean original = Microbot.pauseAllScripts.get();
        try {
            Microbot.pauseAllScripts.set(false);

            assertTrue(Rs2GroundItem.runWhilePaused(() -> {
                assertTrue(Microbot.pauseAllScripts.get());
                return Rs2GroundItem.runWhilePaused(Microbot.pauseAllScripts::get);
            }));

            assertFalse(Microbot.pauseAllScripts.get());
        } finally {
            Microbot.pauseAllScripts.set(original);
        }
    }

    @Test
    public void overlappingCallsRemainPausedUntilLastScopeExits() throws Exception {
        boolean original = Microbot.pauseAllScripts.get();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        try {
            Microbot.pauseAllScripts.set(false);

            Future<Boolean> first = executor.submit(() -> Rs2GroundItem.runWhilePaused(() -> {
                firstEntered.countDown();
                await(releaseFirst);
                return true;
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            Future<Boolean> second = executor.submit(() -> Rs2GroundItem.runWhilePaused(() -> {
                secondEntered.countDown();
                await(releaseSecond);
                return true;
            }));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));

            releaseFirst.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(Microbot.pauseAllScripts.get());

            releaseSecond.countDown();
            assertTrue(second.get(5, TimeUnit.SECONDS));
            assertFalse(Microbot.pauseAllScripts.get());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            executor.shutdownNow();
            Microbot.pauseAllScripts.set(original);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", ex);
        }
    }
}
