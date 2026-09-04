package net.runelite.client.plugins.microbot.util.walker;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WebWalkExecutorTest
{
    private static final WorldPoint START = point(0);
    private static final WorldPoint GOAL = point(30);

    @Test
    public void acceptedCheckpointWaitsForFreshProgressInsteadOfClickingAgain()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMinimapDispatch(100, point(10), point(10), 10, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, START, 1, 0, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
        assertEquals(point(10), session.getCheckpoint());
    }

    @Test
    public void passingCheckpointHandsOffToForwardTargetImmediately()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMinimapDispatch(100, point(10), point(10), 10, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, point(2), 1, 11, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, decision.getType());
        assertEquals(point(12), decision.getTarget());
    }

    @Test
    public void checkpointApproachHandsOffLogicallyWithoutImmediateDispatch()
    {
        for (boolean runEnabled : new boolean[] {false, true})
        {
            WebWalkSession session = new WebWalkSession(GOAL, 0);
            session.installRoute(route(1));
            session.observe(100, START, 0);
            session.recordMinimapDispatch(100, point(12), point(12), 4, false);
            WebWalkExecutor executor = new WebWalkExecutor();

            assertEquals(WebWalkExecutor.DecisionType.WAIT,
                    executor.decide(session, ready(101, point(6), 1, 2, point(14), 5,
                            false, runEnabled, true)).getType());

            WebWalkExecutor.Decision handoff = executor.decide(session,
                    ready(102, point(7), 1, 2, point(14), 5, false,
                            runEnabled, true));

            assertEquals(WebWalkExecutor.DecisionType.WAIT, handoff.getType());
            assertNull(session.getCheckpoint());
            assertEquals(point(12), session.getActiveTarget());
            assertEquals(WebWalkRuntime.DispatchMethod.MINIMAP, session.getActiveMethod());

            WebWalkExecutor.Decision continuedProgress = executor.decide(session,
                    ready(103, point(8), 1, 3, point(14), 5, false,
                            runEnabled, true));

            assertEquals(WebWalkExecutor.DecisionType.WAIT, continuedProgress.getType());
            assertEquals(point(12), session.getActiveTarget());
        }
    }

    @Test
    public void handedOffCommandAdvancesOnceOldTargetIsNearAndForwardTargetChanged()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMovementDispatch(100, point(12), point(12), 12, false,
                WebWalkRuntime.DispatchMethod.MINIMAP);
        WebWalkExecutor executor = new WebWalkExecutor();

        assertEquals(WebWalkExecutor.DecisionType.WAIT, executor.decide(session,
                ready(102, point(7), 1, 2, point(14), 14, false,
                        false, true)).getType());

        WebWalkExecutor.Decision advance = executor.decide(session,
                ready(104, point(11), 1, 11, point(20), 20, false,
                        false, true));

        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, advance.getType());
        assertEquals(point(20), advance.getTarget());
        assertEquals(false, advance.isRedispatch());
    }

    @Test
    public void handedOffMinimapTargetDoesNotSwitchToCanvasWhileStillProgressing()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMovementDispatch(100, point(12), point(12), 12, false,
                WebWalkRuntime.DispatchMethod.MINIMAP);
        WebWalkExecutor executor = new WebWalkExecutor();

        WebWalkExecutor.Decision handoff = executor.decide(session,
                ready(102, point(7), 1, 2, point(12), 12, false,
                        false, true));
        assertEquals(WebWalkExecutor.DecisionType.WAIT, handoff.getType());
        assertNull(session.getCheckpoint());

        WebWalkExecutor.Decision nearAndProgressing = executor.decide(session,
                ready(104, point(11), 1, 11, point(12), 12, false,
                        false, true));

        assertEquals(WebWalkExecutor.DecisionType.WAIT, nearAndProgressing.getType());
        assertEquals(WebWalkRuntime.DispatchMethod.MINIMAP, session.getActiveMethod());
    }

    @Test
    public void checkpointWithoutInitialPlayerDistanceDoesNotHandoff()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.recordMinimapDispatch(100, point(12), point(12), 4, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, point(7), 1, 2, point(14), 5, false));

        assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
        assertEquals(point(12), session.getCheckpoint());
    }

    @Test
    public void checkpointStartedInsideFiveTilesReleasesWhenReached()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, point(8), 2);
        session.recordMinimapDispatch(100, point(10), point(10), 3, false);
        WebWalkExecutor executor = new WebWalkExecutor();

        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(101, point(8), 1, 2, point(12), 4,
                        false)).getType());

        WebWalkExecutor.Decision reached = executor.decide(session,
                ready(102, point(9), 1, 2, point(12), 4, false));

        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, reached.getType());
        assertEquals(point(12), reached.getTarget());
    }

    @Test
    public void reachedTargetCannotDispatchSameLogicalTargetEveryTick()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, point(8), 2);
        session.recordMinimapDispatch(100, point(10), point(10), 3, false);
        WebWalkExecutor executor = new WebWalkExecutor();

        assertEquals(WebWalkExecutor.DecisionType.WAIT, executor.decide(session,
                ready(101, point(9), 1, 2, point(10), 3, false)).getType());

        WebWalkExecutor.Decision intervalElapsed = executor.decide(session,
                ready(103, point(9), 1, 2, point(10), 3, false));

        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, intervalElapsed.getType());
        assertEquals(true, intervalElapsed.isRedispatch());
    }

    @Test
    public void activeCanvasTargetSettlesThenReplansWithoutASecondClick()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, point(25), 25);
        session.recordMovementDispatch(100, GOAL, GOAL, 30, false,
                WebWalkRuntime.DispatchMethod.CANVAS);
        WebWalkExecutor executor = new WebWalkExecutor();

        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(101, point(29), 1, 29, GOAL, 30,
                        false, false, true)).getType());
        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(104, point(29), 1, 29, GOAL, 30,
                        false, false, false)).getType());
        assertEquals(WebWalkExecutor.DecisionType.REPLAN,
                executor.decide(session, ready(105, point(29), 1, 29, GOAL, 30,
                        false, false, false)).getType());
    }

    @Test
    public void activeCanvasCheckpointCannotRedispatchBeforeReachingOneTile()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, point(25), 25);
        session.recordMovementDispatch(100, GOAL, GOAL, 30, false,
                WebWalkRuntime.DispatchMethod.CANVAS);
        WebWalkExecutor executor = new WebWalkExecutor();

        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(101, point(28), 1, 28, GOAL, 30,
                        false, false, true)).getType());
        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(104, point(28), 1, 28, GOAL, 30,
                        false, false, false)).getType());
        assertEquals(WebWalkExecutor.DecisionType.REPLAN,
                executor.decide(session, ready(105, point(28), 1, 28, GOAL, 30,
                        false, false, false)).getType());
    }

    @Test
    public void handedOffCanvasTargetCannotRedispatchAfterDispatchTimeDistanceRace()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, point(24), 24);
        session.recordMovementDispatch(100, GOAL, GOAL, 30, false,
                WebWalkRuntime.DispatchMethod.CANVAS);
        WebWalkExecutor executor = new WebWalkExecutor();

        WebWalkExecutor.Decision handoff = executor.decide(session,
                ready(101, point(25), 1, 25, GOAL, 30,
                        false, false, true));
        assertEquals(WebWalkExecutor.DecisionType.WAIT, handoff.getType());
        assertNull(session.getCheckpoint());
        assertEquals(GOAL, session.getActiveTarget());

        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(104, point(25), 1, 25, GOAL, 30,
                        false, false, false)).getType());
        assertEquals(WebWalkExecutor.DecisionType.REPLAN,
                executor.decide(session, ready(105, point(25), 1, 25, GOAL, 30,
                        false, false, false)).getType());
    }

    @Test
    public void runningAndWalkingRetainCheckpointUntilFiveTileHandoffOrPassed()
    {
        for (boolean runEnabled : new boolean[] {false, true})
        {
            for (int playerX : new int[] {2, 4})
            {
                WebWalkSession session = new WebWalkSession(GOAL, 0);
                session.installRoute(route(1));
                session.observe(100, START, 0);
                session.recordMinimapDispatch(100, point(10), point(10), 10, false);

                WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                        ready(101, point(playerX), 1, 2, point(12), 12,
                                false, runEnabled));

                assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
                assertEquals(point(10), session.getCheckpoint());
            }

            WebWalkSession reached = new WebWalkSession(GOAL, 0);
            reached.installRoute(route(1));
            reached.observe(100, START, 0);
            reached.recordMinimapDispatch(100, point(10), point(10), 10, false);

            WebWalkExecutor.Decision next = new WebWalkExecutor().decide(reached,
                    ready(101, point(9), 1, 2, point(12), 12,
                            false, runEnabled));

            assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, next.getType());
            assertEquals(point(12), next.getTarget());
        }
    }

    @Test
    public void walkingCheckpointOutsideWalkOverlapStaysOwned()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMinimapDispatch(100, point(10), point(10), 10, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, point(2), 1, 2, point(12), 12, false, false));

        assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
        assertEquals(point(10), session.getCheckpoint());
    }

    @Test
    public void checkpointInsideHandoffBandWaitsForActualApproach()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMinimapDispatch(100, point(8), point(8), 8, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, START, 1, 0, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
        assertEquals(point(8), session.getCheckpoint());
    }

    @Test
    public void diagonalCheckpointOutsideCircularHandoffStaysOwned()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMinimapDispatch(100, point(10, 10), point(10, 10), 10, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, point(3, 3), 1, 3, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
        assertEquals(point(10, 10), session.getCheckpoint());
    }

    @Test
    public void routeActionAlwaysWinsOverMinimapDispatch()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(100, START, 1, 0, point(12), 12, true));

        assertEquals(WebWalkExecutor.DecisionType.INTERACT_ROUTE_EDGE, decision.getType());
    }

    @Test
    public void acceptedActionGetsBoundedGraceThenOneRedispatch()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMinimapDispatch(100, point(10), point(10), 10, false);
        WebWalkExecutor executor = new WebWalkExecutor();

        assertEquals(WebWalkExecutor.DecisionType.WAIT,
                executor.decide(session, ready(103, START, 1, 0, point(12), 12, false)).getType());
        WebWalkExecutor.Decision redispatch = executor.decide(session,
                ready(104, START, 1, 0, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, redispatch.getType());
        assertEquals(true, redispatch.isRedispatch());
    }

    @Test
    public void stalledMovementReplansAfterItsSingleRedispatchMakesNoProgress()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMovementDispatch(100, point(10), point(10), 10, false,
                WebWalkRuntime.DispatchMethod.MINIMAP);
        WebWalkExecutor executor = new WebWalkExecutor();

        WebWalkExecutor.Decision redispatch = executor.decide(session,
                ready(104, START, 1, 0, point(12), 12, false));
        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, redispatch.getType());
        assertEquals(true, redispatch.isRedispatch());

        session.recordMovementDispatch(104, point(12), point(12), 12, true,
                WebWalkRuntime.DispatchMethod.MINIMAP);

        assertEquals(WebWalkExecutor.DecisionType.REPLAN, executor.decide(session,
                ready(108, START, 1, 0, point(12), 12, false)).getType());
    }

    @Test
    public void movementCommandTracksDistanceAndProgressTick()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(100, START, 0);
        session.recordMovementDispatch(100, point(10), point(10), 10, false,
                WebWalkRuntime.DispatchMethod.CANVAS);

        assertEquals(point(10), session.getActiveTarget());
        assertEquals(WebWalkRuntime.DispatchMethod.CANVAS, session.getActiveMethod());
        assertEquals(100, session.getDispatchTick());
        assertEquals(10, session.getDistanceAtDispatch());
        assertEquals(10, session.getLastObservedDistance());

        session.observe(101, point(2), 2);

        assertEquals(8, session.getLastObservedDistance());
        assertEquals(101, session.getLastProgressTick());
    }

    @Test
    public void acceptedDispatchAwaitsBeforeTakingAnotherObservation()
    {
        RecordingRuntime runtime = new RecordingRuntime();

        WalkerState result = new WebWalkExecutor().walk(new WebWalkSession(GOAL, 0), runtime);

        assertEquals(WalkerState.ARRIVED, result);
        assertEquals(Arrays.asList("observe", "dispatch", "await", "observe", "finish"),
                runtime.events);
    }

    @Test
    public void routeActionPreemptsMovementThenResumesWithoutDoubleDispatch()
    {
        RouteActionRuntime runtime = new RouteActionRuntime();

        WalkerState result = new WebWalkExecutor().walk(new WebWalkSession(GOAL, 0), runtime);

        assertEquals(WalkerState.ARRIVED, result);
        assertEquals(Arrays.asList(
                "observe", "dispatch", "await",
                "observe", "interact", "await",
                "observe", "dispatch", "await",
                "observe", "finish"), runtime.events);
        assertEquals(2, runtime.dispatches);
    }

    @Test
    public void residualMovementDoesNotEraseRejectedDispatchBudget()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(10, START, 0);
        session.recordRejectedDispatch();
        session.observe(11, point(1), 1);
        assertEquals(1, session.getRejectedDispatchCount());
        session.recordRejectedDispatch();

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(12, point(1), 1, 1, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.REPLAN, decision.getType());
    }

    @Test
    public void acceptedDispatchResetsRejectedDispatchBudget()
    {
        WebWalkSession session = new WebWalkSession(GOAL, 0);
        session.installRoute(route(1));
        session.observe(10, START, 0);
        session.recordRejectedDispatch();
        session.recordMinimapDispatch(11, point(10), point(10), 10, false);

        assertEquals(0, session.getRejectedDispatchCount());
    }

    @Test
    public void executorStopsAtIndependentIterationLimit()
    {
        WaitingRuntime runtime = new WaitingRuntime();

        WalkerState result = new WebWalkExecutor().walk(new WebWalkSession(GOAL, 0), runtime);

        assertEquals(WalkerState.EXIT, result);
        assertEquals(WebWalkExecutor.MAX_EXECUTOR_ITERATIONS, runtime.observations);
        assertEquals("iteration-limit", runtime.finishedReason);
    }

    private static WebWalkRuntime.Observation ready(int tick, WorldPoint player, long generation,
                                                     int pathIndex, WorldPoint clickTarget,
                                                     int clickIndex, boolean routeAction)
    {
        return ready(tick, player, generation, pathIndex, clickTarget, clickIndex,
                routeAction, false);
    }

    private static WebWalkRuntime.Observation ready(int tick, WorldPoint player, long generation,
                                                     int pathIndex, WorldPoint clickTarget,
                                                     int clickIndex, boolean routeAction,
                                                     boolean runEnabled)
    {
        return ready(tick, player, generation, pathIndex, clickTarget, clickIndex,
                routeAction, runEnabled, false);
    }

    private static WebWalkRuntime.Observation ready(int tick, WorldPoint player, long generation,
                                                     int pathIndex, WorldPoint clickTarget,
                                                     int clickIndex, boolean routeAction,
                                                     boolean runEnabled, boolean moving)
    {
        return new WebWalkRuntime.Observation(tick, player, WebWalkRuntime.Status.READY,
                route(generation), pathIndex, clickTarget, clickIndex, routeAction,
                runEnabled, moving);
    }

    private static WebWalkRuntime.RouteSnapshot route(long generation)
    {
        return new WebWalkRuntime.RouteSnapshot(generation,
                Arrays.asList(point(0), point(1), point(2), point(10), point(12), point(14)),
                Arrays.asList(point(0), point(14)));
    }

    private static WorldPoint point(int x)
    {
        return point(x, 0);
    }

    private static WorldPoint point(int x, int y)
    {
        return new WorldPoint(3200 + x, 3200 + y, 0);
    }

    private static final class RecordingRuntime implements WebWalkRuntime
    {
        private final List<String> events = new ArrayList<>();
        private int observations;

        @Override
        public Observation observe(WebWalkSession session)
        {
            events.add("observe");
            if (observations++ == 0)
            {
                return ready(100, START, 1, 0, point(12), 12, false);
            }
            return new Observation(101, point(1), Status.ARRIVED,
                    route(1), 1, null, -1, false);
        }

        @Override
        public DispatchResult dispatchMinimap(WorldPoint requestedTarget, int pathIndex,
                                               boolean redispatch)
        {
            events.add("dispatch");
            return DispatchResult.accepted(requestedTarget);
        }

        @Override
        public ActionResult interactRouteEdge(Observation observation)
        {
            throw new AssertionError("unexpected route action");
        }

        @Override
        public void replan(WebWalkSession session, String reason)
        {
            throw new AssertionError("unexpected replan");
        }

        @Override
        public void awaitChange(Observation observation)
        {
            events.add("await");
        }

        @Override
        public void finish(WalkerState state, String reason)
        {
            events.add("finish");
        }
    }

    private static final class WaitingRuntime implements WebWalkRuntime
    {
        private int observations;
        private String finishedReason;

        @Override
        public Observation observe(WebWalkSession session)
        {
            observations++;
            return new Observation(observations, START, Status.WAITING_FOR_PATH,
                    null, -1, null, -1, false);
        }

        @Override
        public DispatchResult dispatchMinimap(WorldPoint requestedTarget, int pathIndex,
                                               boolean redispatch)
        {
            throw new AssertionError("unexpected minimap dispatch");
        }

        @Override
        public ActionResult interactRouteEdge(Observation observation)
        {
            throw new AssertionError("unexpected route action");
        }

        @Override
        public void replan(WebWalkSession session, String reason)
        {
            throw new AssertionError("unexpected replan");
        }

        @Override
        public void awaitChange(Observation observation)
        {
        }

        @Override
        public void finish(WalkerState state, String reason)
        {
            finishedReason = reason;
        }
    }

    private static final class RouteActionRuntime implements WebWalkRuntime
    {
        private final List<String> events = new ArrayList<>();
        private int observations;
        private int dispatches;
        private WebWalkSession session;

        @Override
        public Observation observe(WebWalkSession currentSession)
        {
            events.add("observe");
            session = currentSession;
            switch (observations++)
            {
                case 0:
                    return ready(100, START, 1, 0, point(10), 3, false);
                case 1:
                    assertEquals("the first accepted movement must establish ownership",
                            point(10), currentSession.getCheckpoint());
                    assertEquals(3, currentSession.getCheckpointPathIndex());
                    return ready(101, START, 1, 0, point(12), 4, true);
                case 2:
                    return ready(102, point(1), 1, 1, point(12), 4, false);
                default:
                    return new Observation(103, point(2), Status.ARRIVED,
                            route(1), 2, null, -1, false);
            }
        }

        @Override
        public DispatchResult dispatchMinimap(WorldPoint requestedTarget, int pathIndex,
                                               boolean redispatch)
        {
            events.add("dispatch");
            if (dispatches++ == 0)
            {
                assertEquals(point(10), requestedTarget);
                assertEquals(3, pathIndex);
            }
            else
            {
                assertEquals(point(12), requestedTarget);
                assertEquals(4, pathIndex);
            }
            assertEquals(false, redispatch);
            return DispatchResult.accepted(requestedTarget);
        }

        @Override
        public ActionResult interactRouteEdge(Observation observation)
        {
            events.add("interact");
            assertNull("route actions must clear the active movement checkpoint before input",
                    session.getCheckpoint());
            return ActionResult.ACCEPTED;
        }

        @Override
        public void replan(WebWalkSession currentSession, String reason)
        {
            throw new AssertionError("unexpected replan");
        }

        @Override
        public void awaitChange(Observation observation)
        {
            events.add("await");
        }

        @Override
        public void finish(WalkerState state, String reason)
        {
            events.add("finish");
        }
    }
}
