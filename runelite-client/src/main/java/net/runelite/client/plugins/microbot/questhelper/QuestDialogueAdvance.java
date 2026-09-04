package net.runelite.client.plugins.microbot.questhelper;

import java.util.Objects;

/** Limits advance input for an unchanged page while allowing fresh pages immediately. */
final class QuestDialogueAdvance
{
    private static final long RETRY_NANOS = 1_200_000_000L;

    private Object lastPageKey;
    private long retryNanos;
    private boolean advanced;

    boolean shouldAdvance(Object pageKey, long nowNanos)
    {
        if (advanced && Objects.equals(lastPageKey, pageKey) && nowNanos - retryNanos < 0)
        {
            return false;
        }
        lastPageKey = pageKey;
        retryNanos = nowNanos + RETRY_NANOS;
        advanced = true;
        return true;
    }

    void reset()
    {
        lastPageKey = null;
        retryNanos = 0L;
        advanced = false;
    }
}
