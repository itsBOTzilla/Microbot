package net.runelite.client.plugins.microbot.util.walker;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.Pathfinder;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.coords.Rs2LocalPoint;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
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
    private static final int CATALOG_DOOR_EDGE_ACTION_DISTANCE = 1;
    private static final int GENERIC_ROUTE_EDGE_ACTION_DISTANCE = 3;
    private static final int GENERIC_ROUTE_EDGE_INDEX_LOOKAHEAD = 2;
    private static final int STATE_WAIT_MS = 750;
    private static final long PATHFINDER_TIMEOUT_NANOS = 10_000_000_000L;
    private static final long LOGIN_GRACE_NANOS = 1_500_000_000L;
    private static final int CAMERA_LOOK_AHEAD_MIN_TILES = 6;
    private static final int CAMERA_LOOK_AHEAD_PREFERRED_TILES = 9;
    private static final int CAMERA_LOOK_AHEAD_MAX_TILES = 12;
    private static final int CAMERA_MIN_DISPATCH_DISTANCE_TILES = 8;
    private static final int CAMERA_MEANINGFUL_TURN_DEGREES = 60;
    private static final int CAMERA_MIN_TOLERANCE_PERCENT = 45;
    private static final int CAMERA_MAX_TOLERANCE_PERCENT = 60;
    private static final int CAMERA_MIN_STARTUP_GRACE_MS = 5_000;
    private static final int CAMERA_MAX_STARTUP_GRACE_MS = 10_000;
    private static final int CAMERA_MIN_UPDATE_INTERVAL_MS = 8_000;
    private static final int CAMERA_MAX_UPDATE_INTERVAL_MS = 15_000;
    private static final int CAMERA_MIN_MOTION_STEP = 8;
    private static final int CAMERA_MAX_MOTION_STEP = 24;
    private static final int CAMERA_MIN_MOTION_DELAY_MS = 40;
    private static final int CAMERA_MAX_MOTION_DELAY_MS = 70;
    private static final int CAMERA_MOTION_COMPLETE_TOLERANCE = 2;
    private static final int CAMERA_YAW_UNITS = 2_048;
    private static final int CAMERA_DIRECTION_UNSET = -1;

    private final WorldPoint target;
    private final int arrivalDistance;
    private final long targetGeneration;
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
    private final Object cameraGuard = new Object();
    private final Consumer<Runnable> cameraDispatcher;
    private final Supplier<WorldPoint> cameraPlayer;
    private final BooleanSupplier cameraHumanInput;
    private final IntSupplier cameraYawReader;
    private final IntConsumer cameraYawWriter;
    private final CameraStepScheduler cameraStepScheduler;
    private long cameraEpoch;
    private boolean cameraUpdateQueued;
    private boolean cameraMotionStepQueued;
    private long cameraMotionScheduleToken;
    private CameraMotion activeCameraMotion;
    private long nextCameraUpdateNanos;
    private boolean cameraDeadlineSet;
    private boolean cameraCadenceInitialized;

    public RuneLiteWebWalkRuntime(WorldPoint target, int arrivalDistance)
    {
        this(target, arrivalDistance, Rs2Walker.getCurrentTargetGeneration());
    }

    public RuneLiteWebWalkRuntime(WorldPoint target, int arrivalDistance, long targetGeneration)
    {
        this(target, arrivalDistance, targetGeneration,
                action -> Microbot.getClientThread().invokeLater(action),
                Rs2Player::getWorldLocation, InputArbiter::isHuman,
                Rs2Camera::getYaw, Rs2Camera::setYawInstant,
                (action, delayMs) -> CompletableFuture
                        .delayedExecutor(Math.max(0L, delayMs), TimeUnit.MILLISECONDS)
                        .execute(() -> Microbot.getClientThread().invokeLater(action)),
                walkingCameraDeadlineNanos(System.nanoTime(),
                        Rs2Random.logNormalBounded(CAMERA_MIN_STARTUP_GRACE_MS,
                                CAMERA_MAX_STARTUP_GRACE_MS)));
    }

    RuneLiteWebWalkRuntime(WorldPoint target, int arrivalDistance, long targetGeneration,
                           Consumer<Runnable> cameraDispatcher,
                           Supplier<WorldPoint> cameraPlayer,
                           BooleanSupplier cameraHumanInput,
                           IntConsumer cameraYawWriter,
                           long initialCameraDeadlineNanos)
    {
        this(target, arrivalDistance, targetGeneration, cameraDispatcher, cameraPlayer,
                cameraHumanInput, Rs2Camera::getYaw, cameraYawWriter,
                (action, ignoredDelayMs) -> cameraDispatcher.accept(action),
                initialCameraDeadlineNanos);
    }

    RuneLiteWebWalkRuntime(WorldPoint target, int arrivalDistance, long targetGeneration,
                           Consumer<Runnable> cameraDispatcher,
                           Supplier<WorldPoint> cameraPlayer,
                           BooleanSupplier cameraHumanInput,
                           IntSupplier cameraYawReader,
                           IntConsumer cameraYawWriter,
                           CameraStepScheduler cameraStepScheduler,
                           long initialCameraDeadlineNanos)
    {
        this.target = Objects.requireNonNull(target, "target");
        this.arrivalDistance = Math.max(0, arrivalDistance);
        this.targetGeneration = targetGeneration;
        this.cameraDispatcher = Objects.requireNonNull(cameraDispatcher, "cameraDispatcher");
        this.cameraPlayer = Objects.requireNonNull(cameraPlayer, "cameraPlayer");
        this.cameraHumanInput = Objects.requireNonNull(cameraHumanInput, "cameraHumanInput");
        this.cameraYawReader = Objects.requireNonNull(cameraYawReader, "cameraYawReader");
        this.cameraYawWriter = Objects.requireNonNull(cameraYawWriter, "cameraYawWriter");
        this.cameraStepScheduler = Objects.requireNonNull(cameraStepScheduler, "cameraStepScheduler");
        this.nextCameraUpdateNanos = initialCameraDeadlineNanos;
        this.cameraDeadlineSet = true;
    }

    @Override
    public Observation observe(WebWalkSession session)
    {
        if (!ownsCurrentRoute())
        {
            stopWalkingCamera();
            return new Observation(lastObservedTick, null, Status.CANCELLED,
                    null, -1, null, -1, false, false, false);
        }
        ClientSample sample = readClientSample();
        lastObservedTick = sample.tick;
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
        observePathfinderSource(pathfinder);

        List<WorldPoint> rawPath = safePathCopy(pathfinder.getPath());
        List<WorldPoint> walkPath = safePathCopy(pathfinder.getWalkablePath());
        RouteSnapshot route = new RouteSnapshot(routeGeneration, rawPath, walkPath);
        if (Rs2Walker.runtimeArrived(target, arrivalDistance, rawPath, walkPath))
        {
            stopWalkingCamera();
            return new Observation(sample.tick, sample.player, Status.ARRIVED,
                    route, -1, null, -1, false, sample.runEnabled, sample.moving);
        }
        if (rawPath.isEmpty())
        {
            stopWalkingCamera();
            return new Observation(sample.tick, sample.player, Status.UNREACHABLE,
                    route, -1, null, -1, false, sample.runEnabled, sample.moving);
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
            stopWalkingCamera();
            return new Observation(sample.tick, sample.player, Status.READY,
                    route, -1, null, -1, false, sample.runEnabled, sample.moving);
        }
        installWalkingCameraRoute(rawPath, currentIndex);
        pathRemaining = Math.max(0, rawPath.size() - currentIndex - 1);
        routeActionIndex = routeActionIndex(rawPath, currentIndex, sample.player, reachable);
        boolean routeActionAvailable = routeActionIndex >= 0;
        ForwardCandidate candidate = selectForwardCandidate(rawPath, sample.player, reachable,
                MINIMAP_COMMAND_RADIUS,
                index -> isExecutableCatalogTransportBoundary(rawPath, index, reachable), currentIndex);
        if (routeActionAvailable)
        {
            candidate = null;
        }
        return new Observation(sample.tick, sample.player, Status.READY, route, currentIndex,
                candidate == null ? null : candidate.target,
                candidate == null ? -1 : candidate.pathIndex, routeActionAvailable,
                sample.runEnabled, sample.moving);
    }

    @Override
    public DispatchResult dispatchMinimap(WorldPoint requestedTarget, int pathIndex, boolean redispatch)
    {
        return dispatchMovement(requestedTarget, pathIndex, redispatch, DispatchMethod.NONE);
    }

    @Override
    public DispatchResult dispatchMovement(WorldPoint requestedTarget, int pathIndex,
                                           boolean redispatch, DispatchMethod preferredMethod)
    {
        if (!ownsCurrentRoute())
        {
            return DispatchResult.rejected();
        }
        WorldPoint player = Rs2Player.getWorldLocation();
        if (player == null)
        {
            return DispatchResult.rejected();
        }
        if (requestedTarget == null || player.getPlane() != requestedTarget.getPlane())
        {
            return DispatchResult.rejected();
        }
        DispatchResult result = dispatchMovementTarget(player, requestedTarget, preferredMethod,
                () ->
                {
                    if (ownsCurrentRoute())
                    {
                        Rs2Walker.manageRunEnergy(pathRemaining);
                    }
                },
                () -> ownsCurrentRoute()
                        && Rs2Walker.walkFastCanvasOnScreenOnly(requestedTarget),
                () -> dispatchWhenCurrent(ownsCurrentRoute(),
                        () ->
                        {
                            Set<WorldPoint> reachable = Rs2Tile.getReachableTilesFromTile(
                                    player, MINIMAP_ROUTE_RADIUS + 2).keySet();
                            return dispatchRouteMinimap(lastRawPath, requestedTarget, player,
                                    reachable, lastObservedPathIndex, pathIndex,
                                    MINIMAP_COMMAND_RADIUS,
                                    index -> isExecutableCatalogTransportBoundary(
                                            lastRawPath, index, reachable),
                                    point -> ownsCurrentRoute()
                                            && Rs2Walker.walkMiniMap(point));
                        }));
        WebWalkLog.movementDispatch(result.getMethod(), requestedTarget,
                result.getActualTarget(), pathIndex, lastObservedTick, redispatch);
        if (result.isAccepted())
        {
            String phase = result.getMethod() == DispatchMethod.CANVAS
                    ? "first_canvas_click" : "first_minimap_click";
            Rs2Walker.markFirstMovementClick(phase, target, player,
                    "method=" + result.getMethod() + " requested=" + requestedTarget
                            + " actual=" + result.getActualTarget());
            scheduleWalkingCamera(player, result.getActualTarget());
        }
        return result;
    }

    static DispatchResult dispatchMovementTarget(WorldPoint player, WorldPoint requestedTarget,
                                                   Runnable beforeDispatch,
                                                   BooleanSupplier canvasDispatcher,
                                                   Supplier<DispatchResult> minimapDispatcher)
    {
        return dispatchMovementTarget(player, requestedTarget, DispatchMethod.NONE,
                beforeDispatch, canvasDispatcher, minimapDispatcher);
    }

    static DispatchResult dispatchMovementTarget(WorldPoint player, WorldPoint requestedTarget,
                                                   DispatchMethod preferredMethod,
                                                   Runnable beforeDispatch,
                                                   BooleanSupplier canvasDispatcher,
                                                   Supplier<DispatchResult> minimapDispatcher)
    {
        Objects.requireNonNull(beforeDispatch, "beforeDispatch");
        Objects.requireNonNull(canvasDispatcher, "canvasDispatcher");
        Objects.requireNonNull(minimapDispatcher, "minimapDispatcher");
        Objects.requireNonNull(preferredMethod, "preferredMethod");
        if (player == null || requestedTarget == null
                || player.getPlane() != requestedTarget.getPlane())
        {
            return DispatchResult.rejected();
        }
        beforeDispatch.run();
        if (preferredMethod != DispatchMethod.MINIMAP
                && shouldPreferCanvas(player, requestedTarget) && canvasDispatcher.getAsBoolean())
        {
            return DispatchResult.accepted(requestedTarget, DispatchMethod.CANVAS);
        }
        DispatchResult minimapResult = minimapDispatcher.get();
        return minimapResult == null ? DispatchResult.rejected() : minimapResult;
    }

    static DispatchResult dispatchWhenCurrent(boolean current,
                                              Supplier<DispatchResult> dispatcher)
    {
        Objects.requireNonNull(dispatcher, "dispatcher");
        return current ? dispatcher.get() : DispatchResult.rejected();
    }

    static boolean isRouteOwnershipCurrent(WorldPoint expectedTarget, long expectedGeneration,
                                           WorldPoint currentTarget, long currentGeneration)
    {
        return expectedGeneration == currentGeneration
                && Objects.equals(expectedTarget, currentTarget);
    }

    boolean ownsCurrentRoute()
    {
        return !Thread.currentThread().isInterrupted()
                && !InputArbiter.isHuman()
                && isRouteOwnershipCurrent(target, targetGeneration,
                        Rs2Walker.getCurrentTarget(), Rs2Walker.getCurrentTargetGeneration());
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
        if (!ownsCurrentRoute())
        {
            return ActionResult.RETRY;
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
        invalidateWalkingCameraRoute();
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
        stopWalkingCamera();
        Rs2Walker.clearWalkingRouteIfOwned(target, targetGeneration,
                "webwalk-executor:" + reason);
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

    static int routeActionIndex(List<WorldPoint> path, int currentIndex,
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
            boolean catalogEdge = isExecutableCatalogTransportBoundary(path, index, reachable);
            List<WorldPoint> dispatchPath = catalogEdge
                    ? Rs2Walker.catalogTransportDispatchPath(path, index) : path;
            WorldPoint actionOrigin = dispatchPath == path
                    || dispatchPath == null || dispatchPath.isEmpty()
                    ? from : dispatchPath.get(0);
            int distance = actionOrigin.distanceTo2D(player);
            boolean catalogDoorEdge = catalogEdge
                    && actionOrigin.getPlane() == to.getPlane()
                    && actionOrigin.distanceTo2D(to) <= 1
                    && Rs2Walker.isDoorLikeCatalogTransportSegment(path, index);
            int catalogActionDistance = catalogDoorEdge
                    ? CATALOG_DOOR_EDGE_ACTION_DISTANCE
                    : CATALOG_ROUTE_EDGE_ACTION_DISTANCE;
            if (catalogEdge && distance <= catalogActionDistance)
            {
                return index;
            }
            if (reachable.contains(from) && !reachable.contains(to))
            {
                // Preserve route ordering: a later catalog transport must not leapfrog an
                // unresolved door/gate/frontier that is still outside generic interaction range.
                boolean genericActionInRange = index <= currentIndex + GENERIC_ROUTE_EDGE_INDEX_LOOKAHEAD
                        && distance <= (catalogDoorEdge
                        ? CATALOG_DOOR_EDGE_ACTION_DISTANCE
                        : GENERIC_ROUTE_EDGE_ACTION_DISTANCE);
                return genericActionInRange ? index : -1;
            }
        }
        return -1;
    }

    static boolean isExecutableCatalogTransportBoundary(List<WorldPoint> path, int index,
                                                         Set<WorldPoint> reachable)
    {
        if (!Rs2Walker.isCatalogBackedTransportSegment(path, index))
        {
            return false;
        }
        List<WorldPoint> dispatchPath = Rs2Walker.catalogTransportDispatchPath(path, index);
        if (dispatchPath == path || dispatchPath == null || dispatchPath.isEmpty())
        {
            return true;
        }
        return reachable == null || reachable.contains(dispatchPath.get(0));
    }

    private Observation terminal(ClientSample sample, Status status)
    {
        if (status != Status.WAITING_FOR_PATH)
        {
            stopWalkingCamera();
        }
        return new Observation(sample.tick, sample.player, status,
                null, -1, null, -1, false, sample.runEnabled, sample.moving);
    }

    private Observation waiting(ClientSample sample)
    {
        return terminal(sample, Status.WAITING_FOR_PATH);
    }

    void installWalkingCameraRoute(List<WorldPoint> path, int currentPathIndex)
    {
        synchronized (cameraGuard)
        {
            if (!lastRawPath.equals(path) || lastObservedPathIndex != currentPathIndex)
            {
                cameraEpoch++;
            }
            lastRawPath = path;
            lastObservedPathIndex = currentPathIndex;
        }
    }

    void stopWalkingCamera()
    {
        synchronized (cameraGuard)
        {
            invalidateWalkingCameraLocked();
        }
    }

    void invalidateWalkingCameraRoute()
    {
        synchronized (cameraGuard)
        {
            observedPathfinder = null;
            invalidateWalkingCameraLocked();
        }
    }

    long observePathfinderSource(Pathfinder pathfinder)
    {
        synchronized (cameraGuard)
        {
            if (pathfinder != observedPathfinder)
            {
                observedPathfinder = pathfinder;
                routeGeneration++;
                invalidateWalkingCameraLocked();
            }
            return routeGeneration;
        }
    }

    private void invalidateWalkingCameraLocked()
    {
        cameraEpoch++;
        lastRawPath = Collections.emptyList();
        lastObservedPathIndex = -1;
        activeCameraMotion = null;
        cameraMotionStepQueued = false;
        cameraMotionScheduleToken++;
    }

    boolean scheduleWalkingCamera(WorldPoint player, WorldPoint dispatchedTarget)
    {
        CameraRequest request;
        synchronized (cameraGuard)
        {
            WorldPoint lookAhead = getCameraLookAhead(lastRawPath, lastObservedPathIndex, player);
            if (!canScheduleWalkingCamera(lookAhead, player, dispatchedTarget,
                    targetGeneration, Rs2Walker.getCurrentTargetGeneration(), cameraUpdateQueued)
                    || observedPathfinder == null)
            {
                return false;
            }
            request = new CameraRequest(cameraEpoch, targetGeneration, lastRawPath,
                    lastObservedPathIndex, lookAhead, observedPathfinder);
            cameraUpdateQueued = true;
        }

        try
        {
            cameraDispatcher.accept(() -> applyWalkingCameraUpdate(request));
            return true;
        }
        catch (RuntimeException ignored)
        {
            synchronized (cameraGuard)
            {
                cameraUpdateQueued = false;
            }
            return false;
        }
    }

    private void applyWalkingCameraUpdate(CameraRequest request)
    {
        synchronized (cameraGuard)
        {
            try
            {
                WorldPoint player = cameraPlayer.get();
                if (!isCameraRequestCurrentLocked(request, player) || cameraHumanInput.getAsBoolean())
                {
                    return;
                }

                WorldView worldView = Microbot.getClient().getTopLevelWorldView();
                if (worldView == null || worldView.getPlane() != request.lookAhead.getPlane())
                {
                    return;
                }
                LocalPoint localPoint = LocalPoint.fromWorld(worldView, request.lookAhead);
                if (localPoint == null && worldView.getScene() != null
                        && worldView.getScene().isInstance())
                {
                    localPoint = Rs2LocalPoint.fromWorldInstance(request.lookAhead);
                }
                if (localPoint == null)
                {
                    return;
                }

                int direction = getCameraLookAheadDirection(
                        request.path, request.currentPathIndex, player);
                if (direction == CAMERA_DIRECTION_UNSET)
                {
                    direction = Rs2Camera.angleToTile(request.lookAhead);
                }

                long now = System.nanoTime();
                int actualDirection = cameraDirectionFromYaw(cameraYawReader.getAsInt());
                boolean meaningfulTurn = cameraCadenceInitialized
                        && isMeaningfulCameraTurn(actualDirection, direction);
                if (isWalkingCameraUpdateDeferred(
                        now, nextCameraUpdateNanos, cameraDeadlineSet, meaningfulTurn))
                {
                    return;
                }

                int tolerance = Rs2Random.nextInt(CAMERA_MIN_TOLERANCE_PERCENT,
                        CAMERA_MAX_TOLERANCE_PERCENT, 1.0, true);
                boolean centered = Rs2Camera.isTileCenteredOnScreen(localPoint, tolerance);
                int intervalMs = Rs2Random.logNormalBounded(CAMERA_MIN_UPDATE_INTERVAL_MS,
                        CAMERA_MAX_UPDATE_INTERVAL_MS);
                nextCameraUpdateNanos = walkingCameraDeadlineNanos(now, intervalMs);
                cameraDeadlineSet = true;
                cameraCadenceInitialized = true;
                if (!centered)
                {
                    beginOrRetargetCameraMotionLocked(
                            Rs2Camera.calculateCameraYaw(direction), request);
                }
            }
            catch (RuntimeException ignored)
            {
                // Camera behavior is optional; walking must continue after any camera failure.
            }
            finally
            {
                cameraUpdateQueued = false;
            }
        }
    }

    private void beginOrRetargetCameraMotionLocked(int targetYaw, CameraRequest request)
    {
        int normalizedTarget = Math.floorMod(targetYaw, CAMERA_YAW_UNITS);
        if (activeCameraMotion == null
                || activeCameraMotion.request.epoch != request.epoch
                || activeCameraMotion.request.routeSource != request.routeSource)
        {
            activeCameraMotion = new CameraMotion(request, normalizedTarget);
        }
        else
        {
            activeCameraMotion.targetYaw = normalizedTarget;
        }

        if (!cameraMotionStepQueued)
        {
            scheduleCameraMotionStepLocked(0);
        }
    }

    private void scheduleCameraMotionStepLocked(int delayMs)
    {
        cameraMotionStepQueued = true;
        long scheduleToken = ++cameraMotionScheduleToken;
        try
        {
            cameraStepScheduler.schedule(() -> applyCameraMotionStep(scheduleToken), delayMs);
        }
        catch (RuntimeException ignored)
        {
            if (scheduleToken == cameraMotionScheduleToken)
            {
                cameraMotionStepQueued = false;
                activeCameraMotion = null;
            }
        }
    }

    private void applyCameraMotionStep(long scheduleToken)
    {
        synchronized (cameraGuard)
        {
            if (scheduleToken != cameraMotionScheduleToken)
            {
                return;
            }
            cameraMotionStepQueued = false;
            CameraMotion motion = activeCameraMotion;
            if (motion == null)
            {
                return;
            }

            try
            {
                WorldPoint player = cameraPlayer.get();
                if (!isCameraRequestCurrentLocked(motion.request, player)
                        || cameraHumanInput.getAsBoolean())
                {
                    activeCameraMotion = null;
                    return;
                }

                int currentYaw = Math.floorMod(cameraYawReader.getAsInt(), CAMERA_YAW_UNITS);
                int delta = shortestCameraYawDelta(currentYaw, motion.targetYaw);
                if (Math.abs(delta) <= CAMERA_MOTION_COMPLETE_TOLERANCE)
                {
                    activeCameraMotion = null;
                    return;
                }

                int stepMagnitude = cameraMotionStepMagnitude(delta);
                int nextYaw = Math.floorMod(
                        currentYaw + Integer.signum(delta) * stepMagnitude, CAMERA_YAW_UNITS);
                cameraYawWriter.accept(nextYaw);

                if (activeCameraMotion == motion)
                {
                    int delayMs = Rs2Random.nextInt(CAMERA_MIN_MOTION_DELAY_MS,
                            CAMERA_MAX_MOTION_DELAY_MS, 1.0, true);
                    scheduleCameraMotionStepLocked(delayMs);
                }
            }
            catch (RuntimeException ignored)
            {
                activeCameraMotion = null;
            }
        }
    }

    private boolean isCameraRequestCurrentLocked(CameraRequest request, WorldPoint player)
    {
        if (request == null || request.epoch != cameraEpoch
                || request.targetGeneration != targetGeneration
                || request.routeSource != observedPathfinder
                || request.routeSource != Rs2PathApi.getPathfinder()
                || request.path != lastRawPath || request.currentPathIndex != lastObservedPathIndex
                || player == null || player.getPlane() != request.lookAhead.getPlane()
                || !isRouteOwnershipCurrent(target, targetGeneration,
                Rs2Walker.getCurrentTarget(), Rs2Walker.getCurrentTargetGeneration()))
        {
            return false;
        }
        for (int index = request.currentPathIndex + 1; index < request.path.size(); index++)
        {
            WorldPoint candidate = request.path.get(index);
            if (candidate == null || candidate.getPlane() != player.getPlane())
            {
                break;
            }
            if (candidate.equals(request.lookAhead))
            {
                return true;
            }
        }
        return false;
    }

    static int getCameraLookAheadDirection(List<WorldPoint> path, int currentPathIndex,
                                               WorldPoint player)
    {
        if (path == null || path.isEmpty() || player == null)
        {
            return CAMERA_DIRECTION_UNSET;
        }

        int startIndex = Math.max(0, currentPathIndex + 1);
        if (startIndex >= path.size())
        {
            return CAMERA_DIRECTION_UNSET;
        }

        WorldPoint previous = player;
        double weightedDx = 0.0;
        double weightedDy = 0.0;
        int travelled = 0;
        int weight = 1;

        for (int index = startIndex; index < path.size(); index++)
        {
            WorldPoint candidate = path.get(index);
            if (candidate == null || candidate.getPlane() != player.getPlane())
            {
                break;
            }

            int segmentDistance = previous.distanceTo2D(candidate);
            travelled += segmentDistance;
            if (travelled > CAMERA_LOOK_AHEAD_MAX_TILES)
            {
                break;
            }

            int dx = candidate.getX() - previous.getX();
            int dy = candidate.getY() - previous.getY();
            if (dx != 0 || dy != 0)
            {
                weightedDx += dx * weight;
                weightedDy += dy * weight;
                weight++;
            }
            previous = candidate;
        }

        if (weightedDx == 0.0 && weightedDy == 0.0)
        {
            return CAMERA_DIRECTION_UNSET;
        }

        int direction = (int) Math.round(Math.toDegrees(Math.atan2(weightedDy, weightedDx)));
        return Math.floorMod(direction, 360);
    }

    static WorldPoint getCameraLookAhead(List<WorldPoint> path, int currentPathIndex,
                                          WorldPoint player)
    {
        if (path == null || path.isEmpty() || player == null)
        {
            return null;
        }
        int startIndex = Math.max(0, currentPathIndex + 1);
        if (startIndex >= path.size())
        {
            return null;
        }

        WorldPoint best = null;
        WorldPoint previous = player;
        int travelled = 0;
        int bestDistanceDifference = Integer.MAX_VALUE;
        for (int index = startIndex; index < path.size(); index++)
        {
            WorldPoint candidate = path.get(index);
            if (candidate == null || candidate.getPlane() != player.getPlane())
            {
                break;
            }
            travelled += previous.distanceTo2D(candidate);
            int distance = player.distanceTo2D(candidate);
            if (travelled > CAMERA_LOOK_AHEAD_MAX_TILES
                    || distance > CAMERA_LOOK_AHEAD_MAX_TILES)
            {
                break;
            }
            if (distance >= CAMERA_LOOK_AHEAD_MIN_TILES)
            {
                int difference = Math.abs(distance - CAMERA_LOOK_AHEAD_PREFERRED_TILES);
                if (difference <= bestDistanceDifference)
                {
                    best = candidate;
                    bestDistanceDifference = difference;
                }
            }
            previous = candidate;
        }
        return best;
    }

    static boolean canScheduleWalkingCamera(WorldPoint lookAhead, WorldPoint player,
                                             WorldPoint dispatchedTarget,
                                             long targetGeneration, long currentGeneration,
                                             boolean updateQueued)
    {
        return lookAhead != null && player != null && dispatchedTarget != null
                && lookAhead.getPlane() == player.getPlane()
                && dispatchedTarget.getPlane() == player.getPlane()
                && player.distanceTo2D(dispatchedTarget) >= CAMERA_MIN_DISPATCH_DISTANCE_TILES
                && targetGeneration == currentGeneration && !updateQueued;
    }

    static int cameraDirectionDifference(int firstDirection, int secondDirection)
    {
        int difference = Math.abs(firstDirection - secondDirection) % 360;
        return Math.min(difference, 360 - difference);
    }

    static int cameraDirectionFromYaw(int yaw)
    {
        int normalizedYaw = Math.floorMod(yaw, CAMERA_YAW_UNITS);
        double degrees = (normalizedYaw - 1536) * (360.0 / CAMERA_YAW_UNITS);
        return Math.floorMod((int) Math.round(degrees), 360);
    }

    static boolean isMeaningfulCameraTurn(int previousDirection, int direction)
    {
        return previousDirection != CAMERA_DIRECTION_UNSET
                && cameraDirectionDifference(previousDirection, direction)
                >= CAMERA_MEANINGFUL_TURN_DEGREES;
    }

    static boolean isWalkingCameraUpdateDeferred(long now, long deadline,
                                                   boolean deadlineSet,
                                                   boolean meaningfulTurn)
    {
        return deadlineSet && !meaningfulTurn && now - deadline < 0L;
    }

    static long walkingCameraDeadlineNanos(long now, int delayMs)
    {
        return now + TimeUnit.MILLISECONDS.toNanos(Math.max(0, delayMs));
    }

    static int shortestCameraYawDelta(int currentYaw, int targetYaw)
    {
        int current = Math.floorMod(currentYaw, CAMERA_YAW_UNITS);
        int target = Math.floorMod(targetYaw, CAMERA_YAW_UNITS);
        int delta = Math.floorMod(target - current, CAMERA_YAW_UNITS);
        return delta > CAMERA_YAW_UNITS / 2 ? delta - CAMERA_YAW_UNITS : delta;
    }

    static int cameraMotionStepMagnitude(int delta)
    {
        int magnitude = Math.abs(delta);
        if (magnitude <= CAMERA_MOTION_COMPLETE_TOLERANCE)
        {
            return magnitude;
        }
        int proportional = (int) Math.ceil(magnitude * 0.18);
        return Math.min(magnitude,
                Math.max(CAMERA_MIN_MOTION_STEP,
                        Math.min(CAMERA_MAX_MOTION_STEP, proportional)));
    }

    static int boundedCameraYaw(int currentYaw, int targetYaw, int maxStep)
    {
        int delta = shortestCameraYawDelta(currentYaw, targetYaw);
        int stepLimit = Math.max(0, Math.min(maxStep, CAMERA_YAW_UNITS / 2));
        int step = Math.max(-stepLimit, Math.min(stepLimit, delta));
        return Math.floorMod(currentYaw + step, CAMERA_YAW_UNITS);
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
            Player localPlayer = client.getLocalPlayer();
            boolean moving = localPlayer != null
                    && localPlayer.getPoseAnimation() != localPlayer.getIdlePoseAnimation();
            return new ClientSample(client.getTickCount(), Rs2Player.getWorldLocation(),
                    Microbot.isLoggedIn(), Rs2Player.isRunEnabled(), moving);
        }).orElseGet(() -> new ClientSample(0, null, false, false, false));
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

    @FunctionalInterface
    interface CameraStepScheduler
    {
        void schedule(Runnable action, int delayMs);
    }

    private static final class CameraMotion
    {
        private final CameraRequest request;
        private int targetYaw;

        private CameraMotion(CameraRequest request, int targetYaw)
        {
            this.request = request;
            this.targetYaw = targetYaw;
        }
    }

    private static final class CameraRequest
    {
        private final long epoch;
        private final long targetGeneration;
        private final List<WorldPoint> path;
        private final int currentPathIndex;
        private final WorldPoint lookAhead;
        private final Pathfinder routeSource;

        private CameraRequest(long epoch, long targetGeneration, List<WorldPoint> path,
                              int currentPathIndex, WorldPoint lookAhead,
                              Pathfinder routeSource)
        {
            this.epoch = epoch;
            this.targetGeneration = targetGeneration;
            this.path = path;
            this.currentPathIndex = currentPathIndex;
            this.lookAhead = lookAhead;
            this.routeSource = routeSource;
        }
    }

    private static final class ClientSample
    {
        private final int tick;
        private final WorldPoint player;
        private final boolean loggedIn;
        private final boolean runEnabled;
        private final boolean moving;

        private ClientSample(int tick, WorldPoint player, boolean loggedIn, boolean runEnabled,
                             boolean moving)
        {
            this.tick = tick;
            this.player = player;
            this.loggedIn = loggedIn;
            this.runEnabled = runEnabled;
            this.moving = moving;
        }
    }
}
