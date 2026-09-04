package net.runelite.client.plugins.microbot.questhelper;

/** Quest-local observation policy. Activity acknowledges an attempt but never proves completion. */
final class QuestInteractionAttempt
{
    private static final long ACKNOWLEDGEMENT_NANOS = 1_200_000_000L;

    enum Result
    {
        WAIT, COMPLETE, RETRY, INVALIDATED
    }

    private final long startedNanos;
    private long deadlineNanos;
    private boolean progressed;

    QuestInteractionAttempt(long nowNanos, long deadlineNanos)
    {
        this.startedNanos = nowNanos;
        this.deadlineNanos = deadlineNanos;
    }

    boolean isExpired(long nowNanos)
    {
        return nowNanos - deadlineNanos >= 0;
    }

    long elapsedMillis(long nowNanos)
    {
        return (nowNanos - startedNanos) / 1_000_000L;
    }

    boolean hasProgressed()
    {
        return progressed;
    }

    boolean isAnimationSettled(long nowNanos, boolean busy, boolean animationSeen)
    {
        return animationSeen && !busy && isExpired(nowNanos);
    }

    Result observe(long nowNanos, boolean busy, boolean completed, boolean valid)
    {
        if (!valid)
        {
            return Result.INVALIDATED;
        }
        if (completed)
        {
            return Result.COMPLETE;
        }
        if (busy)
        {
            progressed = true;
            deadlineNanos = nowNanos + ACKNOWLEDGEMENT_NANOS;
            return Result.WAIT;
        }
        return isExpired(nowNanos) ? Result.RETRY : Result.WAIT;
    }
}
