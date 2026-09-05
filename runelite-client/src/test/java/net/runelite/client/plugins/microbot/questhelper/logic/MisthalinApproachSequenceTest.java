package net.runelite.client.plugins.microbot.questhelper.logic;

import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MisthalinApproachSequenceTest
{
    private static final WorldPoint PAINTING_ENTRY = new WorldPoint(1633, 4830, 0);
    private static final WorldPoint PAINTING_STAND = new WorldPoint(1629, 4832, 0);

    @Test
    public void validatedPaintingRouteAdvancesWithoutReturningToAnEarlierWaypoint()
    {
        QuestApproachSequence sequence = new QuestApproachSequence();
        Object routeKey = new Object();
        List<WorldPoint> route = List.of(PAINTING_ENTRY, PAINTING_STAND);

        assertEquals(PAINTING_ENTRY, sequence.next(
                routeKey, route, new WorldPoint(1635, 4839, 0), 1));
        assertEquals(PAINTING_STAND, sequence.next(routeKey, route, PAINTING_ENTRY, 1));
        assertNull(sequence.next(routeKey, route, PAINTING_STAND, 1));
        assertNull("A completed route must not send the player back to its first waypoint",
                sequence.next(routeKey, route, new WorldPoint(1631, 4833, 0), 1));
    }

    @Test
    public void aNewRouteStartsFreshButCanResumeAtItsLastValidatedWaypoint()
    {
        QuestApproachSequence sequence = new QuestApproachSequence();
        List<WorldPoint> painting = List.of(PAINTING_ENTRY, PAINTING_STAND);

        assertEquals(PAINTING_STAND, sequence.next("painting", painting, PAINTING_ENTRY, 1));
        assertEquals(PAINTING_ENTRY, sequence.next(
                "another-step", painting, new WorldPoint(1635, 4839, 0), 1));
        assertNull(sequence.next("resumed-at-painting", painting, PAINTING_STAND, 1));
    }

    @Test
    public void misthalinUsesTheValidatedPaintingAndFireplaceRoutes()
    {
        assertEquals(List.of(PAINTING_ENTRY, PAINTING_STAND),
                MisthalinMystery.approachRoute(new WorldPoint(1632, 4833, 0),
                        List.of("Slash the painting.")));
        assertEquals(List.of(
                        new WorldPoint(1633, 4837, 0),
                        new WorldPoint(1641, 4828, 0),
                        new WorldPoint(1646, 4836, 0)),
                MisthalinMystery.approachRoute(new WorldPoint(1647, 4836, 0),
                        List.of("Search the fireplace.")));
        assertEquals(List.of(),
                MisthalinMystery.approachRoute(new WorldPoint(1635, 4838, 0),
                        List.of("Open the door.")));
    }

    @Test
    public void damagedWallOutboundRouteUsesRubyDoorApproachOnly()
    {
        WorldPoint damagedWall = new WorldPoint(1648, 4829, 0);

        assertEquals(List.of(new WorldPoint(1646, 4829, 0)),
                MisthalinMystery.approachRoute(damagedWall,
                        List.of("Climb over the damaged wall.")));
        assertEquals("The return trip must retain generic handling until it is separately validated",
                List.of(), MisthalinMystery.approachRoute(damagedWall,
                        List.of("Climb back over the damaged wall into the manor.")));
    }

    @Test
    public void laceyInterruptSelectsCountCheckAndSuppressesGenericFallback() throws Exception
    {
        Method handler;
        try
        {
            handler = MisthalinMystery.class.getDeclaredMethod("handleLaceyInterrupt",
                    String.class, boolean.class, BooleanSupplier.class);
            handler.setAccessible(true);
        }
        catch (NoSuchMethodException ex)
        {
            fail("The Misthalin Lacey cutscene must own its unhighlighted answer prompt");
            return;
        }

        int[] selections = {0};
        BooleanSupplier selectCountCheck = () -> {
            selections[0]++;
            return true;
        };

        assertFalse("A handled prompt must not fall through to generic dialogue selection",
                (boolean) handler.invoke(null, "Interrupt with answer?", true, selectCountCheck));
        assertEquals(1, selections[0]);

        assertTrue((boolean) handler.invoke(null, "Another question?", true, selectCountCheck));
        assertFalse("A recognized prompt must remain owned while its options are loading",
                (boolean) handler.invoke(null, "Interrupt with answer?", false, selectCountCheck));
        assertEquals("Only a present Count Check option may be selected", 1, selections[0]);
    }

    @Test
    public void damagedWallApproachKeepsControlUntilThePlayerCanClimb() throws Exception
    {
        Method approach;
        try
        {
            approach = MisthalinMystery.class.getDeclaredMethod("handleDamagedWallApproach",
                    WorldPoint.class, boolean.class, Runnable.class, Runnable.class,
                    BooleanSupplier.class);
            approach.setAccessible(true);
        }
        catch (NoSuchMethodException ex)
        {
            fail("The outbound wall route must include its final canvas approach before generic interaction");
            return;
        }

        int[] canvas = {0};
        int[] localApproach = {0};
        int[] climbs = {0};
        MisthalinMystery quest = new MisthalinMystery();
        Runnable canvasMove = () -> canvas[0]++;
        Runnable localMove = () -> localApproach[0]++;
        BooleanSupplier climb = () -> {
            climbs[0]++;
            return true;
        };

        assertFalse((boolean) approach.invoke(quest, new WorldPoint(1642, 4839, 0),
                false, canvasMove, localMove, climb));
        assertEquals(0, canvas[0]);
        assertEquals(1, localApproach[0]);

        assertFalse((boolean) approach.invoke(quest, new WorldPoint(1642, 4839, 0),
                false, canvasMove, localMove, climb));
        assertEquals("The local recovery waypoint must not be clicked every poll",
                1, localApproach[0]);

        assertFalse((boolean) approach.invoke(quest, new WorldPoint(1645, 4830, 0),
                false, canvasMove, localMove, climb));
        assertEquals(1, canvas[0]);
        assertEquals(1, localApproach[0]);

        assertFalse((boolean) approach.invoke(quest, new WorldPoint(1645, 4830, 0),
                false, canvasMove, localMove, climb));
        assertEquals("An unacknowledged canvas move must not be clicked every custom-logic poll",
                1, canvas[0]);
        assertEquals(1, localApproach[0]);

        assertFalse((boolean) approach.invoke(quest, new WorldPoint(1646, 4829, 0),
                true, canvasMove, localMove, climb));
        assertEquals("Movement must suppress duplicate approach clicks", 1, canvas[0]);
        assertEquals(1, localApproach[0]);

        assertFalse("Custom logic must retain ownership while dispatching Climb",
                (boolean) approach.invoke(quest, new WorldPoint(1647, 4829, 0),
                        false, canvasMove, localMove, climb));
        assertEquals(1, canvas[0]);
        assertEquals(1, localApproach[0]);
        assertEquals(1, climbs[0]);

        assertFalse((boolean) approach.invoke(quest, new WorldPoint(1647, 4829, 0),
                false, canvasMove, localMove, climb));
        assertEquals("The same wall interaction must not be dispatched every poll", 1, climbs[0]);
    }

    @Test
    public void misthalinCustomRouteHandlerIsRegistered()
    {
        IQuest quest = QuestRegistry.getQuest(Quest.MISTHALIN_MYSTERY.getId());

        assertNotNull("Misthalin route sequencing must run before generic object routing", quest);
        assertEquals(MisthalinMystery.class, quest.getClass());
    }

    @Test
    public void validatedLocalApproachesDispatchCanvasMovement() throws Exception
    {
        Method dispatch;
        try
        {
            dispatch = MisthalinMystery.class.getDeclaredMethod(
                    "dispatchWaypoint", WorldPoint.class, Runnable.class, Runnable.class);
            dispatch.setAccessible(true);
        }
        catch (NoSuchMethodException ex)
        {
            fail("Validated routes must preserve each waypoint's movement mode");
            return;
        }

        int[] canvas = {0};
        int[] webWalker = {0};
        Runnable canvasMove = () -> canvas[0]++;
        Runnable webWalk = () -> webWalker[0]++;

        dispatch.invoke(null, PAINTING_ENTRY, canvasMove, webWalk);
        assertEquals(1, canvas[0]);
        assertEquals(0, webWalker[0]);

        for (WorldPoint waypoint : List.of(
                PAINTING_STAND,
                new WorldPoint(1646, 4829, 0),
                new WorldPoint(1650, 4830, 0),
                new WorldPoint(1633, 4837, 0),
                new WorldPoint(1641, 4828, 0),
                new WorldPoint(1646, 4836, 0)))
        {
            dispatch.invoke(null, waypoint, canvasMove, webWalk);
        }
        assertEquals(3, canvas[0]);
        assertEquals(4, webWalker[0]);
    }

    @Test
    public void questLifecycleResetClearsACompletedApproachRoute() throws Exception
    {
        MisthalinMystery quest = (MisthalinMystery) QuestRegistry.getQuest(
                Quest.MISTHALIN_MYSTERY.getId());
        Field field = MisthalinMystery.class.getDeclaredField("approachSequence");
        field.setAccessible(true);
        QuestApproachSequence sequence = (QuestApproachSequence) field.get(quest);
        List<WorldPoint> route = List.of(PAINTING_ENTRY, PAINTING_STAND);
        sequence.next("painting", route, PAINTING_STAND, 1);

        QuestRegistry.resetAll();

        assertEquals(PAINTING_ENTRY,
                sequence.next("painting", route, new WorldPoint(1635, 4839, 0), 1));
    }

    @Test
    public void routeStopsForEveryQuestSafetyGate() throws Exception
    {
        Method stop;
        try
        {
            stop = MisthalinMystery.class.getDeclaredMethod("shouldStopRoute",
                    boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class);
            stop.setAccessible(true);
        }
        catch (NoSuchMethodException ex)
        {
            fail("Custom WebWalker legs must preserve all QuestScript cancellation gates");
            return;
        }

        assertFalse((boolean) stop.invoke(null, false, false, false, true, true, true));
        assertTrue((boolean) stop.invoke(null, true, false, false, true, true, true));
        assertTrue((boolean) stop.invoke(null, false, true, false, true, true, true));
        assertTrue((boolean) stop.invoke(null, false, false, true, true, true, true));
        assertTrue((boolean) stop.invoke(null, false, false, false, false, true, true));
        assertTrue((boolean) stop.invoke(null, false, false, false, true, false, true));
        assertTrue((boolean) stop.invoke(null, false, false, false, true, true, false));
    }
}
