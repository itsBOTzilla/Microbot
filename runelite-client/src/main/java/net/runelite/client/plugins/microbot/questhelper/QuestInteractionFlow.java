package net.runelite.client.plugins.microbot.questhelper;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;

/** Same-invocation routing handoff with fresh caller-owned interaction validation. */
final class QuestInteractionFlow
{
    /** Custom dialogue choices keep priority; both custom and generic input share one page gate. */
    static boolean allowGenericDialogue(BooleanSupplier pageGate, BooleanSupplier customLogic)
    {
        return pageGate.getAsBoolean() && customLogic.getAsBoolean();
    }

    private QuestInteractionFlow()
    {
    }

    static boolean run(BooleanSupplier current, BooleanSupplier ready,
                       Supplier<WalkerState> walk, BooleanSupplier dispatch)
    {
        if (!current.getAsBoolean())
        {
            return false;
        }
        if (!ready.getAsBoolean())
        {
            if (walk.get() != WalkerState.ARRIVED)
            {
                return false;
            }
            return current.getAsBoolean() && ready.getAsBoolean() && dispatch.getAsBoolean();
        }
        return current.getAsBoolean() && dispatch.getAsBoolean();
    }
}
