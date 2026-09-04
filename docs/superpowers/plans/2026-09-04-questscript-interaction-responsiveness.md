# QuestScript Interaction Responsiveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. This document authorizes no implementation, build, installation, restart, commit, or publication by itself.

**Goal:** Remove avoidable waiting between route readiness, NPC/object interaction, and dialogue progression without duplicate clicks or broken door/transport routing.

**Architecture:** Retain the full WebWalker executor and use its existing caller-defined completion condition. QuestScript owns fresh target validation, one interaction attempt at a time, and observation of that attempt. Replace blanket timing with a small quest-local pending-action policy; do not rewrite all quest handlers or shared movement.

**Tech Stack:** Java 11 target, existing Microbot utilities, JUnit 4, Gradle on Windows/PowerShell.

**Spec:** The requirements and acceptance matrix below capture the user's September 4 review request and subsequent request to plan the optimizations.

## Global constraints

- Planning only until implementation is requested.
- Preserve existing dirty work, especially `RuneLiteWebWalkRuntime.java` and `Rs2WalkerWalkingCameraTest.java`; do not reset, stage, or include it opportunistically.
- Work in `C:/Users/clanb/Desktop/microbot/Microbot`; confirm HEAD and branch before execution. Review checkpoint: `799b871276`, `integration/private-launcher-2.6.22`.
- No commit, push, PR, or game restart without session authorization for that action.
- Read repository AGENTS.md, the microbot subtree AGENTS.md, and `docs/entity-guides/movement.md` and `items.md` before implementation.
- Game/widget reads belong on the client thread. Completion callbacks must be read-only and cheap; never run global pathfinding, sleep, or click from them.
- Preserve raw `Rs2GameObject.interact(TileObject, action)` dispatch, instance-aware object resolution, inventory-selection confirmation, input ownership, cutscene guards, and cancellation.
- Do not globally shorten door cooldowns, change shared mouse timing, or substitute `walkStep()` for full routing.
- A dispatched click, movement, or animation is not proof that a quest step completed.
- Separate source validation, packaged artifact, staged artifact, and observed runtime results.

## Evidence and existing capabilities

Paths below are relative to the Microbot repository. Line numbers are review-time anchors.

| Exists today | Location | Implication |
|---|---|---|
| Random 400–1,000 ms fixed delay, chosen when the scheduler starts | `runelite-client/src/main/java/net/runelite/client/plugins/microbot/questhelper/QuestScript.java:111,328` | Adds latency after each completed iteration; it is not randomized anew each tick. |
| 4–7 second post-dialogue cooldown | Same file, `263–275` | Explicit waiting even when the next step is ready. |
| Activity wait followed by idle wait after generic step dispatch | Same file, `298–321` | Adds redundant waits after helpers already waited; idle helper defaults to 5 seconds. |
| NPC walking returns without same-call interaction re-evaluation | Same file, `1398–1407` | Arrival often pays another scheduler cycle. |
| Object pre-click idle gate and post-click waits | Same file, `1506–1540,1581` | Moving players defer attempts; nested waits delay reevaluation. |
| Full routing with an opt-in completion condition | `.../util/walker/Rs2Walker.java:1477–1525`, `walkWithStateUntil(WorldPoint,int,BooleanSupplier)` | Reuse this to yield when an interaction target is ready. ARRIVED can mean coordinate arrival OR condition satisfied. |
| Partial approach-only stepping | Same file, `1760–1777`, `walkStep(...)` | Explicitly excludes the full transport/door/recovery pipeline; unsuitable as a blanket replacement. |
| Door throttle exits before dispatch | Same file, `6494` | Repeated throttle logs are suppressed attempts, not evidence of repeated clicks. |

Runtime checkpoint: PID 29524 launched `C:/Users/clanb/.microbot/microbot-local.jar` at 14:44:32. Its QuestScript bytecode contains the cooldown and scheduler constants. This is a checkpoint, not future runtime identity proof. Do not assume its entire source tree equals current HEAD.

The supplied logs show 1–3 seconds from walker ARRIVED to entry into an interaction branch. Object branch logging occurs before its idle gate and click. Logs alone do not measure target-ready time, dispatched-click time, or successful action time.

## Proposed files and boundaries

Base production directory: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/questhelper/`.
Base test directory: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/questhelper/`.

- Modify `QuestScript.java`: integrate routing completion, fresh target lookup, dialogue readiness, and pending-action state; preserve existing public boolean handler signatures for compatibility.
- Create `QuestInteractionAttempt.java`: package-private, pure state for one pending attempt; no client access or scheduling.
- Create `QuestInteractionAttemptTest.java`: deterministic timing and completion tests.
- Create `QuestInteractionFlowTest.java`: behavioral tests exercising handler flow with controlled observations and action counters.
- Extend `QuestObjectInteractionDispatchTest.java`, `QuestObjectApproachSelectionTest.java`, and `QuestScriptLifecycleTest.java` only for contracts affected by this work.
- Update `Microbot/docs/entity-guides/movement.md` with the quest caller handoff rule after validation. Follow the repository's supported plugin-version convention; verify the descriptor supports version metadata before adding it.

All new types and methods in this plan are PROPOSED. Existing walker APIs above already exist.

## Task 1: Establish measurements and regression fixtures

**Files:** `QuestScript.java`; new `QuestInteractionFlowTest.java`.

- [ ] Record baseline cases: NPC conversation, ordinary object, item-on-object, multi-object task, door route, and scene/plane transition.
- [ ] Add DEBUG-only timing events for `walk_return`, `target_ready`, `dispatch`, `progress`, `complete`, and `timeout`. Use an attempt counter, step class, entity ID/location, action, elapsed milliseconds, and reason. Exclude account/profile data and dialogue contents. Log transitions once; do not log each poll.
- [ ] Add a narrow package-private test seam around quest-local observations and dispatch. Keep production utility calls in QuestScript; do not create an alternate entity framework.
- [ ] Write failing behavioral fixtures: arrival with a valid target must dispatch in the same handler invocation; failed dispatch must not mark an object handled; a changed step after walking must not click the old target.
- [ ] Run these fixtures before changing behavior and retain the observed failures. Existing static/ASM tests alone are insufficient to prove timing or duplicate-click behavior.

**Deliverable:** Measurable boundaries and deterministic reproduction of extra-cycle handoff latency.

## Task 2: Model one pending interaction and stop stacking waits

**Files:** new `QuestInteractionAttempt.java`, `QuestInteractionAttemptTest.java`; `QuestScript.java`.

**Proposed interface:** `QuestInteractionAttempt(long nowNanos, long deadlineNanos)` with `boolean isExpired(long nowNanos)`. QuestScript stores the associated quest/active-step identity, primitive target identity, action, item-selection state, and initial relevant observations. Never retain a live NPC/object reference across iterations.

- [ ] Use monotonic time with injected timestamps in tests. Initial attempt acknowledgement deadline: 1.2 seconds. An unchanged attempt may retry only after that deadline and a fresh readiness check.
- [ ] Treat movement toward the target or target-related animation as pending activity, not completion. While activity progresses, suppress re-clicks. Reuse existing busy/input guards; do not create a blanket timeout that interrupts long quest actions.
- [ ] Discard pending state on quest/step change, target invalidation, shutdown/reset, logout, and manual input takeover. Revalidate before subsequent dispatch.
- [ ] Replace the unconditional outer activity/idle waits for migrated NPC/object branches with the pending-attempt result. Retain waits in unrelated custom/detailed handlers until each has its own observed completion contract.
- [ ] Use this deterministic minimum test for deadline semantics:

```java
@Test
public void attemptExpiresAtDeadline() {
    QuestInteractionAttempt attempt = new QuestInteractionAttempt(0L, 1_200_000_000L);
    assertFalse(attempt.isExpired(1_199_999_999L));
    assertTrue(attempt.isExpired(1_200_000_000L));
}
```

- [ ] Add flow tests with a dispatch counter: repeated evaluations during pending movement keep the counter at one; a failed dispatch leaves no successful/completed marker; expiry permits a new attempt only after target revalidation.

**Deliverable:** One owner of interaction waiting, without action spam or false completion.

## Task 3: Hand off from full WebWalker as soon as the target is usable

**Files:** `QuestScript.java`; `QuestInteractionFlowTest.java`; existing object approach/dispatch tests.

- [ ] Add fast readiness checks using fresh entity snapshots, current plane/instance, valid action, visibility and unobstructed local approach. Preserve closed-door/wall protections: line of sight alone must not authorize an unreachable click.
- [ ] Readiness callbacks must not call `Rs2Walker.canReach()` if it invokes expensive/global planning; use existing local collision/reachability information and validate instance behavior explicitly.
- [ ] Replace NPC/object approach calls with existing `walkWithStateUntil(...)` where readiness can be expressed safely. Preserve full routing when a target is unloaded or remains obstructed.
- [ ] Apply this control flow; callback satisfaction alone never authorizes a stale click:

```java
WalkerState result = Rs2Walker.walkWithStateUntil(targetTile, distance,
        () -> isCurrentInteractionReady(step));
if (result != WalkerState.ARRIVED) {
    return false;
}
// Re-read active step and entity, then run the existing interaction branch
// in this invocation only if the current target is still ready.
```

Here `isCurrentInteractionReady(QuestStep step)` is a PROPOSED private read-only method with the readiness rules above. EXIT and UNREACHABLE must not be converted to interaction success. MOVING must not cause an extra competing click.

- [ ] Reacquire NPC/object and recompute visibility after walking; do not reuse the pre-walk `objectInLineOfSight` or entity wrapper.
- [ ] Allow ordinary NPC/object approach clicks while moving only when no attempt is already pending and local approach is safe. Keep animation-sensitive and item-selection safeguards.
- [ ] Test: ready target before coordinate arrival; target behind closed door; adjacent target across wall; stale NPC index; changed object variant; plane change; callback became false before dispatch; human takeover; no second click while moving toward a dispatched target.
- [ ] Keep `objectsHandeled` updates tied to observed target/action progress or completion appropriate to multi-object semantics; movement alone must not add the object to that collection.

**Deliverable:** Same-invocation walk-to-interact handoff with doors/transports preserved.

## Task 4: Replace blanket dialogue cooldown and pace observation separately

**Files:** `QuestScript.java`; `QuestInteractionFlowTest.java`; `QuestScriptLifecycleTest.java`.

- [ ] Remove the unconditional 4–7 second post-dialogue gate. Permit the next action when dialogue is closed, the current quest step has been refreshed, and no cutscene, input takeover, or pending action blocks it.
- [ ] Handle dialogue before generic animation waiting where safe. Preserve option-selection priority, expected previous-line behavior, and quest-specific Cook's Assistant/Pirate's Treasure handling.
- [ ] Track the displayed dialogue page/continue state in memory. Dispatch at most one advance for an unchanged page until acknowledgement or a 1.2-second retry deadline; never log page contents.
- [ ] Poll the script at a fixed 200 ms interval on its existing background executor. This is observation cadence, not permission to click five times per second. Keep dispatch gated by readiness and pending-action state.
- [ ] Test: dialogue closes and next action is immediately eligible; unchanged page does not spam space; next page permits one advance; options receive the correct choice rather than space; no action during cutscene; step changes during dialogue; reset clears all dialogue and attempt state.
- [ ] Test dialogue closure without a step change: reevaluate the same step rather than assuming completion or instantly reopening a conversation already pending.

**Deliverable:** Responsive dialogue progression and transition without blind cooldowns or repeated input.

## Task 5: Validate source and package the exact approved scope

- [ ] From `Microbot`, run the focused suite after each behavioral task:

```powershell
.\gradlew.bat :client:runUnitTests --tests 'net.runelite.client.plugins.microbot.questhelper.*'
```

- [ ] Run walker regressions after integrating completion callbacks:

```powershell
.\gradlew.bat :client:runUnitTests --tests 'net.runelite.client.plugins.microbot.util.walker.*'
.\gradlew.bat :client:checkstyleMain :client:checkstyleTest
git diff --check
```

- [ ] Review failures against current baseline; do not weaken inventory-selection, wall-approach, lifecycle, or client-thread guardrails to make new behavior pass.
- [ ] Review changed Java for stale references, cancellation, false-success handling, and duplicate dispatch. Audit every removed wait for its replacement observation.
- [ ] When build/staging is authorized, assemble separately with `.\gradlew.bat :client:assemble`. Verify branch/HEAD, dirty scope, embedded metadata and SHA-256 before staging `microbot-local.jar`. Do not overwrite a JAR owned by the running JVM.

## Task 6: Live acceptance and remaining WebWalker investigation

These are proposed targets, not performance claims. Measure dispatch, not merely the current route-clear log.

| Scenario | Required result |
|---|---|
| Loaded, visible, locally reachable ordinary target | Same invocation after walk returns; absent another legitimate blocker, ready-to-dispatch p95 at most 600 ms across at least 20 attempts. |
| Dialogue closure | No fixed 4–7 second pause; next valid action dispatched within 600 ms of observed readiness when no other legitimate blocker is present. |
| Pending walk/animation after a click | No duplicate click while the action is progressing. |
| Item on object | Correct item confirmed selected before exactly one target dispatch; quantity/widget/quest changes observed appropriately. |
| Doors at `(3246,3193,0)`, `(3109,3167,0)`, `(3110,9559,0)` | Route still handles obstacles; measure door interaction through next movement separately from QuestScript's post-arrival handoff. |
| Invalidated target, logout, cancellation, cutscene, manual takeover | No stale or competing input; resume only after fresh state evaluation. |
| Multi-object step and long action | No premature handled marker and no repeated click interrupting legitimate activity. |

- [ ] After an authorized restart, confirm PID launch path, start time, staged hash, embedded build metadata and fresh logs before judging behavior.
- [ ] Record target-ready, dispatch, acknowledgement, and completion separately; report median/p95 and maximum plus exclusions such as cutscene, blocked input, or active animation.
- [ ] Compare logging enabled/disabled briefly to ensure DEBUG diagnostics are not distorting timing.
- [ ] If pauses remain before ARRIVED/target readiness, open a separate walker investigation using complete door traces and those exact coordinates. Do not reduce throttle constants based solely on repeated suppressed-attempt messages.

## Completion boundary

Implementation is complete only when source checks pass, the approved build is qualified, and live handoffs meet the acceptance matrix. If live access/restart is unavailable, report source-tested and staged status explicitly, with live acceptance outstanding. This planning task ends with this document; no source behavior has been changed.

## Execution record � September 4

- Implementation branch: `fix/questscript-interaction-responsiveness`, based on fork `origin/main` (`76261375b4`), in the isolated `Microbot-QuestScript-Responsiveness` worktree. The reviewed QuestScript and required full-walker API were already present on fork main. Unrelated dirty source in the primary checkout was preserved.
- Tasks 1�4: source implementation and deterministic policy/flow, collision, dialogue widget, item-dispatch structure and actual shutdown tests are present. The supplied user logs provide the baseline arrival-to-interaction gaps; no new live baseline session was run.
- Task 5: focused quest/walker suites, client-thread/queryable guardrails and Checkstyle were run. The scoped NPC lookup repair removed an obsolete guardrail exception; no new exception was added. The build and PR evidence belong in the PR's validation record.
- Task 6: live matrix remains pending. At qualification discovery there was no running Java game client, no Agent Server listener on port 8081, and no `.agent-token`. The source tests do not prove live NPC index reuse, object variant swaps, input takeover, or latency percentiles.
- Ruling: use physical scene collision checks for readiness and convert only the full walker's target to template coordinates. This preserves instance safety; a canonical/physical mismatch would suppress nearby interactions.
- Ruling: retain a 1.2-second acknowledgement interval through short animation gaps; observed motion is not action completion. Keep quest-specific dialogue priority under the page gate, and keep non-migrated custom actions on their own 600 ms cadence.
- Ruling: include the minimal `Rs2Npc.getNpcWithAction` thread-boundary repair because safe composition reads in the new code exposed its existing off-thread definition transform through the guardrail. This reduces the baseline by one exception.
- Publication restriction: PR destination is `itsBOTzilla/Microbot` only.

The unchecked original checklist is retained as the design/acceptance contract, not a claim that source implementation has not started. Source-reviewed and automated-test-passing does not mean live acceptance is complete. No game restart is authorized by this execution record.
