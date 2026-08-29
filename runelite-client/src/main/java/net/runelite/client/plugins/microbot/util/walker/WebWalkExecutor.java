package net.runelite.client.plugins.microbot.util.walker;

import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

/**
 * Deterministic executor for one walk session.
 *
 * <p>Every cycle observes copied state, chooses at most one action, then waits for a new game
 * observation. An accepted click is therefore never followed by more scans or another click from
 * the same stale route snapshot.</p>
 */
public final class WebWalkExecutor
{
    static final int CHECKPOINT_ARRIVAL_DISTANCE = 1;
    static final int WALK_CHECKPOINT_HANDOFF_DISTANCE = 6;
    static final int RUN_CHECKPOINT_HANDOFF_DISTANCE = 8;
    static final int NO_PROGRESS_TICKS = 2;
    static final int MINIMAP_COMMAND_START_GRACE_TICKS = 4;
    static final int MAX_REDISPATCHES = 1;
    static final int MAX_REJECTED_DISPATCHES = 2;
    static final int MAX_ROUTE_ACTION_FAILURES = 2;
    static final int MAX_REPLANS = 3;
    static final int MAX_EXECUTOR_ITERATIONS = 5_000;

    public WalkerState walk(WebWalkSession session, WebWalkRuntime runtime)
    {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(runtime, "runtime");

        try
        {
            int iteration = 0;
            while (!Thread.currentThread().isInterrupted() && iteration++ < MAX_EXECUTOR_ITERATIONS)
            {
                WebWalkRuntime.Observation observation = runtime.observe(session);
                Decision decision = decide(session, observation);
                switch (decision.getType())
                {
                    case ARRIVED:
                        runtime.finish(WalkerState.ARRIVED, decision.getReason());
                        return WalkerState.ARRIVED;
                    case UNREACHABLE:
                        runtime.finish(WalkerState.UNREACHABLE, decision.getReason());
                        return WalkerState.UNREACHABLE;
                    case EXIT:
                        runtime.finish(WalkerState.EXIT, decision.getReason());
                        return WalkerState.EXIT;
                    case CLICK_MINIMAP:
                        WebWalkRuntime.DispatchResult dispatch = runtime.dispatchMinimap(
                                decision.getTarget(), decision.getPathIndex(), decision.isRedispatch());
                        if (dispatch.isAccepted())
                        {
                            int actualPathIndex = session.resolveActualPathIndex(
                                    dispatch.getActualTarget(), decision.getPathIndex());
                            session.recordMinimapDispatch(observation.getTick(), decision.getTarget(),
                                    dispatch.getActualTarget(), actualPathIndex, decision.isRedispatch());
                        }
                        else
                        {
                            session.recordRejectedDispatch();
                        }
                        runtime.awaitChange(observation);
                        break;
                    case INTERACT_ROUTE_EDGE:
                        WebWalkRuntime.ActionResult actionResult = runtime.interactRouteEdge(observation);
                        session.recordRouteActionResult(actionResult, observation.getTick());
                        runtime.awaitChange(observation);
                        break;
                    case REPLAN:
                        if (session.getReplanCount() >= MAX_REPLANS)
                        {
                            runtime.finish(WalkerState.UNREACHABLE, "replans-exhausted");
                            return WalkerState.UNREACHABLE;
                        }
                        session.beginReplan();
                        runtime.replan(session, decision.getReason());
                        break;
                    case WAIT:
                    default:
                        runtime.awaitChange(observation);
                        break;
                }
            }
        }
        catch (RuntimeException ex)
        {
            WebWalkLog.executorFailure(ex.getClass().getSimpleName(), session.getTarget());
            runtime.finish(WalkerState.EXIT, "runtime-failure");
            return WalkerState.EXIT;
        }

        boolean interrupted = Thread.currentThread().isInterrupted();
        if (interrupted)
        {
            Thread.currentThread().interrupt();
        }
        runtime.finish(WalkerState.EXIT, interrupted ? "interrupted" : "iteration-limit");
        return WalkerState.EXIT;
    }

    Decision decide(WebWalkSession session, WebWalkRuntime.Observation observation)
    {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(observation, "observation");

        switch (observation.getStatus())
        {
            case ARRIVED:
                return Decision.terminal(DecisionType.ARRIVED, "arrived");
            case UNREACHABLE:
                return Decision.terminal(DecisionType.UNREACHABLE, "unreachable");
            case CANCELLED:
                return Decision.terminal(DecisionType.EXIT, "cancelled");
            case WAITING_FOR_PATH:
                return Decision.waitFor("pathfinder");
            case READY:
            default:
                break;
        }

        WebWalkRuntime.RouteSnapshot route = observation.getRoute();
        if (route == null)
        {
            return Decision.waitFor("route-snapshot");
        }
        session.installRoute(route);
        boolean progressed = session.observe(observation.getTick(), observation.getPlayer(),
                observation.getPathIndex());

        if (observation.isRouteActionAvailable())
        {
            session.clearCheckpoint();
            if (session.isRouteActionPending())
            {
                if (session.ticksWithoutRouteActionProgress(observation.getTick()) < NO_PROGRESS_TICKS)
                {
                    return Decision.waitFor("route-action-progress");
                }
                session.expireRouteActionAttempt();
            }
            if (session.getRouteActionFailureCount() >= MAX_ROUTE_ACTION_FAILURES)
            {
                return Decision.replan("route-action-failed");
            }
            return Decision.interact("exact-route-edge");
        }
        if (session.isRouteActionPending())
        {
            session.recordRouteActionResolved();
        }

        WorldPoint checkpoint = session.getCheckpoint();
        if (checkpoint != null)
        {
            WorldPoint player = observation.getPlayer();
            boolean passedCheckpoint = session.getCheckpointPathIndex() >= 0
                    && observation.getPathIndex() > session.getCheckpointPathIndex();
            boolean reachedCheckpoint = player != null && player.getPlane() == checkpoint.getPlane()
                    && player.distanceTo2D(checkpoint) <= CHECKPOINT_ARRIVAL_DISTANCE;
            int handoffDistance = observation.isRunEnabled()
                    ? RUN_CHECKPOINT_HANDOFF_DISTANCE : WALK_CHECKPOINT_HANDOFF_DISTANCE;
            if (passedCheckpoint || reachedCheckpoint
                    || session.isCheckpointReadyForHandoff(player, handoffDistance))
            {
                session.clearCheckpoint();
            }
            else if (progressed || session.ticksWithoutCommandProgress(observation.getTick())
                    < MINIMAP_COMMAND_START_GRACE_TICKS)
            {
                return Decision.waitFor("checkpoint-progress");
            }
            else if (session.getRedispatchCount() >= MAX_REDISPATCHES)
            {
                return Decision.replan("checkpoint-no-progress");
            }
            else if (observation.getClickTarget() != null)
            {
                return Decision.click(observation.getClickTarget(), observation.getClickPathIndex(),
                        true, "checkpoint-redispatch");
            }
            else
            {
                return Decision.replan("checkpoint-no-forward-target");
            }
        }

        if (session.getRejectedDispatchCount() >= MAX_REJECTED_DISPATCHES)
        {
            return Decision.replan("minimap-dispatch-rejected");
        }
        if (observation.getClickTarget() != null)
        {
            session.clearNoCandidate();
            return Decision.click(observation.getClickTarget(), observation.getClickPathIndex(),
                    false, "forward-route");
        }
        if (session.ticksWithoutCandidate(observation.getTick()) >= NO_PROGRESS_TICKS)
        {
            return Decision.replan("no-forward-target");
        }
        return Decision.waitFor("no-forward-target-yet");
    }

    enum DecisionType
    {
        ARRIVED,
        WAIT,
        CLICK_MINIMAP,
        INTERACT_ROUTE_EDGE,
        REPLAN,
        UNREACHABLE,
        EXIT
    }

    static final class Decision
    {
        private final DecisionType type;
        private final WorldPoint target;
        private final int pathIndex;
        private final boolean redispatch;
        private final String reason;

        private Decision(DecisionType type, WorldPoint target, int pathIndex,
                         boolean redispatch, String reason)
        {
            this.type = type;
            this.target = target;
            this.pathIndex = pathIndex;
            this.redispatch = redispatch;
            this.reason = reason;
        }

        static Decision terminal(DecisionType type, String reason)
        {
            return new Decision(type, null, -1, false, reason);
        }

        static Decision waitFor(String reason)
        {
            return terminal(DecisionType.WAIT, reason);
        }

        static Decision click(WorldPoint target, int pathIndex, boolean redispatch, String reason)
        {
            return new Decision(DecisionType.CLICK_MINIMAP, target, pathIndex, redispatch, reason);
        }

        static Decision interact(String reason)
        {
            return terminal(DecisionType.INTERACT_ROUTE_EDGE, reason);
        }

        static Decision replan(String reason)
        {
            return terminal(DecisionType.REPLAN, reason);
        }

        DecisionType getType()
        {
            return type;
        }

        WorldPoint getTarget()
        {
            return target;
        }

        int getPathIndex()
        {
            return pathIndex;
        }

        boolean isRedispatch()
        {
            return redispatch;
        }

        String getReason()
        {
            return reason;
        }
    }
}
