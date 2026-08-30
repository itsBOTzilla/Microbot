package net.runelite.client.plugins.microbot.shortestpath.pathfinder;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shortestpath.WorldPointUtil;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CollisionMapTransportSuppressionTest {

    @Test
    public void adjacentSamePlaneTransportFamilyIsNotSuppressed() {
        WorldPoint origin = new WorldPoint(3200, 3200, 0);
        WorldPoint destination = new WorldPoint(3201, 3200, 0);

        assertFalse(CollisionMap.isSuppressedTransportEdge(
                packed(destination), packed(origin), packed(destination), packed(origin)));
        assertFalse(CollisionMap.isSuppressedTransportEdge(
                packed(origin), packed(destination), packed(destination), packed(origin)));
    }

    @Test
    public void distinctTransportFamilySuppressesOnlyTheRequestedDirection() {
        WorldPoint underground = new WorldPoint(3096, 9867, 0);
        WorldPoint surface = new WorldPoint(3096, 3468, 0);

        assertTrue(CollisionMap.isSuppressedTransportEdge(
                packed(underground), packed(surface), packed(underground), packed(surface)));
        assertFalse(CollisionMap.isSuppressedTransportEdge(
                packed(surface), packed(underground), packed(underground), packed(surface)));
    }

    private static int packed(WorldPoint point) {
        return WorldPointUtil.packWorldPoint(point);
    }
}
