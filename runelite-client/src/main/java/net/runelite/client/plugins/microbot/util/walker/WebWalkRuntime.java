package net.runelite.client.plugins.microbot.util.walker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

/** Runtime boundary for the single-action WebWalker executor. */
public interface WebWalkRuntime
{
    enum Status
    {
        READY,
        WAITING_FOR_PATH,
        ARRIVED,
        UNREACHABLE,
        CANCELLED
    }

    enum ActionResult
    {
        ACCEPTED,
        RETRY,
        FAILED
    }

    Observation observe(WebWalkSession session);

    DispatchResult dispatchMinimap(WorldPoint requestedTarget, int pathIndex, boolean redispatch);

    ActionResult interactRouteEdge(Observation observation);

    void replan(WebWalkSession session, String reason);

    void awaitChange(Observation observation);

    void finish(WalkerState state, String reason);

    final class RouteSnapshot
    {
        private final long generation;
        private final List<WorldPoint> rawPath;
        private final List<WorldPoint> walkPath;

        public RouteSnapshot(long generation, List<WorldPoint> rawPath, List<WorldPoint> walkPath)
        {
            this.generation = generation;
            this.rawPath = immutableCopy(rawPath);
            this.walkPath = immutableCopy(walkPath);
        }

        public long getGeneration()
        {
            return generation;
        }

        public List<WorldPoint> getRawPath()
        {
            return rawPath;
        }

        public List<WorldPoint> getWalkPath()
        {
            return walkPath;
        }

        private static List<WorldPoint> immutableCopy(List<WorldPoint> points)
        {
            if (points == null || points.isEmpty())
            {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    final class Observation
    {
        private final int tick;
        private final WorldPoint player;
        private final Status status;
        private final RouteSnapshot route;
        private final int pathIndex;
        private final WorldPoint clickTarget;
        private final int clickPathIndex;
        private final boolean routeActionAvailable;
        private final boolean runEnabled;

        public Observation(int tick, WorldPoint player, Status status, RouteSnapshot route,
                           int pathIndex, WorldPoint clickTarget, int clickPathIndex,
                           boolean routeActionAvailable)
        {
            this(tick, player, status, route, pathIndex, clickTarget, clickPathIndex,
                    routeActionAvailable, false);
        }

        public Observation(int tick, WorldPoint player, Status status, RouteSnapshot route,
                           int pathIndex, WorldPoint clickTarget, int clickPathIndex,
                           boolean routeActionAvailable, boolean runEnabled)
        {
            this.tick = tick;
            this.player = player;
            this.status = Objects.requireNonNull(status, "status");
            this.route = route;
            this.pathIndex = pathIndex;
            this.clickTarget = clickTarget;
            this.clickPathIndex = clickPathIndex;
            this.routeActionAvailable = routeActionAvailable;
            this.runEnabled = runEnabled;
        }

        public int getTick()
        {
            return tick;
        }

        public WorldPoint getPlayer()
        {
            return player;
        }

        public Status getStatus()
        {
            return status;
        }

        public RouteSnapshot getRoute()
        {
            return route;
        }

        public int getPathIndex()
        {
            return pathIndex;
        }

        public WorldPoint getClickTarget()
        {
            return clickTarget;
        }

        public int getClickPathIndex()
        {
            return clickPathIndex;
        }

        public boolean isRouteActionAvailable()
        {
            return routeActionAvailable;
        }

        public boolean isRunEnabled()
        {
            return runEnabled;
        }
    }

    final class DispatchResult
    {
        private final boolean accepted;
        private final WorldPoint actualTarget;

        private DispatchResult(boolean accepted, WorldPoint actualTarget)
        {
            this.accepted = accepted;
            this.actualTarget = actualTarget;
        }

        public static DispatchResult accepted(WorldPoint actualTarget)
        {
            return new DispatchResult(true, Objects.requireNonNull(actualTarget, "actualTarget"));
        }

        public static DispatchResult rejected()
        {
            return new DispatchResult(false, null);
        }

        public boolean isAccepted()
        {
            return accepted;
        }

        public WorldPoint getActualTarget()
        {
            return actualTarget;
        }
    }
}
