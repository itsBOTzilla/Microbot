package net.runelite.client.plugins.microbot.shortestpath;

import java.util.Set;
import java.util.stream.Stream;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StrongholdPortalRequirementsTest
{
    @Test
    public void faminePortalRequiresSlapHeadReward()
    {
        TransportVarbit unlock = requirePortalUnlock(
                new WorldPoint(2040, 5240, 0),
                new WorldPoint(2021, 5223, 0),
                19005,
                2310);

        assertTrue(unlock.getValue() == 1
                && unlock.getOperator() == TransportVarbit.Operator.EQUAL);
        assertTrue("precondition: an unlocked portal must pass the catalog requirement",
                unlock.matches(1));
        assertFalse("a locked portal must be excluded from route planning",
                unlock.matches(0));
    }

    @Test
    public void pestilencePortalRequiresBoxOfHealthRewardOnEveryObjectTile()
    {
        WorldPoint destination = new WorldPoint(2146, 5287, 0);
        Stream.of(
                        new WorldPoint(2120, 5257, 0),
                        new WorldPoint(2119, 5258, 0),
                        new WorldPoint(2121, 5258, 0))
                .forEach(origin -> requirePortalUnlock(origin, destination, 23707, 2311));
    }

    @Test
    public void deathPortalRequiresCradleOfLifeReward()
    {
        requirePortalUnlock(
                new WorldPoint(2364, 5212, 0),
                new WorldPoint(2341, 5219, 0),
                23922,
                2312);
    }

    private static TransportVarbit requirePortalUnlock(
            WorldPoint origin, WorldPoint destination, int objectId, int varbitId)
    {
        Set<Transport> candidates = Transport.loadAllFromResources().get(origin);
        assertNotNull("Stronghold portal origin is missing: " + origin, candidates);

        Transport portal = candidates.stream()
                .filter(transport -> transport.getObjectId() == objectId)
                .filter(transport -> destination.equals(transport.getDestination()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Stronghold portal is missing at " + origin));

        assertTrue("duplicate Stronghold portal rows can bypass unlock requirements at " + origin,
                candidates.stream()
                        .filter(transport -> transport.getObjectId() == objectId)
                        .filter(transport -> destination.equals(transport.getDestination()))
                        .allMatch(transport -> hasExactUnlock(transport, varbitId)));

        return portal.getVarbits().stream()
                .filter(requirement -> requirement.getVarbitId() == varbitId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Stronghold portal is missing varbit " + varbitId + " at " + origin));
    }

    private static boolean hasExactUnlock(Transport transport, int varbitId)
    {
        return transport.getVarbits().stream().anyMatch(requirement ->
                requirement.getVarbitId() == varbitId
                        && requirement.getValue() == 1
                        && requirement.getOperator() == TransportVarbit.Operator.EQUAL);
    }
}
