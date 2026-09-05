package net.runelite.client.plugins.microbot.questhelper.logic;

import net.runelite.api.GraphicsObject;

public interface IQuest {
    boolean executeCustomLogic();

    default boolean onGraphicsObjectCreated(GraphicsObject graphicsObject) {
        return false;
    }

    default void reset() {
    }
}
