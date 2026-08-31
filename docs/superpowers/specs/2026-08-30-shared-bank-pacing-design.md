# Shared Bank Interaction Pacing

## Problem

`Rs2Bank.openBank()`, an inventory mutation, and `Rs2Bank.closeBank()` can complete in one script invocation. Live Mining runs therefore finish an entire bank cycle in roughly three to four seconds. Individual scripts can add sleeps, but that produces inconsistent behavior and leaves every other caller of `Rs2Bank` unchanged.

## Decision

Own humanized banking cadence in `Rs2Bank`, immediately before bank mutations and around close. Read-only bank APIs remain delay-free. This gives Mining and every other caller the same behavior without script-specific timing code.

Each confirmed bank session uses these independently sampled ranges:

- confirmed open to first mutation: 450-1400 ms
- later mutations in the same session: 250-950 ms
- final mutation to close dispatch: 450-1350 ms
- confirmed close to caller continuation: 300-1100 ms

Approximately eight percent of sessions receive one additional 2500-4000 ms hesitation, assigned to either the first mutation or close phase. A session never receives more than one long hesitation.

## Architecture

`BankPacing` is a package-private state machine with injected random, monotonic-clock, and sleep boundaries. It owns phase ordering, one-time hesitation selection, stable per-phase deadlines, and reset behavior. Production wiring uses `Rs2Random`, `System.nanoTime`, and an interrupt-aware background-thread sleeper.

`Rs2Bank` begins the session only after the bank and live bank-container snapshot are confirmed. Its internal item-menu dispatch and bulk deposit buttons await the mutation phase. `closeBank()` awaits the close phase, verifies the interface closed, awaits the departure phase, and resets the session.

Existing one-off bank sleeps are removed so callers do not receive two independent delays. Read-only calls such as `isOpen()`, `bankItems()`, and `hasBankItem()` do not enter the pacing state machine.

## Safety and lifecycle

- Client-thread calls never block; they preserve the existing dispatch behavior without pacing.
- An interrupted background wait restores the interrupt flag, cancels the action, and resets the session.
- Failed close leaves the session active because the bank remains open.
- Successful close, external closure observed by a later open, bank-cache invalidation, logout/world transition, or an abandoned interrupted session resets pacing.
- Existing bank-open, container-epoch, inventory-change, and close postconditions remain authoritative.

## Verification

Deterministic unit tests cover ranges, phase order, stable deadlines across early wakeups, one hesitation maximum, lifecycle reset, and interruption. Existing bank tests, client compilation, unit tests, and shaded-jar assembly remain required. Live acceptance must distinguish the built/staged artifact from the artifact loaded by the running client.
