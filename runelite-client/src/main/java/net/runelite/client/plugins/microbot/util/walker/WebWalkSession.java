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
    private long routeGeneration = Long.MIN_VALUE;
    private List<WorldPoint> rawPath = Collections.emptyList();
    private WorldPoint lastPlayer;
    private int lastPathIndex = -1;
    private int lastProgressTick = Integer.MIN_VALUE;
    private WorldPoint checkpoint;
    private int checkpointPathIndex = -1;
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
        this.target = Objects.requireNonNull(target, "target");
        if (arrivalDistance < 0)
        {
            throw new IllegalArgumentException("arrivalDistance must be >= 0");
        }
        this.arrivalDistance = arrivalDistance;
    }

    public WorldPoint getTarget()
    {
        return target;
    }

    public int getArrivalDistance()
    {
        return arrivalDistance;
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
        lastProgressTick = Integer.MIN_VALUE;
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
            lastProgressTick = tick;
        }
        if (progressed)
        {
            redispatchCount = 0;
            rejectedDispatchCount = 0;
            routeActionFailureCount = 0;
            routeActionPending = false;
            routeActionCommandTick = Integer.MIN_VALUE;
        }
        return progressed;
    }

    public void recordMinimapDispatch(int tick, WorldPoint requestedTarget, WorldPoint actualTarget,
                                      int pathIndex, boolean redispatch)
    {
        Objects.requireNonNull(requestedTarget, "requestedTarget");
        checkpoint = Objects.requireNonNull(actualTarget, "actualTarget");
        checkpointPathIndex = pathIndex;
        commandTick = tick;
        if (lastProgressTick == Integer.MIN_VALUE)
        {
            lastProgressTick = tick;
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
        int baseline = Math.max(commandTick, lastProgressTick);
        return baseline == Integer.MIN_VALUE ? 0 : Math.max(0, tick - baseline);
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
        lastProgressTick = Integer.MIN_VALUE;
        clearPendingCommand();
        noCandidateSinceTick = Integer.MIN_VALUE;
    }

    public void clearCheckpoint()
    {
        checkpoint = null;
        checkpointPathIndex = -1;
        commandTick = Integer.MIN_VALUE;
        redispatchCount = 0;
    }

    private void clearPendingCommand()
    {
        clearCheckpoint();
        rejectedDispatchCount = 0;
        routeActionFailureCount = 0;
        routeActionPending = false;
        routeActionCommandTick = Integer.MIN_VALUE;
    }
}
