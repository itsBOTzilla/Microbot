package net.runelite.client.plugins.microbot.util.walker;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

/** Owns all mutable command and progress state for one blocking walk call. */
public final class WebWalkSession
{
    private final WorldPoint target;
    private final int arrivalDistance;
    private final long targetGeneration;
    private long routeGeneration = Long.MIN_VALUE;
    private List<WorldPoint> rawPath = Collections.emptyList();
    private WorldPoint lastPlayer;
    private int lastPathIndex = -1;
    private int lastObservedProgressTick = Integer.MIN_VALUE;
    private WorldPoint checkpoint;
    private int checkpointPathIndex = -1;
    private int checkpointInitialDistance = -1;
    private WorldPoint activeTarget;
    private WebWalkRuntime.DispatchMethod activeMethod = WebWalkRuntime.DispatchMethod.NONE;
    private int dispatchTick = Integer.MIN_VALUE;
    private int distanceAtDispatch = -1;
    private int lastObservedDistance = -1;
    private int lastProgressTick = Integer.MIN_VALUE;
    private int commandTick = Integer.MIN_VALUE;
    private int redispatchCount;
    private int rejectedDispatchCount;
    private int routeActionFailureCount;
    private boolean routeActionPending;
    private int routeActionCommandTick = Integer.MIN_VALUE;
    private int replanCount;
    private int noCandidateSinceTick = Integer.MIN_VALUE;

    public WebWalkSession(WorldPoint target, int arrivalDistance)
    {
        this(target, arrivalDistance, Long.MIN_VALUE);
    }

    public WebWalkSession(WorldPoint target, int arrivalDistance, long targetGeneration)
    {
        this.target = Objects.requireNonNull(target, "target");
        if (arrivalDistance < 0)
        {
            throw new IllegalArgumentException("arrivalDistance must be >= 0");
        }
        this.arrivalDistance = arrivalDistance;
        this.targetGeneration = targetGeneration;
    }

    public WorldPoint getTarget()
    {
        return target;
    }

    public int getArrivalDistance()
    {
        return arrivalDistance;
    }

    public long getTargetGeneration()
    {
        return targetGeneration;
    }

    public long getRouteGeneration()
    {
        return routeGeneration;
    }

    public List<WorldPoint> getRawPath()
    {
        return rawPath;
    }

    public WorldPoint getCheckpoint()
    {
        return checkpoint;
    }

    public int getCheckpointPathIndex()
    {
        return checkpointPathIndex;
    }

    public WorldPoint getActiveTarget()
    {
        return activeTarget;
    }

    public WebWalkRuntime.DispatchMethod getActiveMethod()
    {
        return activeMethod;
    }

    public int getDispatchTick()
    {
        return dispatchTick;
    }

    public int getDistanceAtDispatch()
    {
        return distanceAtDispatch;
    }

    public int getLastObservedDistance()
    {
        return lastObservedDistance;
    }

    public int getLastProgressTick()
    {
        return lastProgressTick;
    }

    boolean isCheckpointReached(WorldPoint player, int reachedDistance)
    {
        return checkpoint != null && player != null
                && checkpoint.getPlane() == player.getPlane()
                && player.distanceTo2D(checkpoint) <= reachedDistance;
    }

    boolean hasApproachedCheckpointForHandoff(WorldPoint player, int handoffDistance)
    {
        return checkpoint != null && player != null
                && checkpoint.getPlane() == player.getPlane()
                && checkpointInitialDistance > handoffDistance
                && player.distanceTo2D(checkpoint) <= handoffDistance;
    }

    public int getRedispatchCount()
    {
        return redispatchCount;
    }

    public int getRejectedDispatchCount()
    {
        return rejectedDispatchCount;
    }

    public int getRouteActionFailureCount()
    {
        return routeActionFailureCount;
    }

    public int getReplanCount()
    {
        return replanCount;
    }

    public int getLastPathIndex()
    {
        return lastPathIndex;
    }

    public void installRoute(WebWalkRuntime.RouteSnapshot route)
    {
        Objects.requireNonNull(route, "route");
        if (routeGeneration == route.getGeneration())
        {
            return;
        }
        routeGeneration = route.getGeneration();
        rawPath = route.getRawPath();
        lastPlayer = null;
        lastPathIndex = -1;
        lastObservedProgressTick = Integer.MIN_VALUE;
        clearPendingCommand();
        noCandidateSinceTick = Integer.MIN_VALUE;
    }

    public boolean observe(int tick, WorldPoint player, int pathIndex)
    {
        boolean initialized = lastPlayer != null;
        boolean tileChanged = initialized && player != null && !player.equals(lastPlayer);
        boolean pathAdvanced = initialized && pathIndex > lastPathIndex;
        boolean progressed = tileChanged || pathAdvanced;
        lastPlayer = player;
        lastPathIndex = Math.max(lastPathIndex, pathIndex);
        if (!initialized || progressed)
        {
            lastObservedProgressTick = tick;
        }
        observeActiveTargetDistance(tick, player);
        if (progressed)
        {
            redispatchCount = 0;
            routeActionFailureCount = 0;
            routeActionPending = false;
            routeActionCommandTick = Integer.MIN_VALUE;
        }
        return progressed;
    }

    public void recordMinimapDispatch(int tick, WorldPoint requestedTarget, WorldPoint actualTarget,
                                      int pathIndex, boolean redispatch)
    {
        recordMovementDispatch(tick, requestedTarget, actualTarget, pathIndex, redispatch,
                WebWalkRuntime.DispatchMethod.MINIMAP);
    }

    public void recordMovementDispatch(int tick, WorldPoint requestedTarget, WorldPoint actualTarget,
                                       int pathIndex, boolean redispatch,
                                       WebWalkRuntime.DispatchMethod method)
    {
        Objects.requireNonNull(requestedTarget, "requestedTarget");
        checkpoint = Objects.requireNonNull(actualTarget, "actualTarget");
        checkpointPathIndex = pathIndex;
        checkpointInitialDistance = lastPlayer == null || lastPlayer.getPlane() != checkpoint.getPlane()
                ? -1 : lastPlayer.distanceTo2D(checkpoint);
        activeTarget = checkpoint;
        activeMethod = Objects.requireNonNull(method, "method");
        dispatchTick = tick;
        distanceAtDispatch = checkpointInitialDistance;
        lastObservedDistance = checkpointInitialDistance;
        lastProgressTick = tick;
        commandTick = tick;
        if (lastObservedProgressTick == Integer.MIN_VALUE)
        {
            lastObservedProgressTick = tick;
        }
        redispatchCount = redispatch ? redispatchCount + 1 : 0;
        rejectedDispatchCount = 0;
        noCandidateSinceTick = Integer.MIN_VALUE;
    }

    public void recordRejectedDispatch()
    {
        rejectedDispatchCount++;
    }

    public int resolveActualPathIndex(WorldPoint actualTarget, int requestedPathIndex)
    {
        if (actualTarget == null || rawPath.isEmpty())
        {
            return requestedPathIndex;
        }
        int exact = rawPath.lastIndexOf(actualTarget);
        if (exact >= 0)
        {
            return exact;
        }
        int bestIndex = requestedPathIndex;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < rawPath.size(); index++)
        {
            WorldPoint point = rawPath.get(index);
            if (point == null || point.getPlane() != actualTarget.getPlane())
            {
                continue;
            }
            int distance = point.distanceTo2D(actualTarget);
            if (distance < bestDistance || distance == bestDistance && index > bestIndex)
            {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestDistance <= 2 ? bestIndex : requestedPathIndex;
    }

    public void recordRouteActionResult(WebWalkRuntime.ActionResult result, int tick)
    {
        clearCheckpoint();
        if (result == WebWalkRuntime.ActionResult.FAILED)
        {
            routeActionFailureCount++;
            routeActionPending = false;
            routeActionCommandTick = Integer.MIN_VALUE;
            return;
        }
        routeActionPending = true;
        routeActionCommandTick = tick;
    }

    public boolean isRouteActionPending()
    {
        return routeActionPending;
    }

    public int ticksWithoutRouteActionProgress(int tick)
    {
        return routeActionCommandTick == Integer.MIN_VALUE
                ? 0 : Math.max(0, tick - routeActionCommandTick);
    }

    public void expireRouteActionAttempt()
    {
        routeActionFailureCount++;
        routeActionPending = false;
        routeActionCommandTick = Integer.MIN_VALUE;
    }

    public void recordRouteActionResolved()
    {
        routeActionFailureCount = 0;
        routeActionPending = false;
        routeActionCommandTick = Integer.MIN_VALUE;
    }

    public int ticksWithoutCommandProgress(int tick)
    {
        int baseline = Math.max(commandTick, lastObservedProgressTick);
        return baseline == Integer.MIN_VALUE ? 0 : Math.max(0, tick - baseline);
    }

    public int ticksWithoutActiveTargetProgress(int tick)
    {
        int baseline = Math.max(dispatchTick, lastProgressTick);
        return baseline == Integer.MIN_VALUE ? 0 : Math.max(0, tick - baseline);
    }

    public boolean isActiveTargetNear(WorldPoint player, int distance)
    {
        return activeTarget != null && player != null
                && activeTarget.getPlane() == player.getPlane()
                && player.distanceTo2D(activeTarget) <= distance;
    }

    public boolean isActiveMovementHealthy(int tick, boolean moving, int progressTimeoutTicks)
    {
        return activeTarget != null && moving
                && ticksWithoutActiveTargetProgress(tick) <= progressTimeoutTicks;
    }

    public boolean hasDispatchIntervalElapsed(int tick, int minimumTicks)
    {
        return dispatchTick == Integer.MIN_VALUE || tick - dispatchTick >= minimumTicks;
    }

    public int ticksWithoutCandidate(int tick)
    {
        if (noCandidateSinceTick == Integer.MIN_VALUE)
        {
            noCandidateSinceTick = tick;
            return 0;
        }
        return Math.max(0, tick - noCandidateSinceTick);
    }

    public void clearNoCandidate()
    {
        noCandidateSinceTick = Integer.MIN_VALUE;
    }

    public void beginReplan()
    {
        replanCount++;
        routeGeneration = Long.MIN_VALUE;
        rawPath = Collections.emptyList();
        lastPlayer = null;
        lastPathIndex = -1;
        lastObservedProgressTick = Integer.MIN_VALUE;
        clearPendingCommand();
        noCandidateSinceTick = Integer.MIN_VALUE;
    }

    public void clearCheckpoint()
    {
        clearMovementCommand();
    }

    void releaseCheckpoint()
    {
        checkpoint = null;
        checkpointPathIndex = -1;
        checkpointInitialDistance = -1;
        commandTick = Integer.MIN_VALUE;
        redispatchCount = 0;
    }

    private void clearMovementCommand()
    {
        releaseCheckpoint();
        activeTarget = null;
        activeMethod = WebWalkRuntime.DispatchMethod.NONE;
        dispatchTick = Integer.MIN_VALUE;
        distanceAtDispatch = -1;
        lastObservedDistance = -1;
        lastProgressTick = Integer.MIN_VALUE;
    }

    private void observeActiveTargetDistance(int tick, WorldPoint player)
    {
        if (activeTarget == null || player == null || activeTarget.getPlane() != player.getPlane())
        {
            return;
        }
        int distance = player.distanceTo2D(activeTarget);
        if (lastObservedDistance < 0 || distance < lastObservedDistance)
        {
            lastProgressTick = tick;
        }
        lastObservedDistance = distance;
    }

    private void clearPendingCommand()
    {
        clearMovementCommand();
        rejectedDispatchCount = 0;
        routeActionFailureCount = 0;
        routeActionPending = false;
        routeActionCommandTick = Integer.MIN_VALUE;
    }
}
