package net.runelite.client.plugins.microbot.questhelper.logic;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MisthalinMirrorPlannerTest
{
    @Test
    public void misthalinCustomLogicUsesTheQuestSchedulerCadence()
    {
        assertEquals(200_000_000L, new MisthalinMystery().customLogicIntervalNanos());
    }

    @Test
    public void stagingTileIsPushedTowardTheWardrobeOntoTheFourthTile()
    {
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                tile(10, 15), tile(10, 10), tile(10, 20), ignored -> true);

        assertEquals(MisthalinMirrorPlanner.Direction.SOUTH, plan.getDirection());
        assertEquals(tile(10, 16), plan.getStandTile());
        assertEquals(tile(10, 14), plan.getExpectedMirrorTile());
        assertTrue(plan.isFinalAim());
    }

    @Test
    public void mirrorRoutesToTheStagingTileBeforeTheFinalAim()
    {
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                tile(8, 14), tile(10, 10), tile(10, 20), ignored -> true);

        assertEquals(MisthalinMirrorPlanner.Direction.NORTH, plan.getDirection());
        assertEquals(tile(8, 13), plan.getStandTile());
        assertEquals(tile(8, 15), plan.getExpectedMirrorTile());
        assertFalse(plan.isFinalAim());
    }

    @Test
    public void blockedPreferredAxisFallsBackToTheOtherAlignmentLane()
    {
        Set<MisthalinMirrorPlanner.SceneTile> blocked = new HashSet<>();
        blocked.add(tile(10, 9));

        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                tile(10, 10), tile(14, 12), tile(20, 12),
                point -> !blocked.contains(point));

        assertEquals(MisthalinMirrorPlanner.Direction.EAST, plan.getDirection());
        assertEquals(tile(9, 10), plan.getStandTile());
        assertEquals(tile(11, 10), plan.getExpectedMirrorTile());
    }

    @Test
    public void plannerRejectsPushesWithoutAValidStandAndDestination()
    {
        assertNull(MisthalinMirrorPlanner.nextPush(
                tile(10, 10), tile(14, 12), tile(20, 12), ignored -> false));
    }

    @Test
    public void mirrorOnTheFourthTileMovesOutwardBeforeBeingAimedBackOntoIt()
    {
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                tile(10, 14), tile(10, 10), tile(10, 20), ignored -> true);

        assertEquals(MisthalinMirrorPlanner.Direction.NORTH, plan.getDirection());
        assertEquals(tile(10, 15), plan.getExpectedMirrorTile());
        assertFalse(plan.isFinalAim());
    }

    @Test
    public void confirmedFinalPushSuppressesDuplicatesUntilTheAttackCycleEnds()
    {
        MisthalinMirrorPlanner.AttackState state = new MisthalinMirrorPlanner.AttackState();
        MisthalinMirrorPlanner.SceneTile mirror = tile(10, 10);
        MisthalinMirrorPlanner.SceneTile wardrobe = tile(10, 5);
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                mirror, wardrobe, tile(10, 20), ignored -> true);

        state.observe(mirror, wardrobe, 1_000L);
        assertTrue(state.canDispatch(1_000L));
        state.recordDispatch(mirror, plan, 1_000L, 500L);
        assertFalse(state.canDispatch(1_200L));

        state.observe(plan.getExpectedMirrorTile(), wardrobe, 1_300L);
        assertFalse("A confirmed final aim must not be repeated in the same attack cycle",
                state.canDispatch(2_000L));

        state.observe(plan.getExpectedMirrorTile(), null, 2_100L);
        state.observe(plan.getExpectedMirrorTile(), wardrobe, 2_200L);
        assertTrue("The same wardrobe tile may attack again after it visibly closes",
                state.canDispatch(2_200L));
    }

    @Test
    public void unobservedPushIsNotRepeatedDuringTheSameAttack()
    {
        MisthalinMirrorPlanner.AttackState state = new MisthalinMirrorPlanner.AttackState();
        MisthalinMirrorPlanner.SceneTile mirror = tile(10, 10);
        MisthalinMirrorPlanner.SceneTile wardrobe = tile(10, 5);
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                mirror, wardrobe, tile(10, 20), ignored -> true);

        state.observe(mirror, wardrobe, 1L, 1_000L);
        state.recordDispatch(mirror, plan, 1_000L, 500L);

        assertFalse(state.canDispatch(1_499L));
        state.observe(mirror, wardrobe, 1L, 1_500L);
        assertFalse(state.canDispatch(1_500L));

        state.observe(mirror, wardrobe, 2L, 2_000L);
        assertTrue(state.canDispatch(2_000L));
    }

    @Test
    public void acknowledgedNonFinalPushImmediatelyAllowsTheNextMove()
    {
        MisthalinMirrorPlanner.AttackState state = new MisthalinMirrorPlanner.AttackState();
        MisthalinMirrorPlanner.SceneTile mirror = tile(10, 10);
        MisthalinMirrorPlanner.SceneTile wardrobe = tile(14, 12);
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                mirror, wardrobe, tile(20, 12), ignored -> true);

        state.observe(mirror, wardrobe, 1L, 1_000L);
        state.recordDispatch(mirror, plan, 1_000L, 1_800L);

        assertTrue(state.observe(
                plan.getExpectedMirrorTile(), wardrobe, 1L, 1_600L));
        assertTrue(state.canDispatch(1_600L));
    }

    @Test
    public void repeatedGraphicAtSameWardrobeStartsANewAttackCycle()
    {
        MisthalinMirrorPlanner.AttackState state = new MisthalinMirrorPlanner.AttackState();
        MisthalinMirrorPlanner.SceneTile mirror = tile(10, 10);
        MisthalinMirrorPlanner.SceneTile wardrobe = tile(10, 5);
        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                mirror, wardrobe, tile(10, 20), ignored -> true);

        state.observe(mirror, wardrobe, 1L, 1_000L);
        state.recordDispatch(mirror, plan, 1_000L, 500L);
        state.observe(plan.getExpectedMirrorTile(), wardrobe, 1L, 1_300L);
        assertFalse(state.canDispatch(1_400L));

        state.observe(plan.getExpectedMirrorTile(), wardrobe, 2L, 2_000L);
        assertTrue("A new graphic spawn must start a new attack even at the same wardrobe",
                state.canDispatch(2_000L));
    }

    @Test
    public void cueStateOnlyAcceptsGraphic483AndNumbersEverySpawn()
    {
        MisthalinMirrorPlanner.CueState state = new MisthalinMirrorPlanner.CueState();
        MisthalinMirrorPlanner.SceneTile wardrobe = tile(12, 15);

        assertFalse(state.record(482, wardrobe, 7));
        assertNull(state.snapshot());

        assertTrue(state.record(483, wardrobe, 7));
        MisthalinMirrorPlanner.WardrobeCue first = state.snapshot();
        assertEquals(wardrobe, first.getTile());
        assertEquals(7, first.getWorldViewId());
        assertEquals(1L, first.getCycle());

        assertTrue(state.record(483, wardrobe, 7));
        assertEquals(2L, state.snapshot().getCycle());
    }

    @Test
    public void mirrorInstructionIsClaimedWithoutRequiringADefinedPoint()
    {
        assertTrue(MisthalinMystery.isMirrorShowdownText(java.util.List.of(
                "This puzzle requires you to move the mirror to reflect the knives the murderer throws.",
                "You can tell which wardrobe the murderer will throw from by a black swirl.")));
        assertFalse(MisthalinMystery.isMirrorShowdownText(java.util.List.of(
                "Climb over the damaged wall.")));
    }

    @Test
    public void mirrorStandApproachUsesTheCanvasWalkerWithItsOffscreenFallback()
    {
        WorldPoint target = new WorldPoint(1630, 4830, 0);
        AtomicReference<WorldPoint> dispatched = new AtomicReference<>();

        assertTrue(MisthalinMystery.dispatchMirrorMove(target, point -> {
            dispatched.set(point);
            return true;
        }));
        assertEquals(target, dispatched.get());
        assertFalse(MisthalinMystery.dispatchMirrorMove(null, ignored -> true));
    }

    private static MisthalinMirrorPlanner.SceneTile tile(int x, int y)
    {
        return new MisthalinMirrorPlanner.SceneTile(x, y);
    }
}
