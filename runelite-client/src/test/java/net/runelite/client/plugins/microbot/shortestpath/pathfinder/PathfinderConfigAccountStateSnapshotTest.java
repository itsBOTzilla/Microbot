package net.runelite.client.plugins.microbot.shortestpath.pathfinder;

import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.plugins.microbot.shortestpath.TransportVarPlayer;
import net.runelite.client.plugins.microbot.shortestpath.TransportVarbit;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PathfinderConfigAccountStateSnapshotTest
{
    private static final int[] NO_SKILLS = new int[0];
    private static final int[] VARBIT_EQUALS_SEVEN = {
            1234, TransportVarbit.Operator.EQUAL.ordinal(), 7};
    private static final int[] VARPLAYER_EQUALS_ONE = {
            4321, TransportVarPlayer.Operator.EQUAL.ordinal(), 1};
    private static final int[] CLIENT_OF_KOUREND = {Quest.CLIENT_OF_KOUREND.getId()};

    @Test
    public void accountSnapshotProvidersInvalidateTransportCacheForEveryRelevantRequirement()
    {
        IntFunction<QuestState> finishedQuest = ignored -> QuestState.FINISHED;

        int baseline = hash(finishedQuest, ignored -> 7, ignored -> 1);
        assertNotEquals("a changed varbit verdict must invalidate the cached transport set", baseline,
                hash(finishedQuest, ignored -> 8, ignored -> 1));
        assertNotEquals("a changed varplayer verdict must invalidate the cached transport set", baseline,
                hash(finishedQuest, ignored -> 7, ignored -> 2));
        assertNotEquals("a changed quest state must invalidate the cached transport set", baseline,
                hash(ignored -> QuestState.NOT_STARTED, ignored -> 7, ignored -> 1));
    }

    @Test
    public void changedAccountSnapshotCannotReuseOrPublishCachedTransportSet()
    {
        IntFunction<QuestState> finishedQuest = ignored -> QuestState.FINISHED;
        int baseline = hash(finishedQuest, ignored -> 7, ignored -> 1);

        assertTrue(PathfinderConfig.isTransportRefreshVerificationCurrent(baseline, NO_SKILLS, NO_SKILLS,
                VARBIT_EQUALS_SEVEN, VARPLAYER_EQUALS_ONE, CLIENT_OF_KOUREND, finishedQuest,
                ignored -> 7, ignored -> 1));
        assertFalse("a state transition during full filtering must discard the stale cache entry",
                PathfinderConfig.isTransportRefreshVerificationCurrent(baseline, NO_SKILLS, NO_SKILLS,
                        VARBIT_EQUALS_SEVEN, VARPLAYER_EQUALS_ONE, CLIENT_OF_KOUREND, finishedQuest,
                        ignored -> 8, ignored -> 1));
    }

    private static int hash(IntFunction<QuestState> questStates, IntUnaryOperator varbits,
                            IntUnaryOperator varplayers)
    {
        return PathfinderConfig.computeTransportRefreshVerificationHash(NO_SKILLS, NO_SKILLS, VARBIT_EQUALS_SEVEN,
                VARPLAYER_EQUALS_ONE, CLIENT_OF_KOUREND, questStates, varbits, varplayers);
    }
}
