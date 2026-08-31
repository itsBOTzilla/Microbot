package net.runelite.client.plugins.microbot.util.bank;

/**
 * Session-scoped timing for bank mutations.
 *
 * <p>The state machine samples one monotonic deadline per phase. If a sleeper wakes early, the
 * remaining time is awaited against the same deadline instead of drawing a new delay. Instances
 * are synchronized because {@link Rs2Bank} is a static utility shared by script executors.</p>
 */
final class BankPacing
{
    private static final int HESITATION_CHANCE_PERCENT = 8;

    private final RandomSource random;
    private final MonotonicClock clock;
    private final Sleeper sleeper;

    private boolean sessionActive;
    private boolean firstActionPending;
    private Phase hesitationPhase;
    private boolean hesitationConsumed;
    private Phase pendingPhase;
    private long pendingDeadlineMillis;
    private long generation;
    private long sessionSequence;
    private long sessionToken;

    BankPacing(RandomSource random, MonotonicClock clock, Sleeper sleeper)
    {
        this.random = random;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    synchronized void beginSession()
    {
        if (sessionActive)
        {
            return;
        }

        sessionActive = true;
        sessionToken = ++sessionSequence;
        firstActionPending = true;
        hesitationConsumed = false;
        pendingPhase = null;
        pendingDeadlineMillis = 0;

        if (random.betweenInclusive(1, 100) <= HESITATION_CHANCE_PERCENT)
        {
            hesitationPhase = random.betweenInclusive(0, 1) == 0
                    ? Phase.FIRST_ACTION
                    : Phase.BEFORE_CLOSE;
        }
        else
        {
            hesitationPhase = null;
        }
    }

    boolean awaitBeforeAction()
    {
        ScheduledWait wait;
        synchronized (this)
        {
            ensureSession();
            Phase phase = firstActionPending ? Phase.FIRST_ACTION : Phase.BETWEEN_ACTIONS;
            wait = schedule(phase);
        }
        return await(wait);
    }

    boolean awaitBeforeClose(long expectedSessionToken)
    {
        ScheduledWait wait;
        synchronized (this)
        {
            if (!matchesSession(expectedSessionToken))
            {
                return false;
            }
            wait = schedule(Phase.BEFORE_CLOSE);
        }
        return await(wait);
    }

    boolean awaitAfterClose(long expectedSessionToken)
    {
        ScheduledWait wait;
        synchronized (this)
        {
            if (!matchesSession(expectedSessionToken))
            {
                return false;
            }
            wait = schedule(Phase.AFTER_CLOSE);
        }
        return await(wait);
    }

    synchronized long sessionToken()
    {
        ensureSession();
        return sessionToken;
    }

    synchronized boolean recordActionWithoutWait()
    {
        ensureSession();
        invalidatePendingWait();
        firstActionPending = false;
        return true;
    }

    synchronized boolean recordCloseWithoutWait(long expectedSessionToken)
    {
        if (!matchesSession(expectedSessionToken))
        {
            return false;
        }
        invalidatePendingWait();
        return true;
    }

    synchronized boolean finishCloseWithoutWait(long expectedSessionToken)
    {
        if (!matchesSession(expectedSessionToken))
        {
            return false;
        }
        resetState();
        return true;
    }

    synchronized void reset()
    {
        resetState();
    }

    synchronized boolean cancelSession(long expectedSessionToken)
    {
        if (!matchesSession(expectedSessionToken))
        {
            return false;
        }
        resetState();
        return true;
    }

    synchronized boolean isSessionActive()
    {
        return sessionActive;
    }

    private void ensureSession()
    {
        if (!sessionActive)
        {
            beginSession();
        }
    }

    private ScheduledWait schedule(Phase phase)
    {
        if (pendingPhase != phase)
        {
            pendingPhase = phase;
            pendingDeadlineMillis = clock.nowMillis() + sampleDelay(phase);
        }
        return new ScheduledWait(generation, phase, pendingDeadlineMillis);
    }

    private boolean await(ScheduledWait wait)
    {
        try
        {
            while (true)
            {
                long remainingMillis;
                synchronized (this)
                {
                    if (!matches(wait))
                    {
                        return false;
                    }
                    remainingMillis = wait.deadlineMillis - clock.nowMillis();
                    if (remainingMillis <= 0)
                    {
                        complete(wait.phase);
                        return true;
                    }
                }
                sleeper.sleep(remainingMillis);
            }
        }
        catch (InterruptedException interrupted)
        {
            synchronized (this)
            {
                if (matches(wait))
                {
                    resetState();
                }
            }
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean matches(ScheduledWait wait)
    {
        return sessionActive
                && generation == wait.generation
                && pendingPhase == wait.phase
                && pendingDeadlineMillis == wait.deadlineMillis;
    }

    private boolean matchesSession(long expectedSessionToken)
    {
        return sessionActive && sessionToken == expectedSessionToken;
    }

    private void invalidatePendingWait()
    {
        generation++;
        pendingPhase = null;
        pendingDeadlineMillis = 0;
    }

    private void complete(Phase phase)
    {
        pendingPhase = null;
        pendingDeadlineMillis = 0;
        if (phase == Phase.FIRST_ACTION)
        {
            firstActionPending = false;
        }
        if (phase == Phase.AFTER_CLOSE)
        {
            resetState();
        }
    }

    private long sampleDelay(Phase phase)
    {
        long delayMillis = random.betweenInclusive(phase.minimumMillis, phase.maximumMillis);
        if (!hesitationConsumed && phase == hesitationPhase)
        {
            delayMillis += random.betweenInclusive(2500, 4000);
            hesitationConsumed = true;
        }
        return delayMillis;
    }

    private void resetState()
    {
        generation++;
        sessionActive = false;
        sessionToken = 0;
        firstActionPending = true;
        hesitationPhase = null;
        hesitationConsumed = false;
        pendingPhase = null;
        pendingDeadlineMillis = 0;
    }

    interface RandomSource
    {
        int betweenInclusive(int minimum, int maximum);
    }

    interface MonotonicClock
    {
        long nowMillis();
    }

    interface Sleeper
    {
        void sleep(long millis) throws InterruptedException;
    }

    private static final class ScheduledWait
    {
        private final long generation;
        private final Phase phase;
        private final long deadlineMillis;

        private ScheduledWait(long generation, Phase phase, long deadlineMillis)
        {
            this.generation = generation;
            this.phase = phase;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private enum Phase
    {
        FIRST_ACTION(450, 1400),
        BETWEEN_ACTIONS(250, 950),
        BEFORE_CLOSE(450, 1350),
        AFTER_CLOSE(300, 1100);

        private final int minimumMillis;
        private final int maximumMillis;

        Phase(int minimumMillis, int maximumMillis)
        {
            this.minimumMillis = minimumMillis;
            this.maximumMillis = maximumMillis;
        }
    }
}
