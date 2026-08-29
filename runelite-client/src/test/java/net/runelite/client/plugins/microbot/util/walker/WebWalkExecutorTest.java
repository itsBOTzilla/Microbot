package net.runelite.client.plugins.microbot.util.walker;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
        session.recordMinimapDispatch(100, point(1), point(1), 1, false);

        WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                ready(101, point(2), 1, 2, point(12), 12, false));

        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, decision.getType());
        assertEquals(point(12), decision.getTarget());
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
    public void acceptedDispatchAwaitsBeforeTakingAnotherObservation()
    {
        RecordingRuntime runtime = new RecordingRuntime();

        WalkerState result = new WebWalkExecutor().walk(new WebWalkSession(GOAL, 0), runtime);

        assertEquals(WalkerState.ARRIVED, result);
        assertEquals(Arrays.asList("observe", "dispatch", "await", "observe", "finish"),
                runtime.events);
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
        return new WebWalkRuntime.Observation(tick, player, WebWalkRuntime.Status.READY,
                route(generation), pathIndex, clickTarget, clickIndex, routeAction);
    }

    private static WebWalkRuntime.RouteSnapshot route(long generation)
    {
        return new WebWalkRuntime.RouteSnapshot(generation,
                Arrays.asList(point(0), point(1), point(2), point(10), point(12)),
                Arrays.asList(point(0), point(12)));
    }

    private static WorldPoint point(int x)
    {
        return new WorldPoint(3200 + x, 3200, 0);
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
}
