# Shared Bank Interaction Pacing Implementation Plan

**Goal:** Move randomized banking cadence into the shared `Rs2Bank` layer so all scripts inherit it while preserving bank postconditions and client-thread safety.

**Architecture:** Add a small, deterministic pacing state machine beside `Rs2Bank`. Route internal bank mutations and close lifecycle through it, remove overlapping local sleeps, and leave read-only APIs untouched.

**Tech stack:** Java 11, JUnit 5, Gradle.

---

### Task 1: Specify the pacing state machine with red tests

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/bank/BankPacingTest.java`

1. Add deterministic fakes for random sampling, monotonic time, and sleep.
2. Test first/subsequent/close/departure phase ranges and order.
3. Test a deadline is sampled once across an early wakeup.
4. Test at most one long hesitation per session.
5. Test successful close reset and interrupted-wait reset.
6. Run the focused test and confirm it fails because `BankPacing` does not exist.

### Task 2: Implement the pacing state machine

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/bank/BankPacing.java`

1. Implement session begin/reset and phase transitions.
2. Implement exact randomized ranges and session-level hesitation selection.
3. Implement stable monotonic deadlines and interrupt propagation.
4. Run `BankPacingTest` until green.

### Task 3: Integrate pacing into `Rs2Bank`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/bank/Rs2Bank.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/bank/BankPacingTest.java`

1. Wire a production pacer that does not sleep on the client thread.
2. Begin/reset sessions through every bank-open overload and cache invalidation.
3. Pace central item menu dispatch plus bulk-deposit and container-empty mutations.
4. Pace before close and after confirmed close.
5. Remove overlapping per-item and open-overload sleeps.
6. Add source-wiring assertions for the shared action funnels.
7. Run focused bank tests.

### Task 4: Verify and publish

1. Run bank-focused unit tests and `:client:compileJava`.
2. Run the full safe unit-test task and `:client:assemble`.
3. Complete Java-specific and general code review; fix findings and rerun verification.
4. Record the shaded-jar SHA-256 and qualify staging separately from live loading.
5. Commit with a conventional message, push to `itsBOTzilla/Microbot`, and open a focused PR.
