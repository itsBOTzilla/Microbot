package net.runelite.client.plugins.microbot.questhelper.logic;

import net.runelite.api.GraphicsObject;

public interface IQuest {
    boolean executeCustomLogic();

    default long customLogicIntervalNanos() {
        return 600_000_000L;
    }

    default boolean customLogicRunsWhileAnimating() {
        return false;
    }

    default boolean onGraphicsObjectCreated(GraphicsObject graphicsObject) {
        return false;
    }

    default void reset() {
    }
}
