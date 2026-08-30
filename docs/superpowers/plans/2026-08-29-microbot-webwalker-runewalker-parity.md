# Microbot WebWalker RuneWalker 2.0 Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Microbot's active WebWalker movement policy with RuneWalker 2.0 checkpoint ownership while preserving every existing script-facing `Rs2Walker` API and deploying one verified `microbot-local.jar` launcher artifact.

**Architecture:** `WebWalkExecutor` remains the single-action state machine, `WebWalkSession` owns one walk call's command/progress state, and `RuneLiteWebWalkRuntime` adapts immutable observations to existing Microbot APIs. `Rs2Walker` remains the compatibility facade and continues to own Microbot pathfinding, doors, transports, minimap input, banking-aware walking, target lifecycle, and arrival semantics.

**Tech Stack:** Java 11 target, Gradle Kotlin DSL, JUnit 4, ASM-based wiring tests, RuneLite/Microbot client-thread APIs, PowerShell launcher tooling.

**Spec:** `docs/superpowers/specs/2026-08-29-microbot-webwalker-runewalker-parity-design.md`

## Global Constraints

- Work directly on the existing local `main`; do not create another local branch.
- Complete and verify the already-staged upstream nightly merge before changing WebWalker behavior.
- Never reset, clean, rebase, or overwrite existing tracked or untracked work.
- Preserve every public `Rs2Walker` method signature and `WalkerState` meaning used by existing scripts.
- Existing scripts must receive the rewrite automatically through the current Microbot APIs.
- Do not import RuneWalker plugins, branding, Antiban infrastructure, or private scripts.
- Do not modify vendored planner sources or shortest-path resources as part of the movement rewrite.
- Do not block or sleep on the client thread; use bounded predicate waits for game-state changes.
- Keep Microbot's nightly InputArbiter, door, transport, dialogue, cancellation, and pathfinder behavior.
- Keep the shared randomized inclusive 50-100 percent auto-run threshold and reroll only after successful activation.
- Do not push private iBOT scripts, credentials, proxy values, absolute private paths, or session identifiers.
- Pull requests, if later authorized, target only `itsBOTzilla/Microbot`, never `chsami/Microbot`.
- The permanent local artifact is `%USERPROFILE%\.microbot\microbot-local.jar`; launcher `version_pref` is `local`.
- Keep `microbot-2.6.21.jar` as the only official rollback artifact after cleanup.
- Treat source, automated tests, built JAR, staged JAR, selected launcher preference, and loaded runtime as separate evidence.

---

### Task 1: Complete the Pending Nightly Integration Baseline

**Files:**
- Verify staged merge: all currently staged upstream-nightly files
- Preserve untracked: `docs/superpowers/specs/2026-08-29-microbot-webwalker-runewalker-parity-design.md`
- Preserve untracked: `docs/superpowers/plans/2026-08-29-microbot-webwalker-runewalker-parity.md`

**Interfaces:**
- Consumes: current `main` at the open merge state and fetched `upstream/development` at `b357795b2f6f212379936dde7ad29d9aec16fc8d`
- Produces: one verified nightly merge commit on local `main`, followed by one documentation commit containing this spec and plan

- [ ] **Step 1: Verify that the only merge conflict has been resolved**

Run:

```powershell
git status --short --branch
git diff --name-only --diff-filter=U
rg -n '^(<<<<<<<|=======|>>>>>>>)' docs runelite-client/src/main runelite-client/src/test
```

Expected: no unmerged paths and no conflict markers. The spec and plan remain untracked rather than staged into the upstream merge.

- [ ] **Step 2: Re-run compilation from the resolved merge**

Run:

```powershell
.\gradlew.bat :client:compileJava :client:compileTestJava
```

Expected: `BUILD SUCCESSFUL` with no compilation errors.

- [ ] **Step 3: Run the fork and nightly integration suites**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.AutoRunPolicyTest" `
  --tests "net.runelite.client.plugins.microbot.AutoRunPolicyWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RuneLiteWebWalkRuntimeTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.Rs2WalkerUnitTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RouteRecoveryTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RouteReachabilitySnapshotWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.DoorAwaitDispatchWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.door.Rs2WalkerAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.transport.Rs2WalkerTransportAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.shortestpath.ShortestPathWalkTaskPolicyTest" `
  --tests "net.runelite.client.plugins.microbot.shortestpath.ShortestPathCoreTest" `
  --tests "shortestpath.pathfinder.VendoredPathfinderRegressionTest" `
  --tests "shortestpath.transport.VendoredTransportRegressionTest" `
  --tests "net.runelite.client.plugins.microbot.questhelper.QuestScriptLifecycleTest" `
  --tests "net.runelite.client.plugins.microbot.questhelper.QuestObjectInteractionDispatchTest" `
  --tests "net.runelite.client.plugins.microbot.questhelper.QuestObjectApproachPolicyTest" `
  --tests "net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItemPauseTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.CanvasBoundaryTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.FocusLossReleasesHeldInputTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.GestureAbortTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.InputArbiterTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.InputDiagnosticsTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.InputEmissionTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.YieldOnHumanTest" `
  --tests "net.runelite.client.plugins.microbot.util.keyboard.Rs2KeyboardHeldKeysTest"
```

Expected: every selected test executes with zero failures, errors, or skips attributable to the merge.

- [ ] **Step 4: Run repository gates**

Run:

```powershell
.\gradlew.bat :client:checkstyleMain :client:checkstyleTest :client:assemble check
```

Expected: `BUILD SUCCESSFUL`. Report separately if the repository intentionally skips its default `test` task.

- [ ] **Step 5: Audit the staged merge boundary and privacy**

Run:

```powershell
git diff --cached --name-status
git diff --cached --check -- . ':(exclude)runelite-client/src/main/resources/net/runelite/client/plugins/microbot/shortestpath/*.tsv'
$changed = git diff --cached --name-only
$changed | Select-String -Pattern '(?i)(ibot|private-script|credential|secret|C:\\Users\\clanb)'
```

Expected: no private-script or credential paths. The excluded nightly TSV files may retain meaningful trailing empty columns and must not be mechanically rewritten.

- [ ] **Step 6: Commit only the verified nightly merge**

Run:

```powershell
git status --short
git commit --no-edit
```

Expected: the merge commit succeeds; the untracked spec and plan are not included.

- [ ] **Step 7: Commit the approved design and implementation plan separately**

Run:

```powershell
git add -- docs/superpowers/specs/2026-08-29-microbot-webwalker-runewalker-parity-design.md docs/superpowers/plans/2026-08-29-microbot-webwalker-runewalker-parity.md
git diff --cached --check
git commit -m "docs: plan WebWalker RuneWalker parity rewrite"
```

Expected: a documentation-only commit after the nightly merge.

---

### Task 2: Pin RuneWalker 2.0 Checkpoint Ownership with Failing Tests

**Files:**
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java`

**Interfaces:**
- Consumes: existing `WebWalkExecutor.decide(WebWalkSession, Observation)` and `WebWalkRuntime.Observation`
- Produces: red tests requiring reached, passed, or guarded outside-five-to-within-five checkpoint release regardless of run state

- [ ] **Step 1: Replace early-handoff expectations with a run/walk parity sequence**

Replace tests that expect an eight-tile running handoff with a table-driven test using the existing `point`, `route`, and `ready` fixtures:

```java
@Test
public void runningAndWalkingRetainCheckpointUntilFiveTileHandoffOrPassed()
{
    for (boolean runEnabled : new boolean[] {false, true})
    {
        for (int playerX : new int[] {2, 4})
        {
            WebWalkSession session = new WebWalkSession(GOAL, 0);
            session.installRoute(route(1));
            session.observe(100, START, 0);
            session.recordMinimapDispatch(100, point(10), point(10), 10, false);

            WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
                    ready(101, point(playerX), 1, 2, point(12), 12, false, runEnabled));
            assertEquals(WebWalkExecutor.DecisionType.WAIT, decision.getType());
        }

        WebWalkSession reached = new WebWalkSession(GOAL, 0);
        reached.installRoute(route(1));
        reached.observe(100, START, 0);
        reached.recordMinimapDispatch(100, point(10), point(10), 10, false);
        assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP,
                new WebWalkExecutor().decide(reached,
                        ready(101, point(9), 1, 2, point(12), 12,
                                false, runEnabled)).getType());
    }
}
```

Use the existing test fixtures and actual local coordinates/helpers rather than introducing production-only test hooks.

- [ ] **Step 2: Add a passed-index release regression**

Add a test equivalent to:

```java
@Test
public void passingCheckpointIndexReleasesBeforeDistanceArrival()
{
    WebWalkSession session = new WebWalkSession(GOAL, 0);
    session.installRoute(route(1));
    session.observe(100, START, 0);
    session.recordMinimapDispatch(100, point(10), point(10), 10, false);

    WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
            ready(101, point(2), 1, 11, point(12), 12, false, true));
    assertEquals(WebWalkExecutor.DecisionType.CLICK_MINIMAP, decision.getType());
}
```

- [ ] **Step 3: Make the wiring test reject run-dependent checkpoint policy**

Extend the ASM assertion so `WebWalkExecutor.decide` must not read `Observation.isRunEnabled()` and must not reference fields named `RUN_CHECKPOINT_HANDOFF_DISTANCE` or `WALK_CHECKPOINT_HANDOFF_DISTANCE`.

```java
assertFalse(decideReadsRunEnabled.get());
assertFalse(referencesRunHandoffConstant.get());
assertFalse(referencesWalkHandoffConstant.get());
```

- [ ] **Step 4: Run the focused test and verify the current implementation fails**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --rerun-tasks
```

Expected: FAIL because the current `f42b030` behavior has a run-specific eight-tile handoff rather than a shared, guarded five-tile approach policy.

---

### Task 3: Implement Nonblocking Checkpoint Ownership

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkSession.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkRuntime.java` only if constructor compatibility needs an overload
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java`

**Interfaces:**
- Consumes: the red checkpoint-handoff tests from Task 2
- Produces: one active checkpoint whose release policy is independent of run state

- [ ] **Step 1: Track only the guarded initial distance in `WebWalkSession`**

Record a known initial same-plane checkpoint distance when an accepted dispatch creates the checkpoint. Use an unavailable sentinel when no player distance is known; it must not qualify for handoff:

```java
private int checkpointInitialDistance = -1;
boolean hasApproachedCheckpointForHandoff(WorldPoint player, int handoffDistance)
```

Set the value in `recordMinimapDispatch` and reset it in `clearCheckpoint`. Keep the helper package-private and retain checkpoint point, path index, command tick, redispatch count, and rejection count.

- [ ] **Step 2: Apply reached, passed, or guarded handoff logic in `WebWalkExecutor`**

Replace the run-aware branch with:

```java
boolean passedCheckpoint = session.getCheckpointPathIndex() >= 0
        && observation.getPathIndex() > session.getCheckpointPathIndex();
boolean reachedCheckpoint = session.isCheckpointReached(player, 1);
boolean handoffCheckpoint = session.hasApproachedCheckpointForHandoff(player, 5);
if (passedCheckpoint || reachedCheckpoint || handoffCheckpoint)
{
    session.clearCheckpoint();
}
```

Delete `RUN_CHECKPOINT_HANDOFF_DISTANCE` and `WALK_CHECKPOINT_HANDOFF_DISTANCE`. Keep `Observation.isRunEnabled()` only if needed for internal compatibility or diagnostics; the decision must not consult it.

- [ ] **Step 3: Run focused tests**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --rerun-tasks
```

Expected: PASS with both walking and running retaining checkpoints until passed, reached within one tile, or the shared guarded handoff at five tiles.

- [ ] **Step 4: Run checkstyle and commit the parity change**

Run:

```powershell
.\gradlew.bat :client:checkstyleMain :client:checkstyleTest
git diff --check
git add -- runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkSession.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkRuntime.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java
git commit -m "fix(walker): restore nonblocking checkpoint handoff"
```

Expected: a narrowly scoped checkpoint-policy commit.

---

### Task 4: Bound Rejected Minimap Dispatches Across Player Progress

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkSession.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntimeTest.java`

**Interfaces:**
- Consumes: `recordRejectedDispatch()`, `recordMinimapDispatch(...)`, `observe(...)`, and existing safe raw-path fallback dispatch
- Produces: rejected dispatch ownership that resets only after an accepted command or route replacement, not incidental residual movement

- [ ] **Step 1: Write a failing rejection-sequence test**

Add a test in which a minimap dispatch is rejected, the player advances one residual tile, a second dispatch is rejected, and the next decision replans:

```java
@Test
public void residualMovementDoesNotEraseRejectedDispatchBudget()
{
    WebWalkSession session = new WebWalkSession(GOAL, 0);
    session.installRoute(route(1));
    session.observe(10, START, 0);
    session.recordRejectedDispatch();
    session.observe(11, point(1), 1);
    assertEquals(1, session.getRejectedDispatchCount());
    session.recordRejectedDispatch();

    WebWalkExecutor.Decision decision = new WebWalkExecutor().decide(session,
            ready(12, point(1), 1, 1, point(12), 12, false));
    assertEquals(WebWalkExecutor.DecisionType.REPLAN, decision.getType());
}
```

- [ ] **Step 2: Verify the test fails against the current reset behavior**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest.residualMovementDoesNotEraseRejectedDispatchBudget" `
  --rerun-tasks
```

Expected: FAIL because `observe` currently clears `rejectedDispatchCount` on any player progress.

- [ ] **Step 3: Separate command rejection from movement progress**

In `WebWalkSession.observe`, keep progress resets for redispatch and route-action state but remove:

```java
rejectedDispatchCount = 0;
```

Keep rejection reset in `recordMinimapDispatch`, `clearPendingCommand`, route generation installation, and replan initialization so a successfully accepted command or new route starts a clean budget.

- [ ] **Step 4: Pin farthest-safe selection and transport boundaries**

Add or retain runtime tests asserting:

```java
List<WorldPoint> path = line(0, 16);
Set<WorldPoint> reachable = new HashSet<>(line(0, 12));
RuneLiteWebWalkRuntime.ForwardCandidate candidate =
        RuneLiteWebWalkRuntime.selectForwardCandidate(
                path, point(0), reachable, 12, index -> index == 9);
assertEquals(point(9), candidate.getTarget());
assertEquals(9, candidate.getPathIndex());
int dx = candidate.getTarget().getX() - point(0).getX();
int dy = candidate.getTarget().getY() - point(0).getY();
assertTrue(dx * dx + dy * dy <= 12 * 12);
```

Also assert that rejected `DispatchResult` has `isAccepted() == false` and `getActualTarget() == null`; it must never reach `recordMinimapDispatch`.

- [ ] **Step 5: Run tests and commit**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RuneLiteWebWalkRuntimeTest" `
  --rerun-tasks
.\gradlew.bat :client:checkstyleMain :client:checkstyleTest
git diff --check
git add -- runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkSession.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntimeTest.java
git commit -m "fix(walker): bound rejected minimap dispatches"
```

Expected: the rejection budget survives incidental player movement, while accepted dispatches still reset it.

---

### Task 4A: Prefer Existing Microbot Canvas Walking for Close Route Targets

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntime.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkRuntime.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkLog.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntimeTest.java`

**Interfaces:**
- Consumes: the already selected safe route target and Microbot's existing on-screen canvas walker
- Produces: one canvas command for safe targets within five Chebyshev tiles, otherwise one minimap command

- [ ] **Step 1: Add failing dispatch-policy tests**

Pin three sequences with injected dispatch functions: a close canvas-eligible target emits only
canvas, a far target emits only minimap, and a close target rejected before canvas input falls back
to exactly one minimap command. Assert the accepted result records whether canvas or minimap owned
the checkpoint.

- [ ] **Step 2: Add dispatch method identity without breaking callers**

Extend `WebWalkRuntime.DispatchResult` with a movement method (`CANVAS`, `MINIMAP`, or `NONE`). Keep
the existing `accepted(WorldPoint)` and `rejected()` factories compatible; the former remains a
minimap result for existing implementations.

- [ ] **Step 3: Reuse the existing Microbot canvas path**

Add a package-private, on-screen-only overload in `Rs2Walker` that does not toggle run. The runtime
must not call the public `walkFastCanvas` fallback because it can both alter run state and conceal
whether minimap input was emitted.

- [ ] **Step 4: Implement the bounded runtime policy**

When the selected target is same-plane and within five tiles, try the no-run-toggle canvas helper.
Only a pre-input canvas rejection may fall through to `dispatchMiniMapTarget`. Preserve the exact
selected route target, checkpoint path index, action preemption, and one-input-per-observation rule.

- [ ] **Step 5: Validate and commit**

Run the executor/runtime suites, route-action boundary suites, checkstyle, `git diff --check`, and
commit as `fix(walker): prefer canvas for close route targets`.

---

### Task 4B: Keep Passive Real Mouse Motion From Interrupting WebWalker

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/input/InputArbiter.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotConfig.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/input/InputDiagnostics.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/input/InputArbiterTest.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/input/YieldOnHumanTest.java`

**Interfaces:**
- Consumes: real canvas motion, button, and key events through `CanvasInputListener`
- Produces: position-only hover motion and gesture-only HUMAN ownership

- [x] **Step 1: Reproduce the hover takeover through the real listener**

Drive a real `MOUSE_MOVED` event after a bot position and prove the cursor is tracked while
`InputArbiter.isHuman()` remains false. Repeat enough motion to exceed the former threshold.

- [x] **Step 2: Pin the WebWalker wait contract**

After passive motion, run the real `Global.sleepUntil` path and prove it still polls and succeeds.
Retain separate coverage proving real button and key gestures yield and honor idle resume.

- [x] **Step 3: Separate pointer position from takeover activity**

Keep `PointerState` updates for hover motion, but only real button/key events call the activity
owner. Retain the legacy motion-threshold configuration surface as an inert hidden compatibility
setting so existing configuration loads do not break.

- [ ] **Step 4: Validate with the canvas movement unit and live gates**

Run the input, runtime, executor, checkstyle, and assembly suites. During live acceptance, move the
physical cursor continuously over the client while running an open-ground route and require no
route cancellation, wait abort, rejected dispatch, or cadence change attributable to motion.

---

### Task 5: Prove Route Actions Preempt Movement Without Losing Progress

**Files:**
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntimeTest.java`
- Modify only if a failing test requires it: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java`
- Modify only if a failing test requires it: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntime.java`

**Interfaces:**
- Consumes: `Observation.isRouteActionAvailable()`, `interactRouteEdge`, route-action pending state, and fresh observation waits
- Produces: a verified movement-to-action-to-movement sequence with no double dispatch

- [ ] **Step 1: Add an executor sequence test**

Use a scripted fake runtime with observations for clear movement, an actionable edge, causal action progress, and clear movement after the edge. Record events with the same string-list pattern already used by `RecordingRuntime`:

```java
assertEquals(Arrays.asList(
        "observe", "dispatch", "await",
        "observe", "interact", "await",
        "observe", "dispatch", "await",
        "observe", "finish"), runtime.events);
```

Assert the active checkpoint is cleared before the route action and that `awaitChange` occurs after every dispatched action.

- [ ] **Step 2: Add runtime boundary tests**

Retain and extend the existing `selectsFurthestReachableForwardTileInsideMinimapRange` and `neverSelectsPastExactTransportEdge` fixtures:

```java
List<WorldPoint> path = line(0, 16);
RuneLiteWebWalkRuntime.ForwardCandidate candidate =
        RuneLiteWebWalkRuntime.selectForwardCandidate(
                path, point(0), new HashSet<>(path), 12, index -> index == 6);
assertEquals(point(6), candidate.getTarget());
assertEquals(6, candidate.getPathIndex());
```

The executor's `routeActionAlwaysWinsOverMinimapDispatch` and scripted sequence test provide the action-available preemption assertion.

- [ ] **Step 3: Run the focused route-action suites**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RuneLiteWebWalkRuntimeTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.door.Rs2WalkerAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.transport.Rs2WalkerTransportAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.DoorAwaitDispatchWiringTest" `
  --rerun-tasks
```

Expected: PASS. If the new sequence exposes a production defect, make only the minimal executor/runtime ordering correction and rerun this exact command.

- [ ] **Step 4: Commit the route-action qualification**

Run:

```powershell
git diff --check
git add -- runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntime.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorTest.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/RuneLiteWebWalkRuntimeTest.java
git commit -m "test(walker): qualify route action ownership"
```

Expected: a test-first qualification commit, with production files staged only if a failing regression required a minimal correction.

---

### Task 6: Preserve the Existing Script-Facing API Surface

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/Rs2WalkerPublicApiCompatibilityTest.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java`
- Do not change signatures in: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java`

**Interfaces:**
- Consumes: current public `Rs2Walker` method descriptors and `WalkerState`
- Produces: a reflection/ASM compatibility gate for existing and future scripts

- [ ] **Step 1: Add reflection assertions for high-level public entry points**

Create a test helper and enumerate the exact existing methods:

```java
private static void assertPublicStatic(String name, Class<?> returnType, Class<?>... parameters)
        throws NoSuchMethodException
{
    Method method = Rs2Walker.class.getDeclaredMethod(name, parameters);
    assertTrue(Modifier.isPublic(method.getModifiers()));
    assertTrue(Modifier.isStatic(method.getModifiers()));
    assertEquals(returnType, method.getReturnType());
}

@Test
public void preservesScriptFacingWalkingDescriptors() throws Exception
{
    assertPublicStatic("walkTo", boolean.class, int.class, int.class, int.class);
    assertPublicStatic("walkTo", boolean.class, int.class, int.class, int.class, int.class);
    assertPublicStatic("walkTo", boolean.class, WorldPoint.class);
    assertPublicStatic("walkTo", boolean.class, WorldPoint.class, int.class);
    assertPublicStatic("walkWithState", WalkerState.class, WorldPoint.class);
    assertPublicStatic("walkWithState", WalkerState.class, WorldPoint.class, int.class);
    assertPublicStatic("walkWithStateTry", WalkerState.class,
            WorldPoint.class, int.class, long.class);
    assertPublicStatic("walkStep", WalkerState.class, WorldPoint.class, int.class);
    assertPublicStatic("walkWithBankedTransports", boolean.class, WorldPoint.class);
    assertPublicStatic("walkWithBankedTransports", boolean.class,
            WorldPoint.class, boolean.class);
    assertPublicStatic("walkWithBankedTransports", boolean.class,
            WorldPoint.class, int.class, boolean.class);
    assertPublicStatic("walkWithBankedTransportsAndState", WalkerState.class,
            WorldPoint.class, int.class, boolean.class);
}
```

Add the exact `walkUntil`, `walkWithStateUntil`, `walkNextTo`, target, clear, and recalculate descriptors from the current source rather than replacing them with simplified overloads.

- [ ] **Step 2: Extend active-engine wiring coverage**

Make the ASM test prove that the high-level facade converges on `walkWithStateInternal`, and that `walkWithStateInternal` constructs `WebWalkSession`, `RuneLiteWebWalkRuntime`, and `WebWalkExecutor` without calling `processWalk`.

```java
assertTrue(createsSession.get());
assertTrue(createsRuntime.get());
assertTrue(invokesExecutor.get());
assertFalse(invokesLegacyLoop.get());
```

- [ ] **Step 3: Compile representative existing scripts without changes**

Run:

```powershell
.\gradlew.bat :client:compileJava :client:compileTestJava
```

Expected: existing mining, runecrafting, quest, and banking call sites compile without source edits.

- [ ] **Step 4: Run compatibility tests and commit**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.Rs2WalkerPublicApiCompatibilityTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --rerun-tasks
.\gradlew.bat :client:checkstyleTest
git diff --check
git add -- runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/Rs2WalkerPublicApiCompatibilityTest.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java
git commit -m "test(walker): preserve script API compatibility"
```

Expected: all existing descriptors and new-engine wiring are pinned.

---

### Task 7: Add Bounded Diagnostics, Update Documentation, and Quarantine Legacy Ownership

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkLog.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java`
- Modify: `docs/entity-guides/movement.md`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java`
- Do not remove yet: legacy `processWalk` and helpers in `Rs2Walker.java`

**Interfaces:**
- Consumes: strict checkpoint and compatibility contracts from Tasks 3-6
- Produces: one-line checkpoint release evidence, documentation, and a zero-active-caller gate for the legacy movement loop

- [ ] **Step 1: Add one diagnostic at each checkpoint ownership transition**

Add a bounded logger method:

```java
public static void checkpointReleased(String reason, WorldPoint checkpoint,
                                      int pathIndex, WorldPoint player, int tick)
{
    LOG.info("[WebWalk] checkpoint | released={} target={} idx={} at={} tick={}",
            reason, checkpoint, pathIndex, player, tick);
}
```

Call it only when the executor clears an accepted checkpoint because it was reached, passed, handed off after a guarded approach, or preempted by a route action. Use `handoff`, not `reached`, for the five-tile anticipatory case. Do not log every `checkpoint-progress` wait.

- [ ] **Step 2: Pin diagnostic wiring without capturing production logs**

Extend `WebWalkExecutorWiringTest` to assert that the compiled executor invokes `WebWalkLog.checkpointReleased` from its checkpoint-release/action-preemption decision paths. The assertion must fail if release logging is removed while `minimapDispatch` remains.

- [ ] **Step 3: Replace the early-handoff guidance**

Document this exact ownership rule:

```markdown
An accepted minimap checkpoint remains owned until the player passes its raw-path index, comes
within one Chebyshev tile, or approaches from a known initial distance above five tiles to within
five. A checkpoint initially inside that band still requires one-tile arrival, index progress, or
recovery. Run speed does not release ownership early. Door and transport actions preempt the
checkpoint and require a fresh route observation before movement resumes.
```

Also document bounded `actual=null` rejection and the stable `microbot-local.jar` runtime-proof requirement.

- [ ] **Step 4: Strengthen the zero-active-legacy-caller assertion**

Extend ASM coverage to scan production call sites and fail if an active high-level walking entry point invokes either descriptor of private `processWalk`:

```java
assertEquals("legacy processWalk must have no active facade callers", 0,
        legacyProcessWalkInvocations.get());
```

Recursive calls inside the unreachable legacy implementation are not counted as facade callers.

- [ ] **Step 5: Run documentation and wiring checks**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --rerun-tasks
.\gradlew.bat :client:checkstyleTest
git diff --check -- docs/entity-guides/movement.md runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkLog.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java
```

Expected: legacy code remains present for rollback but cannot own a normal script walk.

- [ ] **Step 6: Commit diagnostics, documentation, and quarantine gate**

Run:

```powershell
git add -- docs/entity-guides/movement.md runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkLog.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutor.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java
git commit -m "feat(walker): log checkpoint ownership transitions"
```

---

### Task 8: Run the Complete Source and Build Verification Gate

**Files:**
- Verify: all files changed by Tasks 2-7
- Do not modify generated baselines to hide failures

**Interfaces:**
- Consumes: completed source rewrite and tests
- Produces: fresh source, test, style, assembly, diff, and privacy evidence

- [ ] **Step 1: Run all rewrite and neighboring regression suites fresh**

Run:

```powershell
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.AutoRunPolicyTest" `
  --tests "net.runelite.client.plugins.microbot.AutoRunPolicyWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.Rs2WalkerPublicApiCompatibilityTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RuneLiteWebWalkRuntimeTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.Rs2WalkerUnitTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RouteRecoveryTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RouteReachabilitySnapshotWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.DoorAwaitDispatchWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.door.Rs2WalkerAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.transport.Rs2WalkerTransportAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.shortestpath.ShortestPathWalkTaskPolicyTest" `
  --tests "net.runelite.client.plugins.microbot.shortestpath.ShortestPathCoreTest" `
  --tests "shortestpath.pathfinder.VendoredPathfinderRegressionTest" `
  --tests "shortestpath.transport.VendoredTransportRegressionTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.InputArbiterTest" `
  --tests "net.runelite.client.plugins.microbot.util.input.YieldOnHumanTest" `
  --rerun-tasks
```

Expected: every selected test executes and reports zero failures/errors.

- [ ] **Step 2: Run compile, style, repository check, and assembly**

Run:

```powershell
.\gradlew.bat :client:compileJava :client:compileTestJava :client:checkstyleMain :client:checkstyleTest :client:assemble check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect exact diff and privacy boundaries**

Run:

```powershell
git status --short --branch
git diff --check
git diff --stat
git diff --name-only | Select-String -Pattern '(?i)(ibot|private-script|credential|secret|C:\\Users\\clanb)'
rg -n 'RUN_CHECKPOINT_HANDOFF_DISTANCE|WALK_CHECKPOINT_HANDOFF_DISTANCE|isCheckpointReadyForHandoff' runelite-client/src/main runelite-client/src/test docs/entity-guides/movement.md
```

Expected: no private material, no whitespace errors, and no active early-handoff symbols.

---

### Task 9: Build and Stage the Stable Local Launcher Artifact

**Files:**
- Build: `runelite-client/build/libs/microbot-*.jar`
- Replace after verification: `%USERPROFILE%\.microbot\microbot-local.jar`
- Modify only `version_pref`: `%USERPROFILE%\.microbot\resource_versions.json`

**Interfaces:**
- Consumes: clean verified Git commit from Task 8
- Produces: one hash-matched stable local launcher JAR selected as `local`

- [ ] **Step 1: Commit any final verified source changes**

Run:

```powershell
git status --short
git diff --check
git add -- docs/entity-guides/movement.md runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/input/InputArbiter.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotConfig.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/input/InputDiagnostics.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/input/InputArbiterTest.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/input/YieldOnHumanTest.java
git commit -m "fix(walker): restore RuneWalker checkpoint parity"
```

Expected: either a final scoped commit or `nothing to commit` because Tasks 3-7 already committed all changes. Never use `git add -A`.

- [ ] **Step 2: Build resources and the release JAR from the clean commit**

Run:

```powershell
$buildCommit = git rev-parse HEAD
.\gradlew.bat :client:processResources :client:microbotReleaseJar --rerun-tasks "--project-prop=microbot.commit.sha=$buildCommit" --no-daemon --console=plain
git status --short
```

Expected: build succeeds, the tracked tree remains clean, and the release JAR embeds the exact clean commit rather than the local-build default `nogit`. If the primary worktree contains protected concurrent WIP, run this command from a clean detached worktree at `$buildCommit` instead of modifying or stashing that WIP.

- [ ] **Step 3: Verify embedded identity before staging**

Run:

```powershell
$sourceJar = Get-ChildItem -LiteralPath 'runelite-client\build\libs' -File -Filter 'microbot-*.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$extract = Join-Path $env:TEMP ('microbot-identity-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $extract | Out-Null
Push-Location $extract
jar xf $sourceJar.FullName runelite.properties
$properties = Get-Content -LiteralPath 'runelite.properties'
Pop-Location
$properties | Select-String -Pattern '^runelite\.(version|build\.commit|dirty)='
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceJar.FullName -ErrorAction Stop).Hash
$sourceHash
```

Expected: embedded commit equals `git rev-parse HEAD`, dirty is `false`, and a source SHA-256 is recorded. Remove only the exact temporary extraction directory after resolving and validating that it is beneath `$env:TEMP`.

- [ ] **Step 4: Stage through a temporary launcher file**

Run after the normal Microbot client has been closed:

```powershell
$ErrorActionPreference = 'Stop'
$launcher = Join-Path $env:USERPROFILE '.microbot'
$temporaryJar = Join-Path $launcher 'microbot-local.jar.pending'
$stableJar = Join-Path $launcher 'microbot-local.jar'
$backupJar = Join-Path $launcher 'microbot-local.jar.swap-backup'
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceJar.FullName -ErrorAction Stop).Hash
Copy-Item -LiteralPath $sourceJar.FullName -Destination $temporaryJar -Force -ErrorAction Stop
$pendingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryJar -ErrorAction Stop).Hash
if ($sourceHash -ne $pendingHash) {
    throw 'Pending local JAR hash does not match the built source JAR.'
}
$hadStableJar = Test-Path -LiteralPath $stableJar
if ($hadStableJar) {
    Copy-Item -LiteralPath $stableJar -Destination $backupJar -Force -ErrorAction Stop
}
Move-Item -LiteralPath $temporaryJar -Destination $stableJar -Force -ErrorAction Stop
$stableHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stableJar -ErrorAction Stop).Hash
if ($sourceHash -ne $stableHash) {
    if ($hadStableJar) {
        Copy-Item -LiteralPath $backupJar -Destination $stableJar -Force -ErrorAction Stop
    } else {
        Remove-Item -LiteralPath $stableJar -Force -ErrorAction Stop
    }
    throw 'Stable local JAR hash does not match the built source JAR.'
}
$sourceHash
```

Expected: the source and stable hashes are identical. Before replacement, verify all resolved destinations equal the three explicit paths under `%USERPROFILE%\.microbot`. Retain `microbot-local.jar.swap-backup` until live acceptance passes; do not use `[IO.File]::Replace` with a null backup path because that is not portable across the supported Windows/.NET launcher environments.

- [ ] **Step 5: Point the launcher permanently at `local`**

Use `apply_patch` to change only the existing JSON value:

```diff
-  "version_pref": "2.6.21.public-f42b030",
+  "version_pref": "local",
```

Then verify without printing credential fields:

```powershell
$prefsPath = Join-Path $env:USERPROFILE '.microbot\resource_versions.json'
$prefs = Get-Content -Raw -LiteralPath $prefsPath | ConvertFrom-Json
$prefs.version_pref
```

Expected: output is exactly `local`.

---

### Task 10: Live-Qualify the Exact Stable JAR

**Files:**
- Observe: fresh RuneLite/Microbot logs
- Verify: `%USERPROFILE%\.microbot\microbot-local.jar`

**Interfaces:**
- Consumes: staged stable JAR and recorded commit/hash from Task 9
- Produces: runtime acceptance evidence or an exact failed state without deleting rollback artifacts

- [ ] **Step 1: Start through the normal launcher and verify identity**

Expected startup evidence:

```text
Microbot: <embedded version> - <expected Git commit>
```

Confirm the logged commit equals the built/staged commit. A selected filename alone is not proof.

- [ ] **Step 2: Run the open-ground acceptance route with run enabled**

Use the previously reproduced route from approximately `WorldPoint(2946,3368,0)` toward `WorldPoint(3164,3466,0)` or another equally long unobstructed route.

Expected log contract:

```text
one accepted checkpoint
checkpoint-progress waits while approaching
next click when checkpoint is reached, its index is passed, or guarded outside-five-to-within-five handoff occurs
```

Reject the build if ordinary clear-path logs show repeated 1-5-index replacement commands, repeated `actual=null` churn, an off-route click, or a walker-caused stationary gap.

- [ ] **Step 3: Run door, transport, cancellation, and script compatibility journeys**

Exercise:

- one normal door;
- one catalog-backed transport;
- one collision disagreement/replan;
- target A replaced by target B;
- explicit stop;
- one existing mining or runecrafting script without source changes;
- one existing quest or banking-aware call without source changes.

Expected: route actions temporarily own movement, the new target survives stale-worker cleanup, stop emits no later click, and existing scripts use the rewritten engine automatically.

- [ ] **Step 4: Record the live verdict**

Record:

```text
Git commit:
source JAR SHA-256:
staged JAR SHA-256:
loaded startup commit:
open-ground result:
door result:
transport result:
cancellation result:
existing-script result:
```

Expected: all identity fields match and every journey passes before cleanup or legacy removal.

---

### Task 11: Remove Obsolete Launcher JARs After Stable Live Acceptance

**Files:**
- Keep: `%USERPROFILE%\.microbot\microbot-local.jar`
- Keep: `%USERPROFILE%\.microbot\microbot-2.6.21.jar`
- Remove: obsolete 2.6.20, local swap-backup, and every hash-suffixed public JAR

**Interfaces:**
- Consumes: passing live evidence from Task 10
- Produces: a launcher directory with one local build and one official rollback build

- [ ] **Step 1: Stop the client and verify exact cleanup targets**

Run:

```powershell
$launcher = Join-Path $env:USERPROFILE '.microbot'
$removeNames = @(
  'microbot-2.6.20.18.jar',
  'microbot-2.6.20.19.jar'
)
$discoveredNames = Get-ChildItem -LiteralPath $launcher -File |
  Where-Object {
    $_.Name -like 'microbot-2.6.21.public-*.jar' -or
    $_.Name -like 'microbot-local.jar.swap-backup*'
  } |
  Select-Object -ExpandProperty Name
$targets = @($removeNames + $discoveredNames) | Sort-Object -Unique |
  ForEach-Object { Join-Path $launcher $_ }
$targets | ForEach-Object {
    $resolvedParent = [IO.Path]::GetFullPath((Split-Path -Parent $_))
    if ($resolvedParent -ne [IO.Path]::GetFullPath($launcher)) {
        throw "Refusing cleanup outside launcher: $_"
    }
    [pscustomobject]@{Path=$_; Exists=Test-Path -LiteralPath $_}
}
```

Expected: every discovered name is converted to an exact path directly under the launcher directory;
no recursive path is used, and deletion receives no wildcard.

- [ ] **Step 2: Remove only the verified obsolete files**

Run:

```powershell
foreach ($target in $targets) {
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
}
```

Expected: only the verified obsolete files are removed. This cleanup is permanent except for copies recoverable elsewhere.

- [ ] **Step 3: Verify final launcher state and selection**

Run:

```powershell
Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.microbot') -File -Filter '*.jar' |
    Sort-Object Name | Select-Object Name,Length,LastWriteTime
$prefsPath = Join-Path $env:USERPROFILE '.microbot\resource_versions.json'
$prefs = Get-Content -Raw -LiteralPath $prefsPath | ConvertFrom-Json
$prefs.version_pref
```

Expected JARs:

```text
microbot-2.6.21.jar
microbot-local.jar
```

Expected preference: `local`.

---

### Task 12: Retire the Unreachable Legacy Movement Loop After Live Acceptance

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WalkerRouteState.java` only for fields proven legacy-only
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java`
- Modify: `docs/entity-guides/movement.md`

**Interfaces:**
- Consumes: passing live evidence and zero-active-caller ASM gate
- Produces: one movement owner without removing shared door, transport, pathfinder, arrival, banking, or public API behavior

- [ ] **Step 1: Generate an exact legacy call and field inventory**

Run:

```powershell
rg -n 'processWalk\(' runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java
rg -n 'interimTargetWp|stuckCount|partialRetries|idleNudge' runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker
```

Expected: `processWalk` has no facade caller. Classify each helper/field by actual references; do not delete anything still used by the executor runtime, doors, transports, recovery, overlays, or public APIs.

- [ ] **Step 2: Remove only the unreachable movement-loop body and private helpers with zero remaining callers**

Use `apply_patch` in reviewable groups. Preserve:

```text
public Rs2Walker methods
WebWalkExecutor wiring
runtimeArrived
dispatchMiniMapTarget and route-safe fallback
runtimeHandleRouteEdge
door/transport/dialogue handlers
pathfinder target/recalculate lifecycle
banked-transport entry points
target generation and cancellation
```

Delete no helper based only on its name; require a zero-caller search and compilation after each group.

- [ ] **Step 3: Run the full Walker gate after each removal group**

Run:

```powershell
.\gradlew.bat :client:compileJava :client:compileTestJava
.\gradlew.bat :client:runUnitTests `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.WebWalkExecutorWiringTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.Rs2WalkerPublicApiCompatibilityTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RuneLiteWebWalkRuntimeTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.Rs2WalkerUnitTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.RouteRecoveryTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.door.Rs2WalkerAwaitsTest" `
  --tests "net.runelite.client.plugins.microbot.util.walker.transport.Rs2WalkerTransportAwaitsTest" `
  --rerun-tasks
```

Expected: all tests pass and the public API compatibility test remains unchanged.

- [ ] **Step 4: Run final gates and commit legacy retirement**

Run:

```powershell
.\gradlew.bat :client:checkstyleMain :client:checkstyleTest :client:assemble check
git diff --check
git status --short
git add -- runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/WalkerRouteState.java runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/walker/WebWalkExecutorWiringTest.java docs/entity-guides/movement.md
git commit -m "refactor(walker): retire unreachable legacy movement loop"
```

Expected: a separate post-live commit. If any supposedly legacy helper still has a valid caller, leave it in place and exclude it from this commit.
