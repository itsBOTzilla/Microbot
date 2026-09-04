package net.runelite.client.plugins.microbot.util.walker;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.coords.WorldPoint;

/**
 * Owns the asynchronous walking-camera gate and the route revision that makes queued camera work
 * invalid as soon as its source route is replaced or stopped.
 */
final class WalkingCameraCoordinator
{
    interface ClientThreadDispatcher
    {
        Optional<Boolean> dispatch(Callable<Boolean> action);
    }

    interface FinalState
    {
        WorldPoint getPlayerLocation();

        boolean isTargetCurrent(long targetGeneration);

        boolean isHumanInput();
    }

    static final class Request
    {
        private final WorldPoint lookAhead;
        private final long targetGeneration;
        private final long routeRevision;

        private Request(WorldPoint lookAhead, long targetGeneration, long routeRevision)
        {
            this.lookAhead = lookAhead;
            this.targetGeneration = targetGeneration;
            this.routeRevision = routeRevision;
        }

        WorldPoint getLookAhead()
        {
            return lookAhead;
        }

        long getTargetGeneration()
        {
            return targetGeneration;
        }

        long getRouteRevision()
        {
            return routeRevision;
        }
    }

    private final Executor executor;
    private final ClientThreadDispatcher clientThreadDispatcher;
    private final AtomicBoolean updateInFlight = new AtomicBoolean();
    private final Object routeMutex = new Object();
    private final Set<Object> invalidatedRouteSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private long routeRevision;
    private RouteState routeState = RouteState.stopped(0L);
    private Object lastRouteSource;
    private long lastRouteSourceTargetGeneration;
    private boolean lastRouteSourceSet;
    private long invalidatedSourcesTargetGeneration;
    private boolean invalidatedSourcesTargetGenerationSet;

    WalkingCameraCoordinator(Executor executor, ClientThreadDispatcher clientThreadDispatcher)
    {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clientThreadDispatcher = Objects.requireNonNull(
                clientThreadDispatcher, "clientThreadDispatcher");
    }

    long publishRoute(long expectedRouteRevision, long targetGeneration,
                      List<WorldPoint> path, int currentPathIndex, Object routeSource)
    {
        List<WorldPoint> route = path == null ? Collections.emptyList() : List.copyOf(path);
        synchronized (routeMutex)
        {
            selectInvalidatedSourcesGenerationLocked(targetGeneration);
            if (routeRevision != expectedRouteRevision || routeSource == null
                    || invalidatedRouteSources.contains(routeSource))
            {
                return -1L;
            }
            if (route.isEmpty() || currentPathIndex < 0 || currentPathIndex >= route.size())
            {
                if (routeState.travelling
                        && routeState.targetGeneration == targetGeneration)
                {
                    routeRevision++;
                    routeState = RouteState.stopped(routeRevision);
                }
                return routeRevision;
            }
            boolean pathChanged = !routeState.travelling
                    || routeState.targetGeneration != targetGeneration
                    || !routeState.path.equals(route);
            if (pathChanged)
            {
                routeRevision++;
            }
            routeState = new RouteState(routeRevision, targetGeneration, route,
                    currentPathIndex, true);
            lastRouteSource = routeSource;
            lastRouteSourceTargetGeneration = targetGeneration;
            lastRouteSourceSet = true;
            return routeRevision;
        }
    }

    long invalidateRoute(long targetGeneration, Object currentRouteSource)
    {
        synchronized (routeMutex)
        {
            selectInvalidatedSourcesGenerationLocked(targetGeneration);
            if (currentRouteSource != null)
            {
                invalidatedRouteSources.add(currentRouteSource);
            }
            if (lastRouteSourceSet && lastRouteSourceTargetGeneration == targetGeneration)
            {
                invalidatedRouteSources.add(lastRouteSource);
            }
            routeRevision++;
            routeState = RouteState.stopped(routeRevision);
            return routeRevision;
        }
    }

    private void selectInvalidatedSourcesGenerationLocked(long targetGeneration)
    {
        if (!invalidatedSourcesTargetGenerationSet
                || invalidatedSourcesTargetGeneration != targetGeneration)
        {
            invalidatedRouteSources.clear();
            invalidatedSourcesTargetGeneration = targetGeneration;
            invalidatedSourcesTargetGenerationSet = true;
        }
    }

    void stopTravelling(long targetGeneration)
    {
        synchronized (routeMutex)
        {
            if (!routeState.travelling || routeState.targetGeneration != targetGeneration)
            {
                return;
            }
            routeRevision++;
            routeState = RouteState.stopped(routeRevision);
        }
    }

    Request request(WorldPoint lookAhead, long targetGeneration, long expectedRouteRevision)
    {
        synchronized (routeMutex)
        {
            if (!isCurrentFutureNodeLocked(
                    lookAhead, targetGeneration, expectedRouteRevision, null))
            {
                return null;
            }
            return new Request(lookAhead, targetGeneration, expectedRouteRevision);
        }
    }

    boolean trySchedule(Request request, Runnable update)
    {
        Objects.requireNonNull(update, "update");
        if (request == null || !isCurrentRequest(request)
                || !updateInFlight.compareAndSet(false, true))
        {
            return false;
        }

        try
        {
            executor.execute(new ReleasingUpdate(update));
            return true;
        }
        catch (RuntimeException ignored)
        {
            updateInFlight.set(false);
            return false;
        }
    }

    boolean dispatchIfCurrent(Request request, FinalState finalState, Runnable yawUpdate)
    {
        Objects.requireNonNull(finalState, "finalState");
        Objects.requireNonNull(yawUpdate, "yawUpdate");
        try
        {
            return clientThreadDispatcher.dispatch(
                    new GuardedClientAction(request, finalState, yawUpdate)).orElse(false);
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
    }

    boolean isUpdateInFlight()
    {
        return updateInFlight.get();
    }

    long getRouteRevision()
    {
        synchronized (routeMutex)
        {
            return routeRevision;
        }
    }

    boolean isCurrentRequest(Request request)
    {
        synchronized (routeMutex)
        {
            return isCurrentFutureNodeLocked(request.lookAhead, request.targetGeneration,
                    request.routeRevision, null);
        }
    }

    private boolean isCurrentFutureNodeLocked(WorldPoint lookAhead, long targetGeneration,
                                               long expectedRouteRevision, WorldPoint player)
    {
        if (lookAhead == null || !routeState.travelling
                || routeState.revision != expectedRouteRevision
                || routeState.targetGeneration != targetGeneration)
        {
            return false;
        }
        if (player != null && lookAhead.getPlane() != player.getPlane())
        {
            return false;
        }
        for (int index = routeState.currentPathIndex + 1;
             index < routeState.path.size(); index++)
        {
            WorldPoint candidate = routeState.path.get(index);
            if (candidate == null || player != null && candidate.getPlane() != player.getPlane())
            {
                break;
            }
            if (candidate.equals(lookAhead))
            {
                return true;
            }
        }
        return false;
    }

    private final class ReleasingUpdate implements Runnable
    {
        private final Runnable update;

        private ReleasingUpdate(Runnable update)
        {
            this.update = update;
        }

        @Override
        public void run()
        {
            try
            {
                update.run();
            }
            catch (RuntimeException ignored)
            {
                // Camera work is optional and must never fail the walking executor.
            }
            finally
            {
                updateInFlight.set(false);
            }
        }
    }

    private final class GuardedClientAction implements Callable<Boolean>
    {
        private final Request request;
        private final FinalState finalState;
        private final Runnable yawUpdate;

        private GuardedClientAction(Request request, FinalState finalState, Runnable yawUpdate)
        {
            this.request = request;
            this.finalState = finalState;
            this.yawUpdate = yawUpdate;
        }

        @Override
        public Boolean call()
        {
            synchronized (routeMutex)
            {
                WorldPoint player = finalState.getPlayerLocation();
                if (request == null || player == null
                        || !isCurrentFutureNodeLocked(request.lookAhead,
                        request.targetGeneration, request.routeRevision, player)
                        || !finalState.isTargetCurrent(request.targetGeneration)
                        || finalState.isHumanInput())
                {
                    return false;
                }
                yawUpdate.run();
                return true;
            }
        }
    }

    private static final class RouteState
    {
        private final long revision;
        private final long targetGeneration;
        private final List<WorldPoint> path;
        private final int currentPathIndex;
        private final boolean travelling;

        private RouteState(long revision, long targetGeneration, List<WorldPoint> path,
                           int currentPathIndex, boolean travelling)
        {
            this.revision = revision;
            this.targetGeneration = targetGeneration;
            this.path = path;
            this.currentPathIndex = currentPathIndex;
            this.travelling = travelling;
        }

        private static RouteState stopped(long revision)
        {
            return new RouteState(revision, -1L, Collections.emptyList(), -1, false);
        }
    }
}
