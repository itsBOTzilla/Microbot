# Transport Handoff: Preventing Immediate Reverse Traversal

## Summary

WebWalker could complete a transport, start a route from the landing tile, and then immediately
select the inverse transport. At the Edgeville trapdoor this appeared as:

1. walk to the surface trapdoor at `WorldPoint(3096, 3468, 0)`;
2. climb down to `WorldPoint(3096, 9867, 0)`;
3. start a new walk to the brass key at `WorldPoint(3131, 9862, 0)`;
4. select the underground ladder back to `WorldPoint(3096, 3468, 0)`;
5. climb back to the surface instead of walking to the key.

The fix keeps the transport catalog intact and suppresses only the just-used transport's inverse
edge during the first meaningful path calculation after landing. The suppression is directional,
short-lived, and one-shot. A later route that genuinely requests the surface exit can still use the
ladder.

This is a general WebWalker transport-handoff fix. It does not add an Edgeville-specific route or
hard-code a path to the brass key.

## What the logs proved

The important diagnostic sequence was:

```text
transport_handoff_enter
    goal=3096,9867 at=3096,9867 dest=3096,9867

transport_handoff_suppress_next_path
    suppressed=3096,9867->3096,3468

walk_start
    goal=3131,9862 at=3096,9867
```

This proved that the surface-to-underground transport succeeded and that WebWalker did create the
correct underground walk. The failure was not a missing key coordinate, a missing dedicated route,
or `randomizeFinalTile`. The newly calculated path was allowed to reuse the inverse ladder edge.

Earlier failures also logged a stale destination after the underground walk had begun:

```text
post-handleObject landing unresolved
    dest=3096,3468 at=3096,9867
```

That surface destination is the inverse transport. It must not remain the preferred next edge after
the player has just landed underground and the active goal is also underground.

## Root cause

Surface and underground RuneScape locations can both use plane `0`; their different coordinate
layers are encoded primarily by the Y coordinate. A plane-change-only completion check therefore
cannot distinguish the Edgeville surface trapdoor from its underground landing.

After the object interaction completed, three pieces of state could disagree:

- the player was physically at the transport destination;
- the active request was about to change from the terminal entry tile to the real underground goal;
- a precomputed or restarted path could still contain the inverse ladder transport.

The transport catalog correctly contains both directions. Removing the return ladder from
`transports.tsv` would fix one route by breaking every legitimate exit route, so the catalog was not
the right place to solve the problem.

## Design requirements

The repair follows these rules:

1. Physical arrival at the observed transport destination is authoritative.
2. Suppress only the directed inverse edge: landing to previous origin.
3. Apply suppression only to the first meaningful path after the handoff.
4. Do not consume suppression on the terminal `start == goal` entry walk.
5. Do not suppress the inverse when the caller explicitly requests that coordinate layer.
6. Expire unused suppression so unrelated future walks cannot inherit it.
7. Apply the same edge suppression to forward and reverse path searches.
8. Keep all other transports, including the legitimate return ladder, available.

## Implementation

### 1. Confirm the observed landing

`Rs2Walker` checks the player's current location after a successful transport action. The landing
is accepted when it is on the destination's plane and within three tiles of the catalog destination.
The small tolerance handles object transports that settle the player beside, rather than exactly on,
their catalog landing tile.

```java
boolean landedAtDestination =
        isConfirmedTransportLanding(currentLocation, transportDest);
```

This wider tolerance is used only after the transport handler reports success. It does not make
transport discovery or general route selection permissive.

### 2. Reject an unsafe precomputed continuation

A precomputed continuation is not automatically safe merely because it exists. Once landing is
confirmed, `Rs2Walker` checks whether that continuation contains the reverse transport family. A
continuation containing the landing-to-origin edge is discarded and pathfinding is restarted.

```java
boolean continuationContainsReverse = landedAtDestination
        && precomputedContinuationContainsEdge(
                transportDest,
                transport.getOrigin());
```

Nearby endpoint matching is used for transport-family detection because a transport may have
multiple catalog variants around the same entrance or landing. When the endpoint neighborhoods
overlap, as they do for adjacent same-plane doors and shortcuts, detection falls back to the exact
directed edge so an ordinary A-to-B continuation cannot be mistaken for B-to-A.

### 3. Carry one-shot suppression across a terminal handoff

The first walk often targets the transport destination itself. Once the player lands at
`3096,9867`, that entry walk is already complete. The caller then starts the meaningful walk to
`3131,9862`.

If inverse-edge suppression were attached only to the already-complete entry path, it would be
lost before the brass-key path started. `Rs2WalkerLifecycleRuntime` therefore stores a pending
suppression containing:

```text
origin      = 3096,9867,0
destination = 3096,3468,0
createdAt   = current time
```

The pending suppression:

- survives a no-op `start == goal` recalculation;
- is consumed by the next meaningful walk starting near its origin;
- expires after 15 seconds;
- is discarded if the next walk starts elsewhere;
- is not applied when the requested target is on the suppressed destination's coordinate layer.

The last rule preserves explicit exit routes. A walk from the dungeon to an Edgeville surface bank
is allowed to use the ladder because reaching the surface is the requested result.

### 4. Suppress the inverse inside pathfinding

The pending directed edge is passed into the new `Pathfinder`. `CollisionMap` filters that edge when
adding transport neighbors:

```java
if (isSuppressedTransportEdge(
        node.packedPosition,
        destinationPacked,
        suppressedTransportOrigin,
        suppressedTransportDestination)) {
    continue;
}
```

The same check is applied during reverse-neighbor generation, so bidirectional search cannot
reintroduce the inverse edge from its other frontier.

The match uses same-plane, nearby endpoint families rather than object ID or description text when
the endpoint neighborhoods are directionally distinguishable. Family suppression is disabled for
nearby same-plane endpoints whose radius-three neighborhoods overlap; those local doors and
shortcuts retain their exact-direction and existing handled-point behavior.

### 5. Reject stale pathfinder results for another target

`RuneLiteWebWalkRuntime` now waits unless the completed pathfinder actually contains the active
target:

```java
pathfinder.getTargets().contains(target)
```

This prevents a completed path for the terminal entry tile from being treated as the route for the
new brass-key request.

### 6. Trust exact final arrival

When the player is exactly on the requested target, `Rs2Walker.runtimeArrived` returns success before
a stale path snapshot can make an inverse transport appear pending. Configured-distance arrival still
honors unresolved route interactions; only exact physical arrival receives this authority.

## Resulting handoff flow

```text
surface route
    -> interact with trapdoor
    -> observe underground landing
    -> mark underground-to-surface inverse for one-shot suppression
    -> complete terminal entry walk
    -> start meaningful underground walk
    -> consume suppression
    -> calculate underground path without inverse ladder
    -> walk toward brass key
```

The return route remains:

```text
explicit surface goal
    -> do not apply underground-to-surface suppression
    -> calculate route using the legitimate ladder
    -> climb to surface
```

## Regression coverage

The focused tests cover the contracts rather than only the Edgeville symptom:

- a path from the dungeon entrance to the brass key cannot contain the surface landing;
- an explicitly requested exit path can still contain the surface landing;
- an observed landing a couple of tiles from the catalog destination is accepted;
- reverse-edge detection is directional;
- nearby transport catalog variants are recognized as one transport family;
- adjacent same-plane A-to-B and B-to-A edges are not collapsed into one family;
- pending suppression carries into the key walk;
- a terminal `start == goal` calculation does not consume it;
- an explicit surface route does not inherit it;
- exact physical arrival overrides a stale inverse edge;
- the active WebWalker target must match the completed pathfinder's target set.

Relevant test classes:

- `ShortestPathCoreTest`
- `CollisionMapTransportSuppressionTest`
- `Rs2WalkerUnitTest`
- `RuneLiteWebWalkRuntimeTest`

## Live acceptance criteria

Unit tests and a built JAR prove implementation, not loaded runtime behavior. Qualify this repair
through the normal Microbot launcher and verify the startup commit before testing.

A successful live run must show:

1. the player lands at or near `3096,9867,0`;
2. `transport_handoff_suppress_next_path` identifies
   `3096,9867,0 -> 3096,3468,0`;
3. a new `walk_start` targets `3131,9862,0` from the underground coordinate layer;
4. movement continues east through the dungeon;
5. the player does not climb back to `3096,3468,0`;
6. a separately requested surface exit still works.

The approach click used to reach or interact with the surface trapdoor is a separate route-action
selection concern. A visually offset minimap approach click does not mean the inverse-edge handoff
suppression failed; diagnose it through `routeActionIndex`, the requested/actual click coordinates,
and the route-action checkpoint logs.

## Files involved

- `Rs2Walker.java`: landing confirmation, continuation validation, handoff restart, and exact arrival.
- `Rs2WalkerLifecycleRuntime.java`: pending one-shot suppression and pathfinder construction.
- `Pathfinder.java`: immutable suppressed-edge inputs for the path calculation.
- `CollisionMap.java`: forward and reverse transport-edge filtering.
- `RuneLiteWebWalkRuntime.java`: active-target/pathfinder identity validation.
- `ShortestPathCoreTest.java`: Edgeville entry-to-key and explicit-exit regression coverage.
- `Rs2WalkerUnitTest.java`: handoff, transport-family, carryover, and arrival contracts.
- `RuneLiteWebWalkRuntimeTest.java`: active target/pathfinder behavior.
