package net.runelite.client.plugins.microbot.questhelper;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestDialogueAdvanceTest
{
    @Test
    public void unchangedPageSuppressesDuplicatesUntilRetryDeadline()
    {
        QuestDialogueAdvance gate = new QuestDialogueAdvance();
        assertTrue(gate.shouldAdvance("page", 0L));
        assertFalse(gate.shouldAdvance(new String("page"), 200_000_000L));
        assertFalse(gate.shouldAdvance("page", 1_199_999_999L));
        assertTrue(gate.shouldAdvance("page", 1_200_000_000L));
        assertFalse(gate.shouldAdvance("page", 1_400_000_000L));
    }

    @Test
    public void changedPageAdvancesImmediately()
    {
        QuestDialogueAdvance gate = new QuestDialogueAdvance();
        assertTrue(gate.shouldAdvance(1, 0L));
        assertTrue(gate.shouldAdvance(2, 200_000_000L));
        assertFalse(gate.shouldAdvance(2, 400_000_000L));
        assertTrue(gate.shouldAdvance(1, 600_000_000L));
    }

    @Test
    public void resetReopensTheSamePage()
    {
        QuestDialogueAdvance gate = new QuestDialogueAdvance();
        assertTrue(gate.shouldAdvance(1, 0L));
        assertFalse(gate.shouldAdvance(1, 200_000_000L));
        gate.reset();
        assertTrue(gate.shouldAdvance(1, 200_000_000L));
    }

    @Test
    public void nullPageKeyStillSuppressesDuplicates()
    {
        QuestDialogueAdvance gate = new QuestDialogueAdvance();
        assertTrue(gate.shouldAdvance(null, 0L));
        assertFalse(gate.shouldAdvance(null, 200_000_000L));
    }

    @Test
    public void retryDeadlineHandlesNanotimeWraparound()
    {
        QuestDialogueAdvance gate = new QuestDialogueAdvance();
        long start = Long.MAX_VALUE - 500_000_000L;
        assertTrue(gate.shouldAdvance(1, start));
        assertFalse(gate.shouldAdvance(1, start + 1_199_999_999L));
        assertTrue(gate.shouldAdvance(1, start + 1_200_000_000L));
    }
}
