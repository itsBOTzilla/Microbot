package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StrongholdTargetSelectionTest
{
    private static final WorldPoint FAMINE_START = new WorldPoint(2043, 5243, 0);

    @Test
    public void convertsFamineMapDisplayCoordinateToRealDungeonTile()
    {
        assertEquals(new WorldPoint(2015, 5228, 0), ShortestPathPlugin.resolveStrongholdMapTarget(
                FAMINE_START, new WorldPoint(1919, 5132, 0)));
    }

    @Test
    public void convertsStrongholdTargetEvenWhenCurrentMapDataContainsIt()
    {
        assertEquals(new WorldPoint(2004, 5216, 0), ShortestPathPlugin.resolveSelectedWorldMapTarget(
                FAMINE_START, new WorldPoint(1908, 5120, 0), true));
    }
}
