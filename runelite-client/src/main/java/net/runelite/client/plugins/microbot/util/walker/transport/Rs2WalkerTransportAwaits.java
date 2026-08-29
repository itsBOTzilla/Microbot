package net.runelite.client.plugins.microbot.util.walker.transport;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.shared.Rs2WalkerProgress;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

public final class Rs2WalkerTransportAwaits {
    private Rs2WalkerTransportAwaits() {
    }

    public static boolean didCurrentTileTransportProgress(WorldPoint before, WorldPoint expectedDestination,
                                                          WorldPoint target, boolean dialogueWasOpen) {
        if (before == null) {
            return false;
        }
        boolean progressedDuringWait = sleepUntil(() -> {
            WorldPoint now = Rs2Player.getWorldLocation();
            return hasTransportProgress(before, now, expectedDestination, target,
                    dialogueWasOpen, Rs2Dialogue.isInDialogue());
        }, 1800);
        WorldPoint after = Rs2Player.getWorldLocation();
        return resolveTransportProgress(progressedDuringWait, before, after, expectedDestination, target,
                dialogueWasOpen, Rs2Dialogue.isInDialogue());
    }

    static boolean resolveTransportProgress(boolean progressedDuringWait,
                                            WorldPoint before, WorldPoint after,
                                            WorldPoint expectedDestination, WorldPoint target,
                                            boolean dialogueWasOpen, boolean dialogueOpen) {
        return progressedDuringWait || hasTransportProgress(
                before, after, expectedDestination, target, dialogueWasOpen, dialogueOpen);
    }

    static boolean hasTransportProgress(WorldPoint before, WorldPoint now, WorldPoint expectedDestination,
                                        WorldPoint target, boolean dialogueWasOpen, boolean dialogueOpen) {
        return !dialogueWasOpen && dialogueOpen
                || Rs2WalkerProgress.hasMovementOrProgress(before, now, expectedDestination, target);
    }
}
