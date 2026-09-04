# Quest Helper interaction responsiveness

QuestScript observes its active step every 200 ms. Ordinary NPC and object handlers use
`Rs2Walker.walkWithStateUntil` to yield when a fresh, visible target has an unobstructed
local approach. They revalidate the active step and target before dispatching in the same
handler invocation. The shared walker retains responsibility for doors, transports and
route recovery; `walkStep` is not a replacement for that pipeline.

## Interaction ownership

- A successful dispatch registers one pending attempt. Movement and animation acknowledge
  activity, but movement alone does not complete an object action or mark an object handled.
- Pending activity renews a 1.2-second acknowledgement window. A short animation gap cannot
  trigger a duplicate click. An idle expired attempt retries only after fresh validation.
- Item quantity changes and action-result widgets can complete an attempt. An observed
  animation near the target can complete after sustained idle. Dialogue confirms an NPC
  dispatch; ownership persists during dialogue to prevent reopening the same conversation.
- Shutdown, reset, logout and input takeover discard pending state. A changed active step
  stops the old handoff; target disappearance is invalidation, not proof of quest completion.
- Item use still waits for confirmation of the selected item before revalidating and
  dispatching through the existing NPC/raw object interaction utilities.

Readiness uses physical scene coordinates and bounded scene collision checks, including in
instances. Only the full walker's target is converted to instance-template coordinates.
This avoids comparing a template player location with an adjacent physical NPC/object.

## Dialogue and legacy handlers

The blanket 4–7 second post-dialogue cooldown is removed. Dialogue pages are represented by
immutable copies of visible widget values, including dynamic option children and continue
readiness. An unchanged page has a 1.2-second retry window; a new page is immediately eligible.
Page contents are never logged.

Quest-specific dialogue logic retains priority over generic fallback under the same page
gate. Legacy non-dialogue custom handlers retain a separate 600 ms evaluation cadence and
busy-state protection. Their existing quest-specific waits remain in place.

## Verification and runtime qualification

The regression suite covers handoff ordering, stale readiness, walker exit states, failed
dispatch, item-selection order, pending movement, animation gaps, dialogue-page changes,
scene collision walls/corners, and instance-coordinate preservation. Existing quest and
walker suites remain required, as do Checkstyle and client-thread guardrails.

Enable DEBUG for `net.runelite.client.plugins.microbot.questhelper.QuestScript` only while
measuring. Transition events include `walk_return`, `target_ready`, `dispatch`,
`dispatch_timing`, `progress`, `complete`, `invalidated` and `timeout`. `readyToDispatch`
measures elapsed time from observed readiness to accepted dispatch. A negative value means
the readiness timestamp was unavailable and must not enter latency percentiles.

Live acceptance is separate from unit-test success. Confirm the launched PID/JAR and build
identity, then measure at least 20 ordinary ready-target dispatches. Target p95 is 600 ms
when no cutscene, animation or input-ownership blocker is present. Exercise NPC dialogue,
ordinary and multi-object steps, item-on-object, and these reported door edges:

- `(3246,3193,0)` to `(3247,3193,0)`
- `(3109,3167,0)` to `(3109,3166,0)`
- `(3110,9559,0)` to `(3111,9559,0)`

Door throttling before handoff must be measured separately. Repeated suppressed-attempt
messages alone do not justify reducing shared door cooldowns.

The execution record must state source tests, packaged hash, staging, restart and live
measurement separately. The implementation plan is in
[the September 4 plan](superpowers/plans/2026-09-04-questscript-interaction-responsiveness.md).
