# Movement Gotchas

## Current execution model

Blocking WebWalker calls run through `WebWalkExecutor`, `WebWalkSession`, and
`RuneLiteWebWalkRuntime`. The executor owns one copied observation at a time and may dispatch at
most one movement command (canvas or minimap) or route-edge interaction from it. After every
dispatch it waits for a new tick, player tile, or pathfinder state before deciding again.
`Rs2Walker.processWalk` remains only as legacy implementation detail during the migration and is
not called by `walkWithStateInternal`.

New movement fixes must preserve these boundaries:

- the pathfinder and transport catalog remain the route-planning source;
- the runtime selects the furthest collision-reachable forward point without crossing a transport;
- an accepted click owns its actual fallback destination as the checkpoint;
- an accepted checkpoint remains owned until the player passes its raw-path index, reaches within
  one Chebyshev tile, or, after a genuine approach from outside the five-tile band, comes within
  five Chebyshev tiles; run speed
  never releases it early;
- a rejected dispatch with `actual=null` never creates a checkpoint, and the bounded rejection
  budget replans instead of spinning on the same unusable target;
- doors, transports, and dynamic blockers outrank forward minimap movement;
- a door or transport preempts the active checkpoint and movement resumes only from a fresh
  observation after the route action;
- progress means a changed player tile or forward path index, not merely elapsed time;
- a dispatch always ends the current decision cycle.

The historical sections below document the failure modes that led to this executor. Do not restore
their former multi-scan `processWalk` ownership model when reusing an individual helper.

Source tests and a successful assembly prove only the implementation. Launcher qualification must
use the stable `%USERPROFILE%\.microbot\microbot-local.jar`, verify its SHA-256 and embedded commit
against the clean source commit, start it through the normal launcher with `version_pref=local`, and
confirm the same commit in fresh startup logs. A matching filename alone is not runtime proof.

## 1. Do not recurse on failed minimap clicks without changing the click target

`Rs2Walker.processWalk` holds the walker lock while processing a path. If a minimap click is rejected because the calculated point is outside the minimap clip, immediately recursing with the same target can spin forever while still holding the lock. Shrink the click target toward the player or otherwise change the condition before retrying.

**Why this matters:** Quest steps that walk to a nearby object can repeatedly calculate a valid path but never move, starving other walk requests because the walker lock is never released.

**Pattern to follow:**

```java
WorldPoint clickTarget = getPointWithWallDistance(targetWp);
boolean clicked = Rs2Walker.walkMiniMap(clickTarget);
if (!clicked)
{
	clicked = walkMiniMapToward(clickTarget, playerLoc, MINIMAP_REACH_EUCLIDEAN - 1);
}
```

**Where this applies:** `Rs2Walker`, `Rs2MiniMap`, and shortest-path walking loops.

**Defensive check:** When debugging stalls, compare pathfinder logs with `./microbot-cli state`. A repeating valid path with an unchanged player position usually means the click layer failed after pathing succeeded.

## 2. Probe raw path obstacles before declaring the walker stuck

Path smoothing can collapse many adjacent raw path tiles into one minimap waypoint. Some doors and gates are not represented as blocking collision in the pathfinder map, so the smoothed segment may legally cross them while hiding the exact tile the object handler needs to inspect. Run nearby raw-path door/object checks as soon as the raw path is longer than the smoothed path and the obstacle is in scene range; do not wait for `stuckCount` to increment first.

**Why this matters:** A walk from Varrock castle's upper floors toward Varrock fountain can descend correctly, then stall at the plane-1 castle door because the smoothed waypoint skips over the door tile and the normal per-segment door check never sees it.

**Pattern to follow:**

```java
if (rawPath != null && path != null && rawPath.size() > path.size()
        && handleNearbyRawPathSceneObjects(rawPath, HANDLER_RANGE)) {
    doorOrTransportResult = true;
}
```

**Where this applies:** `Rs2Walker`, `PathSmoother`, and shortest-path obstacle handling.

**Defensive check:** When a path stalls beside a visible door while the pathfinder reports a complete route, compare raw and smoothed path lengths; if the raw path is longer, verify nearby raw-path obstacle probing happens before stall recovery.

## 3. Match wall doors by crossed edge, not nearby tile

Wall-object doors block the edge between the wall object's tile and the neighboring tile indicated by its orientation. Raw-path segment probes must only treat a wall door as relevant when the path segment actually transitions across that edge. Do not match a wall door merely because the path starts on, ends on, or passes near one side of the door.

**Why this matters:** At Draynor Manor's east/back door, the player can stand on the south-side door tile and need to walk southwest into the room. A broad "door near segment" match repeatedly re-opens the back door instead of allowing the next minimap walk step to run.

**Pattern to follow:**

```java
WorldPoint doorTile = wall.getWorldLocation();
WorldPoint blockedNeighbor = getWallDoorNeighborPoint(wall.getOrientationA(), doorTile);
return isDoorEdgeTransition(previousPathTile, nextPathTile, doorTile, blockedNeighbor);
```

**Where this applies:** `Rs2Walker.handleNearbyRawPathSceneObjects`, `Rs2Walker.findDoorNearSegment`, and any wall-door probe that uses `WallObject.getOrientationA()`.

**Defensive check:** Add a unit test for a path starting on the door's blocked-neighbor tile and moving away from the door; it must return false.

## 4. Do not raw-probe doors while the player is already moving

Raw-path scene-object probing is a recovery aid for smoothed paths that hide nearby obstacles. Once a door interaction has started movement, let that movement settle or reach the door edge before probing again. Re-running raw probes while the player is still moving can repeatedly interact with the same door and prevent the normal minimap/path step from taking over.

**Why this matters:** When leaving Draynor Manor through the east/back door, the walker can click the door, start moving toward it, then immediately re-enter raw-path probing and click the same door again instead of continuing through the path outside.

**Pattern to follow:**

```java
if (Rs2Player.isMoving()) {
    return false;
}
WorldPoint posBefore = Rs2Player.getWorldLocation();
boolean interacted = Rs2GameObject.interact(door, action);
if (interacted) {
    waitForDoorInteractionProgress(posBefore, fromWp, toWp);
}
```

**Where this applies:** `Rs2Walker.handleNearbyRawPathSceneObjects`, door handlers that call `Rs2GameObject.interact`, and any recovery logic that recurses into `processWalk`.

**Defensive check:** In live testing, a door should produce one interaction followed by movement/path progress, not repeated `Raw path door handler resolved obstacle` messages every tick while the player is moving.

## 5. Suppress the inverse adjacent transport after crossing a same-plane door

Some doors are represented in `transports.tsv` as two adjacent same-plane transports, one for each direction. After the walker clicks one side and arrives on the other, immediately accepting the inverse transport can bounce the player back through the same door instead of letting the next minimap step continue away from it. Mark both tiles of a successful adjacent same-plane transport as recently handled for a short window.

**Why this matters:** Leaving Draynor Manor through the east/back door can alternate between `3123,3360,0` and `3123,3361,0`, repeatedly logging raw-path/current-tile transport handling and burning the route timeout before walking back to Draynor.

**Pattern to follow:**

```java
boolean reachedDestination = sleepUntil(() -> atTransportDestination(transport), 5000);
if (reachedDestination && isAdjacentSamePlaneTransport(transport)) {
    markStationaryDoorOpened(transport.getOrigin());
    markStationaryDoorOpened(transport.getDestination());
}
```

**Where this applies:** `Rs2Walker.handleTransports`, current-tile transport recovery, raw-path transport probing, and bidirectional same-plane door/gate transports.

**Defensive check:** A successful adjacent same-plane transport should be followed by a minimap/path step away from the doorway, not by alternating `Raw path transport handler` and `Current-tile transport handler` logs for the same two tiles.

## 6. Recalculate after long-distance object transports

Not every large map transition changes plane or uses a teleport type. Some object transports, such as the Varrock Sewers ladder, remain on plane 0 while jumping between coordinate bands. After a successful object interaction reaches one of these destinations, run the normal transport finalizer so the shortest path is rebuilt from the new location.

**Why this matters:** A route from Varrock Sewers back to a surface origin can climb the ladder successfully, then continue using a path that was calculated from the underground coordinate band. The walker may drift off path or exit during setup even though the transport itself worked.

**Pattern to follow:**

```java
if (reachedDestination) {
    markAdjacentSamePlaneTransportHandled(transport, object);
    return finishHandledTransport(transport);
}
```

**Where this applies:** `Rs2Walker.handleTransports` object interactions and any object-transport handler that waits for the destination tile directly.

**Defensive check:** Same-plane object transports with a large `distanceTo2D` delta should produce a fresh pathfinder start near the post-transport player location before the next minimap step.

## 7. Model missing collision edges before tuning walker retries

Some static collision gaps are specific edges, not whole tiles. If the pathfinder repeatedly routes through a visible fence/wall and the live client keeps clicking fallback tiles near that boundary, add an explicit blocked edge to pathfinding and smoothing instead of trying to solve it with longer timeouts or broader minimap fallback.

**Why this matters:** The Varrock Palace garden south fence can be missing from the bundled collision map near `3229..3241,3472 -> 3471`. A no-agility F2P route to the Varrock Sewers manhole can walk around the trellis correctly, then stall against that garden boundary because the path says the south edge is traversable.

**Pattern to follow:**

```java
if (config.isBlockedTransportEdge(node.packedPosition, neighborPacked)) {
    continue;
}
```

**Where this applies:** `CollisionMap.getNeighbors`, `PathSmoother.lineOfSight`, and any path data correction where only one edge between adjacent tiles is invalid.

**Defensive check:** Add a core pathfinder regression from the observed stuck tile; assert neither the raw path nor smoothed path crosses the blocked edge, and that the route still reaches the original destination.

## 8. Do not click a visible endpoint before honoring pending route interactions

An endpoint being visible on the minimap does not mean it is the next correct click. If the computed shortest path reaches that endpoint through an intermediate door, gate, transport, shortcut, ladder, or other route object, the walker must process the first route interaction before issuing a direct endpoint click.

**Why this matters:** From Varrock Palace, a destination such as `3229,3473,0` can be visible on the minimap while the shorter route requires opening the palace doors first. Clicking the endpoint lets the game choose a longer collision-valid detour and bypasses the webwalker's route.

**Pattern to follow:**

```java
if (handleNearbyRawPathSceneObjects(rawPath, HANDLER_RANGE)) {
    return true;
}
if (!hasPendingExplicitTransportStepBeforeArrival(rawPath, target, distance)
        && !localRouteDetoursFromComputedRoute(rawPath, end, DIRECT_CLICK_MAX_DISTANCE)) {
    walkMiniMap(end);
}
```

**Where this applies:** `Rs2Walker.walkWithStateInternal`, short local walk kick-starts, final/minimap endpoint clicks, and any future fast-path that bypasses normal path iteration.

**Defensive check:** Reproduce with closed Varrock Palace doors toward `3229,3473,0`; the first action should target the door or route waypoint, not the final endpoint tile.

## 9. Preserve interrupts so walker cancellation stops waits immediately

Ctrl+X and script shutdown cancel the active walk task with `Future.cancel(true)` and clear the walker target. Shared sleep/poll helpers must preserve the interrupted flag and stop polling when interruption is observed; otherwise the walker can continue through several timeout cycles before noticing the cleared target.

**Why this matters:** A user pressing Ctrl+X expects the webwalker to stop issuing route actions immediately. If `InterruptedException` is swallowed, long waits in object, transport, dialogue, or animation handling can keep cycling until their normal timeout elapses.

**Pattern to follow:**

```java
try {
    Thread.sleep(delayMs);
} catch (InterruptedException ignored) {
    Thread.currentThread().interrupt();
}
while (!Thread.currentThread().isInterrupted() && !condition.getAsBoolean()) {
    sleep(pollMs);
}
```

**Where this applies:** `Global.sleep*`, `Global.sleepUntil*`, `Rs2Walker.setTarget(null)`, and any walker helper that waits after clicking a door, shortcut, transport, or minimap tile.

**Defensive check:** Start a long webwalk, press Ctrl+X during movement or a route-object wait, and verify no additional path recalculations or route-object interactions occur after the cancel log.

## 10. Do not treat reachable endpoint tiles as proof that a gate edge is open

Local reachability answers whether individual tiles can be reached within the sampled area; it does not prove that the computed path edge between two reachable tiles can be crossed without opening a gate, door, stile, or similar route object. Before skipping door handling, issuing a direct short minimap/checkpoint click, or yielding to an in-flight interim minimap target, scan the nearby remaining route for door-like scene objects that sit on the route segment. Include one or two raw edges before the closest path index; when the player is slightly off-path near a gate, the closest raw tile can already be on the far side of the gate edge. For diagonal hops beside small gates, also check the two cardinal sub-steps of the diagonal; the gate edge may sit on one of those sub-steps even when the direct diagonal segment does not equal the wall edge. Do not skip door probing just because the edge or object is catalogued as a transport when it is an `Open Gate` / door-like object transport. A raw-route scan may notice a future gate early, but the actual object interaction must still be range-gated against that gate edge before treating it as handled.

**Why this matters:** A short route near the Lumbridge farm allotment can correctly choose the gate as the shortest path, but the walker may see both sides as valid reachable tiles and click the minimap endpoint. The game then routes around the fence instead of opening the gate.

**Pattern to follow:**

```java
int scanStart = Math.max(0, closestRawIndex - 2);
if (bothEndpointTilesReachable
        && !hasDoorLikeSceneObjectOnSegment(from, to, playerLoc, HANDLER_RANGE)) {
    continue;
}
if (doorOpenedButPlayerDidNotTraverse) {
    // Only count this as success if the nudge actually reaches/crosses the door edge.
    tryDoorEdgeCrossNudge(from, to, currentTarget);
}
if (hasPendingDoorLikeSceneObjectBeforeDirectClick(rawPath, path, playerLoc, DIRECT_CLICK_MAX_DISTANCE)
        || handlePendingDoorBeforeRouteClick(rawPath, path, i, targetIdx, smoothedToRaw, timeoutMs,
        attemptedDoorEdgesThisPass, playerLoc)
        || handlePendingDoorNearRawPath(rawPath, timeoutMs, attemptedDoorEdgesThisPass, playerLoc, 2, 14)
        || handlePendingDoorDuringInterim(rawPath, timeoutMs, attemptedDoorEdgesThisPass, playerLoc)) {
    return WalkerState.MOVING;
}
```

**Where this applies:** `Rs2Walker.tryDirectShortWalk`, route checkpoint/minimap click selection, unreachable-smoothed-tile recovery, interim minimap movement waits, post-open door-edge nudges, `Rs2Walker.handleDoorsInRawSegment`, and any future optimization that skips route-object probing because tiles look locally reachable.

**Defensive check:** Reproduce from the Lumbridge farm road toward a target southwest of the allotment with both gates closed; the route should open each gate as it enters handler range, not stay in `interim-in-flight` until the server path has already routed around the field.

## 11. Clear sticky minimap interim targets outside the click branch

Sticky interim targets prevent click thrash while the player is moving toward a minimap checkpoint, but they are only useful while the checkpoint is still ahead. Clear them at the start of each walk pass when the player is already within the close threshold, the target is on another plane, or the checkpoint has aged out. Also clear them when stall-recalc fires, otherwise the replan can inherit the same stale checkpoint and spin without issuing a new movement command.

**Why this matters:** Long post-transport routes through cluttered areas can stop one tile from the sticky interim target. If the next pass does not enter the checkpoint-click branch, the stale interim remains in diagnostics and each stall-recalc repeats the same state until the tail iteration limit exits.

**Pattern to follow:**

```java
if (shouldClearInterimTarget(interimTargetWp, Rs2Player.getWorldLocation(), interimSetAtMs,
        interimLastProgressAtMs, nowMs)) {
    clearInterimTarget("close-or-expired");
}
if (isStuckTooLong()) {
    clearInterimTarget("stall-recalc");
    recalculatePath();
}
```

**Where this applies:** `Rs2Walker.processWalk`, recovery minimap clicks, post-transport walking, and any future logic that stores a sticky route checkpoint across loop iterations.

**Defensive check:** Reproduce a long route after the Falador crumbling-wall shortcut toward Ardougne through the dead-tree field; if the player reaches one tile from the interim checkpoint, the next pass should log `interim_clear` and select a fresh movement target instead of repeating `STALL_RECALC` until `tail_max`.

For long open routes, retarget before the player fully stops when they are already close to the interim checkpoint, and keep normal minimap clicks slightly inside the observed minimap edge. This reduces visible stop/start pauses and outside-clip fallback clicks without reintroducing rapid click thrash.

## 12. Do not let optimistic recovery override unresolved door blockers

Unreachable-tile recovery is useful for outdoor false negatives, but in tight rooms it can fight the door resolver. If a route edge still has a door-like scene object on or adjacent to the raw path, suppress broad minimap recovery and let the door scanners retry after their normal cooldowns. Do not permanently blacklist a path-adjacent fallback door just because one attempt traversed the wrong way; in small door clusters the same object may be the correct blocker again once the player has moved to the other side.

**Why this matters:** In POH-style tight rooms with several doors close together, a fallback door click can move the player away from the intended route. If that door tile is session-blacklisted and optimistic recovery keeps clicking route tiles beyond the blocker, the walker loops around the room until a user manually opens the final door.

**Pattern to follow:**

```java
if (tryResolvePathAdjacentBlocker(...)) {
    return MOVING;
}
if (hasUnresolvedDoorLikeObjectNearRawPath(...)) {
    return MOVING; // retry door handling next pass; do not broad-click recovery
}
clickOptimisticRecoveryTarget();
```

**Where this applies:** `Rs2Walker.processWalk` unreachable-tile handling, `tryResolvePathAdjacentBlocker`, and any fallback that issues minimap recovery clicks after door/path-adjacent scans fail.

**Defensive check:** Reproduce a route through a small room with three nearby doors and a POH portal. The walker should retry the route-door blocker and avoid repeated `unreachable optimistic recovery` loops around the room; it should not need the user to manually open the final door.

## 13. Stall recalculation must also issue fresh movement

Recalculating a path after a stationary stall is not enough by itself. If the player is idle and the next loop still cannot enter a normal click branch, repeated `STALL_RECALC` logs can continue forever until a user manually nudges the player. After clearing stale interim state and refreshing the route, issue a conservative minimap click along the reachable raw route so the server pathfinder gets a new movement command immediately. On active long routes, also nudge after a short stationary idle window, around a few ticks, instead of waiting for the full stall threshold.

**Why this matters:** Long routes can stop on a tile with no combat, animation, or interaction. Repeated stall recalcs refresh pathfinding state but leave the character standing still, so the route only resumes after manual movement changes the local path context.

**Pattern to follow:**

```java
if (isRouteActive() && playerIsIdleForShortWindow()) {
    tryIssueRouteRecoveryClick(rawPath, path, target);
    continue;
}
if (isStuckTooLong()) {
    clearInterimTarget("stall-recalc");
    setTarget(target);
    if (playerIsIdle()) {
        tryIssueRouteRecoveryClick(rawPath, path, target);
    }
    continue;
}
```

**Where this applies:** `Rs2Walker.processWalk` stall-recalc handling and any future stale-state recovery that clears route state while the player is idle.

**Defensive check:** Start a long route and observe a stationary pause. A short idle pause should log `active route idle nudge`; if it reaches full stall recalc, the next log sequence should include `stall recovery click` and a position delta, not another idle-only `STALL_RECALC` loop at the same tile.

## 14. A ranged door click owns the approach movement

Object interaction can be dispatched several tiles from a door. Once the player starts moving toward the door's near-side edge, release the synchronous door await and retain that approach as in-flight route ownership. Do not burn the full traversal timeout waiting for an edge the player has not reached yet, and do not let recovery or another raw door probe replace the queued interaction while the approach is progressing.

**Why this matters:** A four-tile approach can take longer than the door traversal wait. Treating ordinary approach movement as an unresolved traversal produces a timeout, followed by `door_recovery_suppressed` exits and a multi-second idle-nudge holdoff even though the original click is still working.

**Pattern to follow:**

```java
if (movingTowardDoorNearSide(clickPosition, playerPosition, from, to)) {
    releaseDoorAwait("approach-started");
    retainDoorApproachUntilEdgeResolutionOrMovementStops();
    return MOVING;
}
```

**Where this applies:** ranged scene-object door interaction, `Rs2WalkerAwaits`, recent-door recovery gates, and any fallback scanner that can run before the queued interaction reaches the object.

**Defensive check:** Interact with a door from three or four tiles away. The await should release as `approach-started` after the first position delta, recovery should yield while distance to the near-side edge decreases, and no second door interaction should fire before the approach stops or the edge resolves.

After a handled transport, avoid expensive path-adjacent or raw transport scans on ordinary open-ground segments unless a nearby planned transport or recent door attempt exists. Those scans are recovery tools, and on long outdoor routes a no-op scan can add several seconds before the next minimap click.

The retained `processWalk` continuation/interim rules are legacy-only rollback behavior. They must
not be used to give the active executor a second movement owner or to release a checkpoint early.

When a route-following minimap click is outside the minimap clip, fallback clicks must stay on the raw path. A generic "reachable tile closer to target" fallback can select a tile far away from the route in open areas, especially near the final destination.

For adjacent same-plane shortcuts, do not treat any movement away from the origin as success. Some shortcuts, such as stepping stones, can fail and place the player on a fallback tile; once the player is settled away from the expected destination, stop the landing wait and replan from the actual tile.

## 15. Match transport execution to its interaction mechanism and interface family

Transport rows do not all represent scene-object clicks, and related networks can use different widget groups. Before admitting new transport data, verify that the walker has an execution branch for the row's actual interaction and selects the interface from the origin object ID. Fail closed for unknown object IDs and tightly identify object-less item actions by their exact origin, destination, action, target, and item requirement.

**Why this matters:** Barrows mound entries use a spade inventory action and therefore have object ID `0`; the generic object executor skips them. River Lum and River Dougne canoe stations open different map interfaces, so waiting unconditionally for the Lum map makes every Dougne route time out.
**Pattern to follow:**

```java
if (isExactItemActionTransport(transport)) {
    interactRequiredItem();
    awaitDestination();
    return finishHandledTransport(transport);
}

int mapComponent = mapComponentForOriginObject(transport.getObjectId());
if (mapComponent < 0) {
    return false;
}
```

**Where this applies:** `Rs2Walker.handleTransports`, specialized transport handlers, and shortest-path transport resource additions.

**Defensive check:** Add pure unit tests for exact item-action recognition and for every supported origin-object-to-interface mapping, plus a loader test proving required item and unlock fields survive TSV parsing.

## 16. Yield object-transport waits when dialogue opens

An object transport can open quest dialogue instead of moving the player immediately. When that happens, end the synchronous transport landing wait and propagate that yield through the outer `processWalk` loop by returning `MOVING` to the calling script. The quest layer owns the dialogue response; the walker should not answer it or keep retrying the object.

**Why this matters:** Quest walking and dialogue handling may run on the same script thread. If the walker keeps waiting for a destination or retries the door, the quest script cannot process the dialogue and movement appears stuck.

**Pattern to follow:**

```java
boolean dialogueWasOpen = Rs2Dialogue.isInDialogue();
dispatchObjectTransport(...);
boolean landed = waitForPostHandleObjectLanding(..., dialogueWasOpen);
boolean dialogueOpened = !dialogueWasOpen && Rs2Dialogue.isInDialogue();
if (!landed && dialogueOpened) {
    return true; // handled this transport pass
}
return landed;

// In processWalk, after recording the handled transport/door:
if (Rs2Dialogue.isInDialogue() && isRouteProgressExit(exitReason)) {
    return WalkerState.MOVING; // caller can now process the dialogue
}
```

Only the closed-to-open transition counts as the object transport's handled result. The outer
handoff remains level-triggered after route progress has been independently established, so the
calling quest layer can also resume when genuine landing progress coincides with an existing
dialogue; an open dialogue by itself does not create a route-progress exit.

**Where this applies:** `Rs2Walker.handleObject`, post-object transport landing waits, and quest-driven doors or shortcuts that can display dialogue.

**Defensive check:** Open a quest door that displays dialogue. The walker should log `object_transport_dialogue_yield`, then `route_dialogue_yield_to_caller`, and then allow a `quest-helper:dialogue-space-step` without another door click.

## 17. Keep one strict owner for every accepted movement checkpoint

An accepted minimap or canvas checkpoint remains owned until the player passes its raw-path index,
reaches within one Chebyshev tile, or genuinely approaches it from outside the five-tile band to
within five Chebyshev tiles. A checkpoint accepted inside that band remains owned until it reaches
within one tile, has index progress, or enters bounded recovery. Run speed does not release ownership early. Door and transport actions preempt
the checkpoint and require a fresh route observation before movement resumes.

**Why this matters:** Releasing a checkpoint merely because a running player entered a larger
overlap radius allowed repeated decisions from stale route state. That caused extra clicks, visible
stop/start cadence, and competing movement while the first command was still active.

**Pattern to follow:**

```java
boolean passed = observation.getPathIndex() > checkpointPathIndex;
boolean reached = player.getPlane() == checkpoint.getPlane()
        && player.distanceTo2D(checkpoint) <= 1;
boolean handoff = checkpointStartedOutsideFiveTiles
        && player.getPlane() == checkpoint.getPlane()
        && player.distanceTo2D(checkpoint) <= 5;
if (passed || reached || handoff) {
    WebWalkLog.checkpointReleased(passed ? "passed" : reached ? "reached" : "handoff",
            checkpoint, checkpointPathIndex, player, observation.getTick());
    session.clearCheckpoint();
}
```

When an exact route edge becomes actionable, log `released=route-action`, clear the checkpoint
before interaction, dispatch only that route action, and wait for a fresh observation. A failed
movement dispatch whose resolved target is `null` is not an accepted checkpoint and must consume
the bounded rejection budget instead.

**Where this applies:** `WebWalkExecutor`, `WebWalkSession`, movement dispatch results, and every
door or transport implementation behind `RuneLiteWebWalkRuntime`.

**Defensive check:** The executor sequence must be movement, wait, route action, wait, new movement,
wait. Walking and running must keep the same checkpoint until it is passed or reaches the five-tile
handoff after beginning outside that band, except that any accepted checkpoint releases once it is
within one tile.

### Match checkpoint handoff geometry to minimap selection

Minimap route targets are selected inside a circular Euclidean radius, so the early-handoff check must use that same Euclidean radius. Do not use `WorldPoint.distanceTo2D` for this check: its Chebyshev (maximum-axis) distance can classify a diagonal checkpoint as close before the player has made meaningful progress toward it. Keep the separate close/arrival threshold unchanged.

**Why this matters:** A checkpoint seven tiles away on both axes is within eight tiles by Chebyshev distance but almost ten tiles away geometrically. Releasing that checkpoint immediately defeats sticky-target pacing and causes repeated route processing and visible stop-start movement.

**Pattern to follow:**

```java
long dx = (long) player.getX() - checkpoint.getX();
long dy = (long) player.getY() - checkpoint.getY();
boolean readyForHandoff = dx * dx + dy * dy <= (long) radius * radius;
```

**Where this applies:** `Rs2Walker` interim waits, active-route yield decisions, and any future minimap checkpoint handoff logic.

**Defensive check:** For a running handoff radius of eight, `(7, 7)` must remain in flight while `(8, 0)` may hand off. Recovery and the five-tile checkpoint-clear contract must remain unchanged.

Apply the route handoff at the start of the next walk pass, before collision, door, and transport scans, and apply the same decision if the path loop revisits the checkpoint in the same pass that issued it. Waking the pass at the larger pre-click radius without releasing the route-owned checkpoint there still lets those scans consume the remaining movement time and produces a stop before the continuation click. Require observed distance progress before this early release so a newly issued seven-tile checkpoint is not immediately replaced by an eight-tile running handoff. Tag recovery-owned checkpoints separately and keep them on the five-tile clear threshold.

## 18. Exit a walk when the local player disappears

Login transitions, connection loss, profile changes, and client shutdown can make `Client.getLocalPlayer()` return null while a blocking walk is still inside a movement wait. Treat a missing player location as a normal walk exit. Do not dereference it for an arrival-distance check, and do not keep a stale walker target active.

**Why this matters:** A Fishing return walk reached its anchor, then the local player disappeared during the final idle wait. `Rs2Player.getWorldLocation_Internal()` threw on the client thread and `Rs2Walker.processWalk()` immediately threw again while calculating the final distance.

**Pattern to follow:**

```java
WorldPoint player = Rs2Player.getWorldLocation();
if (player == null) {
    setTarget(null, "rs2walker:player-unavailable");
    return WalkerState.EXIT;
}
int distance = player.distanceTo(target);
```

**Where this applies:** `Rs2Player.getWorldLocation_Internal()`, blocking `Rs2Walker` waits, final arrival checks, and exception/diagnostic paths that run during login or shutdown transitions.

**Defensive check:** Unit-test the local-player-null resolver, and guard every post-wait distance calculation before dereferencing the returned `WorldPoint`.

## 19. Let the normal route selector own the first movement click

Before the first movement click, skip speculative local-reachability recovery for unreachable smoothed waypoints. The startup path already avoids broad segment handlers, and the normal route selector can choose a collision-reachable raw-path point. Keep the final `handlePendingDoorBeforeRouteClick` guard so a real nearby door is still resolved before dispatch.

**Why this matters:** A short route can contain a smoothed waypoint across a wall even though its raw route is valid. Treating that waypoint as a blocked frontier during startup runs several door and transport timeout windows, replans a walled recovery target, and can let the idle nudge issue the first click away from the goal. In the observed Varrock route, pathfinding completed in 344 ms but this recovery cascade delayed the first action until 6.8 seconds.

**Where this applies:** the `Rs2Walker.processWalk` local-reachability branch before `firstMovementClickMarked`, startup obstacle policy, and the final route-click door check.

**Defensive check:** On a fresh walk with an unreachable smoothed waypoint but a usable raw route, logs should progress from `preclick_segment_handler_skip` to `click_candidate_found` and `first_minimap_click`; they should not emit `frontier rewind`, `recovery_target_walled`, or `active_route_idle_nudge` before that first click.

## 20. Treat passive cursor motion as position, not a WebWalker takeover

The real canvas listener must continue recording `MOUSE_MOVED` and `MOUSE_DRAGGED` coordinates in
`PointerState`, but position samples alone must not mark `InputArbiter` as HUMAN. Only an actual
mouse-button or keyboard gesture may interrupt script waits and route ownership.

**Why this matters:** When motion was measured against the last synthetic cursor position, moving
the physical cursor more than ten pixels while hovering the client marked HUMAN for 1.8 seconds.
Continuous motion repeatedly renewed that window, causing `Script`, `Global.sleepUntil`,
`VirtualMouse`, and `Rs2Walker` to pause or reject work even though the user never clicked.

**Pattern to follow:**

```java
PointerState.setFromReal(canvasX, canvasY); // position only

// Ownership begins with deliberate input, not hover motion.
InputArbiter.onRealButtonPressed(button);
InputArbiter.onRealKeyPressed(keyCode);
```

**Where this applies:** `CanvasInputListener`, `InputArbiter`, `Global` waits, `Script.run`,
`VirtualMouse`, `Rs2Walker`, and any movement executor that consults human-input ownership.

**Defensive check:** Dispatch repeated real `MOUSE_MOVED` events over the canvas and assert the
pointer changes while `InputArbiter.isHuman()` stays false and a real `Global.sleepUntil` continues
polling. Keep separate tests proving real button/key gestures still yield.

## 21. Scan the full actionable transport horizon and trust exact arrival

See [Transport Handoff: Preventing Immediate Reverse Traversal](../transport-handoff-fix.md) for
the complete Edgeville failure analysis, one-shot inverse-edge suppression design, regression
coverage, and live acceptance criteria.

The route-action scan must inspect every raw-path edge within the catalog transport interaction
radius, not only the next one or two list entries. After a transport lands, an exact player-target
match is authoritative even if a superseded path snapshot still contains the inverse transport.
Keep the wider scan limited to catalog-backed transports; generic blocked edges retain their
smaller interaction radius.

**Why this matters:** At the Edgeville trapdoor, the transport origin can be five raw-path indices
ahead while only four tiles from the player. A two-index scan ground-clicks the trapdoor tile first.
After climbing down, a stale reverse-ladder edge can then reject exact arrival and send the player
straight back to the surface.

**Pattern to follow:**

```java
int lastEdge = Math.min(path.size() - 2, currentIndex + CATALOG_ACTION_DISTANCE);
if (player.equals(target)) {
    return ARRIVED;
}
```

**Where this applies:** `RuneLiteWebWalkRuntime.routeActionIndex`, `Rs2Walker.runtimeArrived`, and
transport handoff/replan boundaries.

**Defensive check:** Model a catalog transport five raw-path indices ahead and assert it preempts
movement. Separately assert that exact physical arrival succeeds despite a stale inverse edge,
while non-exact configured-distance arrival still honors pending route interactions.

## 22. Translate dungeon display coordinates and gate shortcuts by their real unlock

World-map display coordinates are not necessarily the player's real tiles inside coordinate-shifted
dungeons. Convert a dungeon-map selection through the existing world-map-area mapping, then
normalize any canonical plane representation to the physical coordinates used by the pathfinder.
Reject only when the selected map coordinate cannot be mapped to a real dungeon tile. Transport
data for reward shortcuts must also include the exact varbit that unlocks the interaction; an object
being visible and accepting a click does not prove that it can transport the player.

**Why this matters:** On the second Stronghold of Security floor, a world-map click produced a
surface/display target outside the floor. The resulting route searched the wrong component and an
unconditional Famine portal row then diverted the route to a locked portal instead of the gates.
The object click returned success after merely walking to the portal, while the expected landing
never occurred.

**Pattern to follow:**

```java
if (insideKnownDungeonFloor(player) && selectedPointIsDungeonMapCoordinate) {
    selectedMapPoint = convertMapPointToPhysicalDungeonTile(selectedMapPoint);
}
if (shortcutRequiresReward) {
    transport.addVarbitRequirement(rewardVarbit, 1);
}
```

Run static target walkability preflight before starting a new pathfinder. Otherwise a bad target
starts a search that the same call immediately cancels when preflight rejects the coordinate.

**Where this applies:** `ShortestPathPlugin` world-map target selection, dungeon shortcut TSV data,
`Rs2Walker.walkWithStateInternal`, and any transport whose availability changes after a reward.

**Defensive check:** Load the production transport resources and assert the shortcut carries its
unlock requirement. Test a literal dungeon display coordinate against its hand-derived physical
tile, and assert target preflight precedes pathfinder startup.

## 23. Keep item-gated plain transports through bank-route filtering

Bank-route pathfinding may use a plain `TRANSPORT` because an item in the bank satisfies its
requirement. The later path-to-transport scan must retain that row when it has either an item or
currency requirement; otherwise the withdrawal planner sees an empty list and starts the direct
walk without fetching the item.

**Why this matters:** A Varrock Sewers route correctly found the slashable web while bank items
were enabled and selected the faster banking route. An older filter then discarded the web because
it was a plain transport with no currency fare, producing `direct_no_missing_items` after the bank
had opened and leaving the knife and scimitars in the bank.

**Pattern to follow:**

```java
Rs2WalkerBankingPlanner.planningCoversPlainTransport(transport)
```

Use the shared predicate in both eligibility checks and path filtering. Do not duplicate a narrower
plain-transport rule at either boundary.

**Where this applies:** `Rs2Walker.applyTransportFiltering`,
`Rs2WalkerBankingPlanner.planningCoversPlainTransport`, and any future bank-route transport scan.

**Defensive check:** Pass a production `Slash;Web` transport through the filtered-path boundary and
assert it remains present so its cutting-tool requirement reaches the withdrawal map.

After a successful slash, the web object can disappear before the player has crossed its edge.
Treat that state as a reason to re-path through the now-open tile, not as a confirmed transport
landing. The normal landing check remains authoritative for completing the transport.

## 24. Inspect future route transports in world space before consulting the live scene

A banked route is often inspected while its later dungeon tiles are not loaded. Always match the
transport's canonical world origin against the planned path. Instance-local copies may be added as
alternatives when a world view exists, but a scene conversion must not replace the canonical origin
or gate matching on the player's current plane.

**Why this matters:** From Varrock East bank, the bank-enabled path used the slashable sewer web,
but the withdrawal scan converted that underground origin through the currently loaded surface
scene. The conversion returned no point, so the planner reported `direct_no_missing_items` and left
the knife and scimitars in the bank.

**Where this applies:** `Rs2Walker.getTransportsForPath` and any route analysis that inspects
transports beyond the currently loaded scene.

**Defensive check:** Feed the production Varrock sewer web edge to the path-transport extractor in
a headless/off-scene test and assert the web reaches the missing-item planner.

## 25. Revalidate quest targets at the walk-to-interaction handoff

Use `Rs2Walker.walkWithStateUntil` when an NPC or object becoming locally actionable should
end a full route. Its completion callback must only inspect state; perform the actual click
after return and re-read the active step and live target first. `ARRIVED` can mean coordinate
arrival or caller-condition satisfaction, so it is not an unconditional interaction permit.

**Why this matters:** Waiting for another quest loop after route arrival adds visible idle time.
Reusing the pre-walk target instead can click a stale NPC, object variant or completed step.
Replacing full walking with `walkStep` would also omit doors and transports.

Readiness collision checks use physical scene positions for both player and target, including
inside instances. Convert only the full walker's destination to its expected template space.
Do not compare template player coordinates against physical scene objects. Preserve one
pending interaction while movement or animation is in progress; movement alone is not proof
that an object action completed.

**Where this applies:** `QuestScript` NPC/object handoffs, `QuestInteractionFlow`, and
`QuestLocalApproach`. See [Quest Helper interaction responsiveness](../questhelper-interaction-responsiveness.md).

**Defensive check:** Test same-invocation handoff, changed step/target after walking, closed
walls and instance coordinates, failed dispatch, and no second click through short animation gaps.
