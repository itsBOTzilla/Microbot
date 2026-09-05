package net.runelite.client.plugins.microbot.questhelper;

import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/** Physical scene geometry shared by object approach selection and interaction readiness. */
final class QuestObjectGeometry
{
    private QuestObjectGeometry() {}

    /** Call on the client thread; GameObjects retain their complete footprint. */
    static WorldArea area(WorldView view, TileObject object)
    {
        if (view == null || object == null) return null;
        if (object instanceof GameObject)
        {
            GameObject gameObject = (GameObject) object;
            Point minimum = gameObject.getSceneMinLocation();
            if (minimum == null) return null;
            return new WorldArea(WorldPoint.fromScene(view, minimum.getX(), minimum.getY(),
                    gameObject.getPlane()), gameObject.sizeX(), gameObject.sizeY());
        }
        WorldPoint location = object.getWorldLocation();
        return location == null ? null : location.toWorldArea();
    }

    /** Call on the client thread with physical (not template) coordinates. */
    static boolean hasLineOfSight(WorldView view, WorldPoint player, TileObject object)
    {
        WorldArea target = area(view, object);
        return target != null && player != null
                && target.hasLineOfSightTo(view, player.toWorldArea());
    }
}
