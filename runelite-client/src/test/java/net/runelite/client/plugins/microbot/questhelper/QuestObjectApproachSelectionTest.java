package net.runelite.client.plugins.microbot.questhelper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QuestObjectApproachSelectionTest
{
    @Test
    public void multiTileObjectApproachSkipsTilesInsideTheObjectFootprint() throws Exception
    {
        Method selector;
        try
        {
            selector = QuestScript.class.getDeclaredMethod(
                    "selectObjectApproachTile",
                    WorldArea.class,
                    WorldPoint.class,
                    Predicate.class,
                    Predicate.class);
        }
        catch (NoSuchMethodException ex)
        {
            fail("Object approach selection must account for the full object footprint");
            return;
        }

        WorldArea twoTileCrate = new WorldArea(3008, 3207, 2, 1, 0);
        WorldPoint player = new WorldPoint(3008, 3207, 0);
        WorldPoint insideInteractionTile = new WorldPoint(3010, 3207, 0);
        Predicate<WorldPoint> walkable = point -> true;
        Predicate<WorldPoint> visibleFromObjectSide = insideInteractionTile::equals;

        WorldPoint selected = (WorldPoint) selector.invoke(
                null, twoTileCrate, player, walkable, visibleFromObjectSide);

        assertEquals(insideInteractionTile, selected);
    }

    @Test
    public void unloadedObjectOnAnotherPlaneStillRequiresAnApproach()
    {
        WorldPoint player = new WorldPoint(3200, 3200, 0);
        WorldPoint step = new WorldPoint(3200, 3200, 1);

        assertTrue(QuestScript.isAwayFromObjectStep(player, step));
        assertFalse(QuestScript.isAwayFromObjectStep(player, new WorldPoint(3201, 3200, 0)));
    }

    @Test
    public void unloadedObjectStepCannotReportCompletion()
    {
        assertFalse(QuestScript.canCompleteObjectStep(false));
        assertTrue(QuestScript.canCompleteObjectStep(true));
    }

    @Test
    public void emptyPathHasNoEndpointNearTheObjectStep()
    {
        WorldPoint step = new WorldPoint(3200, 3200, 0);

        assertFalse(QuestScript.pathEndsNearObjectStep(null, step));
        assertFalse(QuestScript.pathEndsNearObjectStep(Collections.emptyList(), step));
        assertTrue(QuestScript.pathEndsNearObjectStep(
                List.of(new WorldPoint(3190, 3190, 0), new WorldPoint(3201, 3200, 0)), step));
    }
}
