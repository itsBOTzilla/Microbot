package net.runelite.client.plugins.microbot.shortestpath;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VarrockMuseumGateRequirementsTest
{
    private static final int MUSEUM_GATE_OBJECT_ID = 24536;

    @Test
    public void museumCleaningGateRequiresAMembersWorldInBothDirections()
    {
        assertMembersGate(
                new WorldPoint(3261, 3446, 0),
                new WorldPoint(3261, 3447, 0));
        assertMembersGate(
                new WorldPoint(3261, 3447, 0),
                new WorldPoint(3261, 3446, 0));
    }

    private static void assertMembersGate(WorldPoint origin, WorldPoint destination)
    {
        Set<Transport> candidates = Transport.loadAllFromResources().get(origin);
        assertNotNull("Varrock Museum gate origin is missing: " + origin, candidates);
        assertTrue("every matching gate row must be rejected on F2P worlds: " + origin,
                candidates.stream()
                        .filter(transport -> transport.getObjectId() == MUSEUM_GATE_OBJECT_ID)
                        .filter(transport -> destination.equals(transport.getDestination()))
                        .allMatch(Transport::isMembers));
        assertTrue("Varrock Museum gate row is missing: " + origin,
                candidates.stream()
                        .anyMatch(transport -> transport.getObjectId() == MUSEUM_GATE_OBJECT_ID
                                && destination.equals(transport.getDestination())));
    }
}
