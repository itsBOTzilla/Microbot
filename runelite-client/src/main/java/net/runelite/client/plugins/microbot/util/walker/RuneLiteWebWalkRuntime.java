package net.runelite.client.plugins.microbot.util.walker;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.Pathfinder;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/** Production Microbot adapter for {@link WebWalkExecutor}. */
public final class RuneLiteWebWalkRuntime implements WebWalkRuntime
{
    private static final int MINIMAP_ROUTE_RADIUS = 12;
    private static final int MINIMAP_COMMAND_RADIUS = 10;
    private static final int CANVAS_ROUTE_RADIUS = 5;
    private static final int CATALOG_ROUTE_EDGE_ACTION_DISTANCE = CANVAS_ROUTE_RADIUS;
    private static final int GENERIC_ROUTE_EDGE_ACTION_DISTANCE = 3;
    private static final int GENERIC_ROUTE_EDGE_INDEX_LOOKAHEAD = 2;
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
    private int lastObservedPathIndex = -1;
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
        if (Thread.currentThread().isInterrupted() || InputArbiter.isHuman())
        {
            return new Observation(lastObservedTick, null, Status.CANCELLED,
                    null, -1, null, -1, false, false);
        }
        ClientSample sample = readClientSample();
        lastObservedTick = sample.tick;
        if (Rs2Walker.getCurrentTarget() == null
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
        if (shouldWaitForPathfinder(pathfinder, invalidatedPathfinder, target))
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
        lastObservedPathIndex = -1;
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
        lastObservedPathIndex = currentIndex;
        pathRemaining = Math.max(0, rawPath.size() - currentIndex - 1);
        routeActionIndex = routeActionIndex(rawPath, currentIndex, sample.player, reachable);
        boolean routeActionAvailable = routeActionIndex >= 0;
        ForwardCandidate candidate = selectForwardCandidate(rawPath, sample.player, reachable,
                MINIMAP_COMMAND_RADIUS,
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
        if (requestedTarget == null || player.getPlane() != requestedTarget.getPlane())
        {
            return DispatchResult.rejected();
        }
        DispatchResult result = dispatchMovementTarget(player, requestedTarget,
                () -> Rs2Walker.manageRunEnergy(pathRemaining),
                () -> Rs2Walker.walkFastCanvasOnScreenOnly(requestedTarget),
                () -> dispatchRouteMinimap(lastRawPath, requestedTarget, player,
                        Rs2Tile.getReachableTilesFromTile(player, MINIMAP_ROUTE_RADIUS + 2).keySet(),
                        lastObservedPathIndex, pathIndex, MINIMAP_COMMAND_RADIUS,
                        index -> Rs2Walker.isCatalogBackedTransportSegment(lastRawPath, index),
                        Rs2Walker::walkMiniMap));
        WebWalkLog.movementDispatch(result.getMethod(), requestedTarget,
                result.getActualTarget(), pathIndex, lastObservedTick, redispatch);
        if (result.isAccepted())
        {
            String phase = result.getMethod() == DispatchMethod.CANVAS
                    ? "first_canvas_click" : "first_minimap_click";
            Rs2Walker.markFirstMovementClick(phase, target, player,
                    "method=" + result.getMethod() + " requested=" + requestedTarget
                            + " actual=" + result.getActualTarget());
        }
        return result;
    }

    static DispatchResult dispatchMovementTarget(WorldPoint player, WorldPoint requestedTarget,
                                                   Runnable beforeDispatch,
                                                   BooleanSupplier canvasDispatcher,
                                                   Supplier<DispatchResult> minimapDispatcher)
    {
        Objects.requireNonNull(beforeDispatch, "beforeDispatch");
        Objects.requireNonNull(canvasDispatcher, "canvasDispatcher");
        Objects.requireNonNull(minimapDispatcher, "minimapDispatcher");
        if (player == null || requestedTarget == null
                || player.getPlane() != requestedTarget.getPlane())
        {
            return DispatchResult.rejected();
        }
        beforeDispatch.run();
        if (shouldPreferCanvas(player, requestedTarget) && canvasDispatcher.getAsBoolean())
        {
            return DispatchResult.accepted(requestedTarget, DispatchMethod.CANVAS);
        }
        DispatchResult minimapResult = minimapDispatcher.get();
        return minimapResult == null ? DispatchResult.rejected() : minimapResult;
    }

    static DispatchResult dispatchRouteMinimap(List<WorldPoint> path, WorldPoint requestedTarget,
                                                WorldPoint player, Set<WorldPoint> reachable,
                                                int currentIndex, int requestedIndex,
                                                int maxEuclidean, IntPredicate transportEdgeAtIndex,
                                                Predicate<WorldPoint> minimapDispatcher)
    {
        if (path == null || path.isEmpty() || requestedTarget == null || player == null
                || reachable == null || reachable.isEmpty() || minimapDispatcher == null
                || currentIndex < 0 || requestedIndex < 0 || requestedIndex >= path.size()
                || !requestedTarget.equals(path.get(requestedIndex))
                || requestedTarget.getPlane() != player.getPlane())
        {
            return DispatchResult.rejected();
        }
        int freshCurrentIndex = closestForwardIndex(path, player, reachable);
        currentIndex = Math.max(currentIndex, freshCurrentIndex);
        if (freshCurrentIndex < 0 || requestedIndex < currentIndex)
        {
            return DispatchResult.rejected();
        }
        int maxDistanceSquared = maxEuclidean * maxEuclidean;
        if (reachable.contains(requestedTarget)
                && euclideanSquared(requestedTarget, player) <= maxDistanceSquared
                && minimapDispatcher.test(requestedTarget))
        {
            return DispatchResult.accepted(requestedTarget);
        }

        int maxFallbackIndex = requestedIndex - 1;
        for (int index = currentIndex; index <= maxFallbackIndex; index++)
        {
            if (index < path.size() - 1 && transportEdgeAtIndex != null
                    && transportEdgeAtIndex.test(index))
            {
                maxFallbackIndex = index;
                break;
            }
        }
        for (int index = maxFallbackIndex; index >= currentIndex; index--)
        {
            WorldPoint fallback = path.get(index);
            if (fallback == null || fallback.equals(player)
                    || path.subList(0, currentIndex).contains(fallback)
                    || fallback.getPlane() != player.getPlane()
                    || euclideanSquared(fallback, player) > maxDistanceSquared
                    || !reachable.contains(fallback))
            {
                continue;
            }
            if (minimapDispatcher.test(fallback))
            {
                return DispatchResult.accepted(fallback);
            }
        }
        return DispatchResult.rejected();
    }

    private static boolean shouldPreferCanvas(WorldPoint player, WorldPoint requestedTarget)
    {
        return player != null && requestedTarget != null
                && player.getPlane() == requestedTarget.getPlane()
                && player.distanceTo2D(requestedTarget) <= CANVAS_ROUTE_RADIUS;
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
        int lastEdge = Math.min(path.size() - 2,
                currentIndex + CATALOG_ROUTE_EDGE_ACTION_DISTANCE);
        for (int index = Math.max(0, currentIndex); index <= lastEdge; index++)
        {
            WorldPoint from = path.get(index);
            WorldPoint to = path.get(index + 1);
            if (from == null || to == null || from.getPlane() != player.getPlane())
            {
                continue;
            }
            int distance = from.distanceTo2D(player);
            if (Rs2Walker.isCatalogBackedTransportSegment(path, index)
                    && distance <= CATALOG_ROUTE_EDGE_ACTION_DISTANCE)
            {
                return index;
            }
            if (reachable.contains(from) && !reachable.contains(to))
            {
                // Preserve route ordering: a later catalog transport must not leapfrog an
                // unresolved door/gate/frontier that is still outside generic interaction range.
                boolean genericActionInRange = index <= currentIndex + GENERIC_ROUTE_EDGE_INDEX_LOOKAHEAD
                        && distance <= GENERIC_ROUTE_EDGE_ACTION_DISTANCE;
                return genericActionInRange ? index : -1;
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

    static boolean shouldWaitForPathfinder(Pathfinder pathfinder,
                                           Pathfinder invalidatedPathfinder,
                                           WorldPoint target)
    {
        return pathfinder == null
                || pathfinder == invalidatedPathfinder
                || !pathfinder.isDone()
                || target == null
                || !pathfinder.getTargets().contains(target);
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
