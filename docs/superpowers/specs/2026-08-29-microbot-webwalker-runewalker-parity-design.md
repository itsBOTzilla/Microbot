# Microbot WebWalker RuneWalker 2.0 Parity Design

**Status:** Proposed for user review

**Date:** 2026-08-29

## Decision

Replace the active Microbot WebWalker movement policy with the proven RuneWalker 2.0 checkpoint-ownership model while retaining Microbot's public scripting APIs, nightly shortest-path planner, transport and door handlers, client-thread rules, input arbitration, and randomized run-energy policy.

This is a behavioral port, not a wholesale copy of RuneWalker's `Rs2Walker`. Scripts will continue to call the existing Microbot `Rs2Walker.walkTo`, `walkWithState`, and banking-aware entry points.

## Problem statement

The loaded `f42b030` build repeatedly replaces running checkpoints after only a few tiles. Its log advances include route-index changes such as 11 to 13 to 16 to 19, interleaved with rejected minimap dispatches reported as `actual=null`.

The behavior comes from the run-specific early-handoff policy added after the initial RuneWalker-inspired executor port:

- clear-route candidates are selected up to roughly 12 Euclidean tiles away;
- a running checkpoint becomes eligible for replacement at eight tiles;
- only two tiles of approach are required before replacement;
- each replacement performs another minimap dispatch;
- rejected far targets cause additional commands on following observations.

RuneWalker 2.0 does not use a run-specific early-handoff rule. An accepted checkpoint releases when the player passes its path index, reaches within one tile, or makes a genuine same-plane approach from outside five tiles to within five. Run energy affects player speed, not checkpoint ownership.

## Goals

- Keep open-ground walking and running continuous without walker-caused stop-and-go movement.
- Issue one authoritative movement command at a time.
- Select the furthest safe route point that Microbot can dispatch before the next route action.
- Prefer Microbot's existing canvas walk interaction for safe on-screen route points within five
  Chebyshev tiles, with a single minimap fallback when the point cannot be canvas-dispatched.
- Preserve all Microbot door, transport, dialogue, collision, pathfinder, cancellation, banking, and script-call contracts.
- Preserve randomized run activation using the shared inclusive 50-100 percent threshold.
- Keep WebWalker active while the user passively moves the real cursor over the game canvas;
  pointer motion alone is not an input takeover.
- Make rejected minimap targets bounded and recoverable instead of producing click churn.
- Keep nightly Microbot updates mergeable by concentrating custom behavior in narrow, tested files.
- Deploy every verified local build through one stable launcher artifact: `microbot-local.jar`.

## Non-goals

- Do not transplant RuneWalker's complete `Rs2Walker` class.
- Do not import RuneWalker branding, plugins, scripts, Antiban infrastructure, or private iBOT scripts.
- Do not use or decompile the obfuscated RLitePlus release JAR.
- Do not add a second public walking API.
- Do not tune location-specific routes to hide general movement defects.
- Do not remove legacy movement helpers until the replacement passes source, build, and live gates.

## Architecture

The active walker remains divided into four responsibilities:

1. `WebWalkExecutor` owns the deterministic command state machine.
2. `WebWalkSession` owns mutable state for one blocking walk call.
3. `RuneLiteWebWalkRuntime` adapts the state machine to Microbot and RuneLite state.
4. `Rs2Walker` retains public APIs and the existing route-action implementations for doors, transports, banking, arrival, and minimap dispatch.

The executor may choose at most one action from one immutable observation. It must then wait for a new tick, player position, target, or pathfinder observation before deciding again.

## Existing-script compatibility

The rewrite is installed behind the existing `Rs2Walker` facade. Existing first-party scripts, external plugins, and user scripts receive the new implementation automatically; they do not import a new class, enable a feature flag, or change a call site.

The following script-facing contracts remain source- and binary-compatible:

- every existing `walkTo` overload;
- `walkUntil` and `walkWithStateUntil`;
- every existing `walkWithState` overload;
- `walkWithStateTry` and its lock-timeout behavior;
- `walkStep`;
- `walkNextTo` and `walkNextToInstance`;
- banking-aware `walkWithBankedTransports` and `walkWithBankedTransportsAndState` overloads;
- current target, route clearing, path recalculation, and target replacement methods;
- `WalkerState` meanings and boolean adapter behavior.

Low-level direct movement helpers such as `walkMiniMap`, `walkFastCanvas`, and `walkCanvas` retain their signatures for compatibility, but ordinary WebWalker calls do not expose them as alternative movement owners.

Existing behavior at the facade boundary is preserved:

- null and client-thread calls fail through their current documented result paths;
- blocking methods remain blocking;
- `ARRIVED` means the configured arrival contract was satisfied;
- `MOVING` remains a non-terminal retry result where currently supported;
- `UNREACHABLE` and `EXIT` remain distinct terminal outcomes;
- banking-aware calls still acquire required transport items before delegating to the same new executor;
- target replacement and stop requests still cancel the active walk without allowing stale cleanup to clear a newer target.

Compatibility tests enumerate the public entry points and prove that each high-level walking path delegates to the replacement executor. Representative existing scripts from mining, runecrafting, questing, and banking receive compile and runtime smoke coverage without source changes.

## Checkpoint ownership

An accepted minimap dispatch creates one active checkpoint containing:

- the actual dispatched world point;
- its resolved raw-path index;
- the dispatch tick;
- bounded retry counters.

While a checkpoint is active, the executor does not select or dispatch another ordinary forward target. The checkpoint is cleared only when one of these conditions is true:

- the observed path index has passed the checkpoint index;
- the player is on the checkpoint's plane and within one Chebyshev tile of it;
- the player has genuinely approached a checkpoint that was initially farther than five tiles and
  is now on the same plane within five Chebyshev tiles of it;
- a route edge becomes actionable, which transfers ownership to route-action handling;
- the command makes no progress for the bounded grace period and enters redispatch or replan recovery;
- cancellation, target replacement, logout expiry, interruption, or terminal arrival ends the session.

Walking and running use the same checkpoint lifecycle. The following current concepts are removed from the active contract:

- `runEnabled` as an input to checkpoint-release decisions.

## Route observation and forward selection

Each observation captures the client tick, player world point, login state, current target ownership, pathfinder identity/completion, copied raw and walkable paths, current path index, reachable tiles, and the nearest actionable route edge.

Forward selection follows the RuneWalker 2.0 contract:

- begin at the closest forward path index consistent with session progress;
- scan forward on the player's plane;
- remain within the configured minimap radius;
- require the point to be present in the fresh reachable-tile snapshot;
- stop before a catalog-backed transport or unresolved route edge;
- choose the furthest valid point, not the next nearby point.

The reachability snapshot is captured once at the decision boundary and reused throughout that observation. Door or transport interactions invalidate the observation and force a fresh snapshot before subsequent movement.

## Close-range canvas dispatch

The executor continues to own one movement command at a time, while the Microbot runtime chooses
how to emit that command. A selected route target is eligible for canvas walking only when it is on
the player's plane and within five Chebyshev tiles. The runtime then uses the existing
on-screen-only Microbot canvas walk path. If projection or visibility rejects the canvas target
before input is emitted, the runtime may issue the normal route-safe minimap fallback from the same
observation.

This policy does not select a different world point. The target has already passed raw-path,
reachability, collision, radius, door, and transport boundaries. A successful canvas dispatch owns
the same checkpoint as a successful minimap dispatch. Run state does not affect the choice or
checkpoint lifecycle, and the canvas helper must not toggle run because run energy remains owned by
the shared randomized policy.

## Minimap dispatch

The runtime continues to use Microbot's existing minimap and input APIs. RuneWalker UI infrastructure is not imported.

Before emitting input, the runtime resolves the requested route point to the furthest dispatchable point on the same safe raw-path prefix. This resolution must use existing Microbot geometry, minimap, reachability, and collision APIs. It must not dispatch several candidate clicks from one observation.

Dispatch outcomes are explicit:

- accepted with an actual target: create the checkpoint using the actual target and resolved path index;
- rejected or `actual=null`: do not create a checkpoint and increment the rejection counter;
- repeated bounded rejection: request a fresh path rather than clicking indefinitely;
- interruption or a real button/key takeover: yield or terminate through the existing Microbot
  contracts. Passive cursor motion continues updating pointer position without changing ownership.

No synthetic click may be emitted from a stale observation.

## Doors, transports, and dialogues

Route actions have priority over minimap movement when their path edge is near enough to handle. The replacement retains the existing Microbot implementations for:

- doors and gates, including movement-aware approach handling;
- catalog transports and explicit destination validation;
- object transports and accepted adjacent landings;
- new-dialogue progress using false-to-true dialogue transitions;
- Barrows dig, canoes, and other nightly transport additions;
- collision disagreement and fresh reachability handling.

An accepted movement checkpoint is cleared before dispatching a route action. After a successful action, the executor waits for causal progress and obtains a new path/client observation before moving again.

## Run energy

Run activation remains the shared Microbot policy:

- sample an inclusive threshold from 50 through 100 percent;
- enable run only when energy meets the current threshold;
- reroll only after run activation succeeds;
- share the policy between scripts and the walker;
- do not use run state to shorten checkpoint ownership.

## Lifecycle and threading

- Public walk calls remain blocking and must not run on the client thread.
- Client state is copied through Microbot's client-thread facilities.
- No static sleep is used to wait for game state; waits are predicate-based and bounded.
- Target generation remains the cancellation and replacement owner.
- Stop and shutdown invalidate ownership before cancelling work.
- No worker may clear or replace a newer target.
- InputArbiter remains authoritative for real mouse-button and keyboard yielding and held-input
  cleanup. `MOUSE_MOVED` and `MOUSE_DRAGGED` position samples alone never claim HUMAN ownership,
  cancel a route, end a wait, or reject the next WebWalker command.

## Observability

Movement logs remain concise but must distinguish:

- requested target, resolved actual target, and path index;
- accepted versus rejected dispatch;
- active-checkpoint wait, reached, passed, or no-progress expiry;
- route-action ownership;
- replanning reason;
- terminal state and target generation.

Debug logging must not expose player identity, account information, tokens, proxies, or private script names.

## Automated verification

Tests are organized around behavior rather than private helper implementation.

### State-machine sequences

- A checkpoint first dispatched from outside five tiles releases at distance 5 with `handoff`; a checkpoint first dispatched inside that band remains `WAIT` until distance 1.
- Passing the checkpoint path index releases it immediately.
- Walking and running produce identical checkpoint decisions.
- Player movement resets progress timers without clearing the checkpoint early.
- A route action preempts an ordinary movement click.
- Cancellation and target replacement terminate without stale cleanup.

### Dispatch behavior

- The furthest reachable point before a transport is selected.
- A safe, on-screen route target within five tiles emits one canvas walk command.
- An off-screen or unprojectable close target emits one minimap fallback, never both inputs.
- Unreachable, cross-plane, and outside-radius points are rejected before input.
- `actual=null` never creates a checkpoint.
- Consecutive bounded rejection causes replan, not an unbounded click loop.
- One observation can produce at most one input action.

### Microbot integration

- All public walking and banking-aware entry points wire to the executor.
- No active call path falls back to legacy `processWalk` movement.
- Client-thread guardrails remain intact.
- Nightly InputArbiter, door, transport, pathfinder, and cancellation tests remain green.
- Real cursor motion over the canvas updates `PointerState` while WebWalker waits continue polling;
  real button and key gestures retain their existing yield and idle-resume behavior.
- The RuneWalker 2.0 behavioral fixtures are ported using Microbot package names and APIs.

## Live acceptance

The exact built and staged JAR must be tested with run enabled on at least:

1. a long open-ground route containing turns;
2. a route with a normal door;
3. a route containing a catalog transport;
4. a route that triggers replanning or collision disagreement;
5. a target replacement and explicit stop.
6. continuous real cursor movement over the hovered game canvas during an open-ground run.

Open-ground acceptance requires:

- no walker-caused stationary gap while an unobstructed route remains;
- no ordinary checkpoint replacement before it is reached, passed, or makes the guarded outside-five-to-within-five handoff;
- no route pause, cancellation, dispatch rejection, or cadence change caused only by cursor motion;
- no repeated 1-5-tile command churn on a clear segment;
- no off-route or through-wall click;
- no repeated rejected dispatch loop;
- bounded recovery when a target cannot be dispatched.

Doors and transports are allowed to pause movement only while their verified action owns the route.

## Nightly integration strategy

The pending upstream nightly merge is completed and verified before movement implementation begins. Future nightly updates are merged into local `main`; custom behavior is preserved through focused files and regression tests rather than by editing vendored planner sources or shortest-path resource data.

After each nightly merge:

1. inspect overlapping Walker, Script, input, pathfinder, and resource changes;
2. resolve conflicts without replacing custom files wholesale;
3. run focused parity and upstream regression suites;
4. run checkstyle, assembly, and privacy checks;
5. build and stage only after the tree is clean and verified.

## Launcher artifact contract

The launcher uses one stable custom artifact:

- file: `%USERPROFILE%\.microbot\microbot-local.jar`;
- launcher `version_pref`: `local`;
- embedded Microbot version and Git commit remain the authoritative identity;
- the filename never contains a commit hash or random suffix.

Deployment is transactional:

1. build to the Gradle output directory;
2. inspect embedded version, commit, and dirty state;
3. calculate the source JAR SHA-256;
4. copy to a temporary launcher filename;
5. stop the running local client;
6. replace `microbot-local.jar`;
7. set `version_pref` to `local` without modifying credential fields;
8. verify the staged SHA-256 matches the source JAR;
9. launch normally and confirm the log reports the expected commit;
10. remove obsolete launcher JARs.

After the first verified local launch, cleanup removes:

- `microbot-2.6.20.18.jar`;
- `microbot-2.6.20.19.jar`;
- every `microbot-2.6.21.public-*.jar` artifact.

The current official `microbot-2.6.21.jar` is retained as a single known rollback artifact. Future official version upgrades replace that rollback artifact rather than accumulating older releases.

## Rollout and rollback

The replacement lands in independently reviewable phases: parity tests, executor/session parity, Microbot runtime adapter, route-action qualification, live qualification, legacy retirement, and launcher cleanup.

If live acceptance fails, the launcher can select the retained official release. A source/build success is never reported as a live fix. The loaded log commit and staged hash must prove which implementation was tested.

## Privacy and contribution boundaries

- No iBOT or other private script source is included.
- Quest and death changes are excluded from this contribution.
- Review and pull requests target only `itsBOTzilla/Microbot`.
- No pull request is opened against `chsami/Microbot` unless separately and explicitly requested.
