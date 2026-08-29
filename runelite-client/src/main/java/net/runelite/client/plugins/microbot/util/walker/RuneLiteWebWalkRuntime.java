package net.runelite.client.plugins.microbot.util.walker;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.Pathfinder;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/** Production Microbot adapter for {@link WebWalkExecutor}. */
public final class RuneLiteWebWalkRuntime implements WebWalkRuntime
{
    private static final int MINIMAP_ROUTE_RADIUS = 12;
    private static final int ROUTE_EDGE_ACTION_DISTANCE = 3;
    private static final int STATE_WAIT_MS = 750;
    private static final long PATHFINDER_TIMEOUT_NANOS = 10_000_000_000L;
    private static final long LOGIN_GRACE_NANOS = 1_500_000_000L;

    private final WorldPoint target;
    private final int arrivalDistance;
    private Pathfinder observedPathfinder;
    private Pathfinder invalidatedPathfinder;
    private long routeGeneration;
    private long pathfinderWaitStartedNanos;
    private long loginMissingSinceNanos;
    private int routeActionIndex = -1;
    private int lastObservedTick;
    private int pathRemaining;
    private List<WorldPoint> lastRawPath = Collections.emptyList();

    public RuneLiteWebWalkRuntime(WorldPoint target, int arrivalDistance)
    {
        this.target = Objects.requireNonNull(target, "target");
        this.arrivalDistance = Math.max(0, arrivalDistance);
    }

    @Override
    public Observation observe(WebWalkSession session)
    {
        ClientSample sample = readClientSample();
        lastObservedTick = sample.tick;
        if (Thread.currentThread().isInterrupted()
                || Rs2Walker.getCurrentTarget() == null
                || !target.equals(Rs2Walker.getCurrentTarget()))
        {
            return terminal(sample, Status.CANCELLED);
        }
        if (!sample.loggedIn || sample.player == null)
        {
            long now = System.nanoTime();
            if (loginMissingSinceNanos == 0L)
            {
                loginMissingSinceNanos = now;
            }
            return now - loginMissingSinceNanos <= LOGIN_GRACE_NANOS
                    ? waiting(sample) : terminal(sample, Status.CANCELLED);
        }
        loginMissingSinceNanos = 0L;

        Pathfinder pathfinder = Rs2PathApi.getPathfinder();
        if (pathfinder == null || pathfinder == invalidatedPathfinder || !pathfinder.isDone())
        {
            long now = System.nanoTime();
            if (pathfinderWaitStartedNanos == 0L)
            {
                pathfinderWaitStartedNanos = now;
            }
            if (now - pathfinderWaitStartedNanos > PATHFINDER_TIMEOUT_NANOS)
            {
                return terminal(sample, Status.UNREACHABLE);
            }
            return waiting(sample);
        }
        pathfinderWaitStartedNanos = 0L;
        invalidatedPathfinder = null;
        if (pathfinder != observedPathfinder)
        {
            observedPathfinder = pathfinder;
            routeGeneration++;
        }

        List<WorldPoint> rawPath = safePathCopy(pathfinder.getPath());
        List<WorldPoint> walkPath = safePathCopy(pathfinder.getWalkablePath());
        lastRawPath = rawPath;
        RouteSnapshot route = new RouteSnapshot(routeGeneration, rawPath, walkPath);
        if (Rs2Walker.runtimeArrived(target, arrivalDistance, rawPath, walkPath))
        {
            return new Observation(sample.tick, sample.player, Status.ARRIVED,
                    route, -1, null, -1, false, sample.runEnabled);
        }
        if (rawPath.isEmpty())
        {
            return new Observation(sample.tick, sample.player, Status.UNREACHABLE,
                    route, -1, null, -1, false, sample.runEnabled);
        }

        Map<WorldPoint, Integer> reachableMap = Rs2Tile.getReachableTilesFromTile(
                sample.player, MINIMAP_ROUTE_RADIUS + 2);
        Set<WorldPoint> reachable = reachableMap.keySet();
        int currentIndex = closestForwardIndex(rawPath, sample.player, reachable);
        if (session.getRouteGeneration() == routeGeneration)
        {
            currentIndex = Math.max(currentIndex, session.getLastPathIndex());
        }
        if (currentIndex < 0)
        {
            return new Observation(sample.tick, sample.player, Status.READY,
                    route, -1, null, -1, false, sample.runEnabled);
        }
        pathRemaining = Math.max(0, rawPath.size() - currentIndex - 1);
        routeActionIndex = routeActionIndex(rawPath, currentIndex, sample.player, reachable);
        boolean routeActionAvailable = routeActionIndex >= 0;
        ForwardCandidate candidate = selectForwardCandidate(rawPath, sample.player, reachable,
                MINIMAP_ROUTE_RADIUS,
                index -> Rs2Walker.isCatalogBackedTransportSegment(rawPath, index), currentIndex);
        if (routeActionAvailable)
        {
            candidate = null;
        }
        return new Observation(sample.tick, sample.player, Status.READY, route, currentIndex,
                candidate == null ? null : candidate.target,
                candidate == null ? -1 : candidate.pathIndex, routeActionAvailable,
                sample.runEnabled);
    }

    @Override
    public DispatchResult dispatchMinimap(WorldPoint requestedTarget, int pathIndex, boolean redispatch)
    {
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null)
        {
            return DispatchResult.rejected();
        }
        Rs2Walker.manageRunEnergy(pathRemaining);
        DispatchResult result = Rs2Walker.dispatchMiniMapTarget(
                lastRawPath, requestedTarget, player, pathIndex, MINIMAP_ROUTE_RADIUS - 1);
        WebWalkLog.minimapDispatch(requestedTarget, result.getActualTarget(), pathIndex,
                lastObservedTick, redispatch);
        if (result.isAccepted())
        {
            Rs2Walker.markFirstMovementClick("first_minimap_click", target, player,
                    "requested=" + requestedTarget + " actual=" + result.getActualTarget());
        }
        return result;
    }

    @Override
    public ActionResult interactRouteEdge(Observation observation)
    {
        RouteSnapshot route = observation.getRoute();
        if (route == null || routeActionIndex < 0)
        {
            return ActionResult.FAILED;
        }
        return Rs2Walker.runtimeHandleRouteEdge(route.getRawPath(), routeActionIndex)
                ? ActionResult.ACCEPTED : ActionResult.FAILED;
    }

    @Override
    public void replan(WebWalkSession session, String reason)
    {
        WebWalkLog.executorReplan(reason, session.getReplanCount(),
                Rs2Player.getWorldLocation(), target);
        invalidatedPathfinder = Rs2PathApi.getPathfinder();
        observedPathfinder = null;
        pathfinderWaitStartedNanos = System.nanoTime();
        Rs2Walker.recalculatePath();
    }

    @Override
    public void awaitChange(Observation observation)
    {
        Pathfinder beforePathfinder = Rs2PathApi.getPathfinder();
        boolean beforeDone = beforePathfinder != null && beforePathfinder.isDone();
        int beforeTick = observation.getTick();
        WorldPoint beforePlayer = observation.getPlayer();
        sleepUntil(() -> Thread.currentThread().isInterrupted()
                        || Rs2Walker.getCurrentTarget() == null
                        || !target.equals(Rs2Walker.getCurrentTarget())
                        || currentTick() != beforeTick
                        || !Objects.equals(Rs2Player.getWorldLocation(), beforePlayer)
                        || Rs2PathApi.getPathfinder() != beforePathfinder
                        || !beforeDone && beforePathfinder != null && beforePathfinder.isDone(),
                STATE_WAIT_MS);
    }

    @Override
    public void finish(WalkerState state, String reason)
    {
        if (target.equals(Rs2Walker.getCurrentTarget()))
        {
            Rs2Walker.setTarget(null, "webwalk-executor:" + reason);
        }
    }

    static ForwardCandidate selectForwardCandidate(List<WorldPoint> path, WorldPoint player,
                                                    Set<WorldPoint> reachable, int maxEuclidean,
                                                    IntPredicate transportEdgeAtIndex)
    {
        if (path == null || path.isEmpty() || player == null || reachable == null || reachable.isEmpty())
        {
            return null;
        }
        int startIndex = closestForwardIndex(path, player, reachable);
        return selectForwardCandidate(path, player, reachable, maxEuclidean,
                transportEdgeAtIndex, startIndex);
    }

    static ForwardCandidate selectForwardCandidate(List<WorldPoint> path, WorldPoint player,
                                                    Set<WorldPoint> reachable, int maxEuclidean,
                                                    IntPredicate transportEdgeAtIndex, int startIndex)
    {
        if (path == null || path.isEmpty() || player == null || reachable == null
                || reachable.isEmpty() || startIndex < 0 || startIndex >= path.size())
        {
            return null;
        }
        int maxDistanceSquared = maxEuclidean * maxEuclidean;
        ForwardCandidate best = null;
        for (int index = startIndex; index < path.size(); index++)
        {
            WorldPoint point = path.get(index);
            if (point == null || point.getPlane() != player.getPlane()
                    || euclideanSquared(point, player) > maxDistanceSquared)
            {
                break;
            }
            if (!point.equals(player) && reachable.contains(point))
            {
                best = new ForwardCandidate(point, index);
            }
            if (index < path.size() - 1 && transportEdgeAtIndex != null
                    && transportEdgeAtIndex.test(index))
            {
                break;
            }
        }
        return best;
    }

    static int closestForwardIndex(List<WorldPoint> path, WorldPoint player, Set<WorldPoint> reachable)
    {
        if (path == null || path.isEmpty() || player == null)
        {
            return -1;
        }
        Optional<Integer> reachableIndex = IntStream.range(0, path.size())
                .boxed()
                .filter(index -> path.get(index) != null
                        && path.get(index).getPlane() == player.getPlane()
                        && reachable != null && reachable.contains(path.get(index)))
                .min(Comparator.comparingInt((Integer index) -> path.get(index).distanceTo2D(player))
                        .thenComparing(Comparator.reverseOrder()));
        if (reachableIndex.isPresent())
        {
            return reachableIndex.get();
        }
        return IntStream.range(0, path.size())
                .boxed()
                .filter(index -> path.get(index) != null
                        && path.get(index).getPlane() == player.getPlane())
                .min(Comparator.comparingInt((Integer index) -> path.get(index).distanceTo2D(player))
                        .thenComparing(Comparator.reverseOrder()))
                .orElse(-1);
    }

    private static int routeActionIndex(List<WorldPoint> path, int currentIndex,
                                        WorldPoint player, Set<WorldPoint> reachable)
    {
        int lastEdge = Math.min(path.size() - 2, currentIndex + 2);
        for (int index = Math.max(0, currentIndex); index <= lastEdge; index++)
        {
            WorldPoint from = path.get(index);
            WorldPoint to = path.get(index + 1);
            if (from == null || to == null || from.getPlane() != player.getPlane()
                    || from.distanceTo2D(player) > ROUTE_EDGE_ACTION_DISTANCE)
            {
                continue;
            }
            if (Rs2Walker.isCatalogBackedTransportSegment(path, index)
                    || reachable.contains(from) && !reachable.contains(to))
            {
                return index;
            }
        }
        return -1;
    }

    private Observation terminal(ClientSample sample, Status status)
    {
        return new Observation(sample.tick, sample.player, status,
                null, -1, null, -1, false, sample.runEnabled);
    }

    private Observation waiting(ClientSample sample)
    {
        return terminal(sample, Status.WAITING_FOR_PATH);
    }

    private static List<WorldPoint> safePathCopy(List<WorldPoint> path)
    {
        return path == null ? Collections.emptyList() : List.copyOf(path);
    }

    private static int euclideanSquared(WorldPoint first, WorldPoint second)
    {
        int dx = first.getX() - second.getX();
        int dy = first.getY() - second.getY();
        return dx * dx + dy * dy;
    }

    private static ClientSample readClientSample()
    {
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
        {
            Client client = Microbot.getClient();
            return new ClientSample(client.getTickCount(), Rs2Player.getWorldLocation(),
                    Microbot.isLoggedIn(), Rs2Player.isRunEnabled());
        }).orElseGet(() -> new ClientSample(0, null, false, false));
    }

    private static int currentTick()
    {
        return Microbot.getClientThread().runOnClientThreadOptional(
                () -> Microbot.getClient().getTickCount()).orElse(-1);
    }

    static final class ForwardCandidate
    {
        private final WorldPoint target;
        private final int pathIndex;

        private ForwardCandidate(WorldPoint target, int pathIndex)
        {
            this.target = target;
            this.pathIndex = pathIndex;
        }

        WorldPoint getTarget()
        {
            return target;
        }

        int getPathIndex()
        {
            return pathIndex;
        }
    }

    private static final class ClientSample
    {
        private final int tick;
        private final WorldPoint player;
        private final boolean loggedIn;
        private final boolean runEnabled;

        private ClientSample(int tick, WorldPoint player, boolean loggedIn, boolean runEnabled)
        {
            this.tick = tick;
            this.player = player;
            this.loggedIn = loggedIn;
            this.runEnabled = runEnabled;
        }
    }
}
