package net.runelite.client.plugins.microbot.questhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QuestInteractionFlowTest
{
    @Test
    public void unchangedPageBlocksCustomDialogueLogic()
    {
        assertFalse(QuestInteractionFlow.allowGenericDialogue(() -> false,
                () -> { fail("Unchanged page must not repeat custom input"); return true; }));
    }

    @Test
    public void customDialogueCanSuppressGenericInputAfterPageGate()
    {
        List<String> calls = new ArrayList<>();
        assertFalse(QuestInteractionFlow.allowGenericDialogue(
                () -> { calls.add("page"); return true; },
                () -> { calls.add("custom"); return false; }));
        assertEquals(Arrays.asList("page", "custom"), calls);
    }

    @Test
    public void genericDialogueRunsOnlyAfterCustomLogicPermitsIt()
    {
        List<String> calls = new ArrayList<>();
        if (QuestInteractionFlow.allowGenericDialogue(
                () -> { calls.add("page"); return true; },
                () -> { calls.add("custom"); return true; }))
        {
            calls.add("generic");
        }
        assertEquals(Arrays.asList("page", "custom", "generic"), calls);
    }

    @Test
    public void initiallyReadyTargetDispatchesWithoutWalking()
    {
        List<String> calls = new ArrayList<>();
        assertTrue(QuestInteractionFlow.run(
                () -> { calls.add("current"); return true; },
                () -> { calls.add("ready"); return true; },
                () -> { fail("Ready target must not walk"); return WalkerState.EXIT; },
                () -> { calls.add("dispatch"); return true; }));
        assertEquals(Arrays.asList("current", "ready", "current", "dispatch"), calls);
    }

    @Test
    public void walkHandoffDispatchesInTheSameInvocationWithFreshChecks()
    {
        AtomicBoolean ready = new AtomicBoolean();
        List<String> calls = new ArrayList<>();
        assertTrue(QuestInteractionFlow.run(
                () -> { calls.add("current"); return true; },
                () -> { calls.add("ready"); return ready.get(); },
                () -> { calls.add("walk"); ready.set(true); return WalkerState.ARRIVED; },
                () -> { calls.add("dispatch"); return true; }));
        assertEquals(Arrays.asList("current", "ready", "walk", "current", "ready", "dispatch"), calls);
    }

    @Test
    public void stepChangeOrStaleTargetAfterWalkingPreventsDispatch()
    {
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger readinessChecks = new AtomicInteger();
        assertFalse(QuestInteractionFlow.run(current::get,
                () -> readinessChecks.incrementAndGet() > 1,
                () -> { current.set(false); return WalkerState.ARRIVED; },
                () -> { fail("Stale interaction must not dispatch"); return true; }));
        assertEquals(1, readinessChecks.get());
    }

    @Test
    public void readinessLostAfterCompletionCallbackPreventsDispatch()
    {
        AtomicBoolean ready = new AtomicBoolean();
        assertFalse(QuestInteractionFlow.run(() -> true, ready::get,
                () -> {
                    ready.set(true);
                    assertTrue("Walker completion callback was satisfied", ready.get());
                    ready.set(false);
                    return WalkerState.ARRIVED;
                },
                () -> { fail("Expired readiness must not dispatch"); return true; }));
    }

    @Test
    public void everyNonArrivalBlocksDispatch()
    {
        for (WalkerState state : WalkerState.values())
        {
            if (state == WalkerState.ARRIVED)
            {
                continue;
            }
            AtomicInteger currentChecks = new AtomicInteger();
            assertFalse(state.name(), QuestInteractionFlow.run(
                    () -> { currentChecks.incrementAndGet(); return true; }, () -> false,
                    () -> state,
                    () -> { fail("Non-arrival must not dispatch: " + state); return true; }));
            assertEquals(1, currentChecks.get());
        }
    }

    @Test
    public void invalidInteractionDoesNotReadReadinessWalkOrDispatch()
    {
        assertFalse(QuestInteractionFlow.run(() -> false,
                () -> { fail("Must validate before checking readiness"); return true; },
                () -> { fail("Invalid interaction must not walk"); return WalkerState.ARRIVED; },
                () -> { fail("Invalid interaction must not dispatch"); return true; }));
    }

    @Test
    public void initiallyReadyInteractionStillRechecksCurrentBeforeDispatch()
    {
        AtomicInteger checks = new AtomicInteger();
        assertFalse(QuestInteractionFlow.run(() -> checks.incrementAndGet() == 1, () -> true,
                () -> { fail("Ready target must not walk"); return WalkerState.EXIT; },
                () -> { fail("Invalidated interaction must not dispatch"); return true; }));
        assertEquals(2, checks.get());
    }

    @Test
    public void failedDispatchReturnsFalse()
    {
        AtomicInteger dispatches = new AtomicInteger();
        assertFalse(QuestInteractionFlow.run(() -> true, () -> true,
                () -> WalkerState.ARRIVED,
                () -> { dispatches.incrementAndGet(); return false; }));
        assertEquals(1, dispatches.get());
    }

    @Test
    public void pendingActivitySuppressesDuplicatesUntilRetryAndFreshReadiness()
    {
        AtomicReference<QuestInteractionAttempt> pending = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        assertTrue(QuestInteractionFlow.run(() -> pending.get() == null, () -> true,
                () -> WalkerState.ARRIVED,
                () -> {
                    dispatches.incrementAndGet();
                    pending.set(new QuestInteractionAttempt(0L, 1_200_000_000L));
                    return true;
                }));
        for (long now = 200_000_000L; now <= 2_000_000_000L; now += 200_000_000L)
        {
            assertEquals(QuestInteractionAttempt.Result.WAIT,
                    pending.get().observe(now, true, false, true));
            assertFalse(QuestInteractionFlow.run(() -> pending.get() == null,
                    () -> { fail("Pending action owns readiness"); return true; },
                    () -> { fail("Pending action must not walk again"); return WalkerState.ARRIVED; },
                    () -> { dispatches.incrementAndGet(); return true; }));
        }
        assertEquals(1, dispatches.get());
        assertEquals(QuestInteractionAttempt.Result.RETRY,
                pending.get().observe(3_200_000_000L, false, false, true));
        pending.set(null);
        assertFalse(QuestInteractionFlow.run(() -> pending.get() == null, () -> false,
                () -> WalkerState.UNREACHABLE,
                () -> { dispatches.incrementAndGet(); return true; }));
        assertEquals(1, dispatches.get());
        assertTrue(QuestInteractionFlow.run(() -> pending.get() == null, () -> true,
                () -> WalkerState.ARRIVED,
                () -> { dispatches.incrementAndGet(); return true; }));
        assertEquals(2, dispatches.get());
    }
}
