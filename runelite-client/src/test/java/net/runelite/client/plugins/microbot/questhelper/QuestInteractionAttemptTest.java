package net.runelite.client.plugins.microbot.questhelper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestInteractionAttemptTest
{
    @Test
    public void briefAnimationGapsDoNotSettleTheAttempt()
    {
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
        attempt.observe(600_000_000L, true, false, true);
        assertFalse(attempt.isAnimationSettled(800_000_000L, false, true));
        attempt.observe(1_200_000_000L, true, false, true);
        assertFalse(attempt.isAnimationSettled(2_399_999_999L, false, true));
        assertTrue(attempt.isAnimationSettled(2_400_000_000L, false, true));
        assertFalse(attempt.isAnimationSettled(2_400_000_000L, true, true));
    }

    @Test
    public void movementProgressWithoutAnimationNeverSettles()
    {
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
        attempt.observe(600_000_000L, true, false, true);
        assertTrue(attempt.hasProgressed());
        assertFalse(attempt.isAnimationSettled(5_000_000_000L, false, false));
    }

    @Test
    public void attemptExpiresAtDeadline()
    {
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
        assertFalse(attempt.isExpired(1_199_999_999L));
        assertTrue(attempt.isExpired(1_200_000_000L));
        assertEquals(QuestInteractionAttempt.Result.WAIT,
            attempt.observe(1_199_999_999L, false, false, true));
        assertEquals(QuestInteractionAttempt.Result.RETRY,
            attempt.observe(1_200_000_000L, false, false, true));
    }

    @Test
    public void progressSuppressesRetryWithoutCompletingLongAction()
    {
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
        assertFalse(attempt.hasProgressed());
        for (long now = 1_000_000_000L; now <= 20_000_000_000L; now += 1_000_000_000L)
        {
            assertEquals(QuestInteractionAttempt.Result.WAIT, attempt.observe(now, true, false, true));
        }
        assertTrue(attempt.hasProgressed());
        assertEquals(QuestInteractionAttempt.Result.WAIT,
            attempt.observe(21_199_999_999L, false, false, true));
        assertEquals(QuestInteractionAttempt.Result.RETRY,
            attempt.observe(21_200_000_000L, false, false, true));
        assertTrue(attempt.hasProgressed());
    }

    @Test
    public void invalidTargetCannotCompleteEvenWhenBusy()
    {
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
        assertEquals(QuestInteractionAttempt.Result.INVALIDATED,
            attempt.observe(100L, true, true, false));
    }

    @Test
    public void observedCompletionWinsOverBusyOrDeadline()
    {
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
        assertEquals(QuestInteractionAttempt.Result.COMPLETE,
            attempt.observe(2_000_000_000L, true, true, true));
    }

    @Test
    public void elapsedTimeMeasuresFromDispatchIncludingNanotimeWraparound()
    {
        long start = Long.MAX_VALUE - 500_000_000L;
        QuestInteractionAttempt attempt = new QuestInteractionAttempt(start, start + 1_200_000_000L);
        assertEquals(1234L, attempt.elapsedMillis(start + 1_234_567_890L));
        assertFalse(attempt.isExpired(start + 1_199_999_999L));
        assertTrue(attempt.isExpired(start + 1_200_000_000L));
    }
}
