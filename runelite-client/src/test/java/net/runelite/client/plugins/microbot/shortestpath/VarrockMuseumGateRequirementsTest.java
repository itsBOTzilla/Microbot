package net.runelite.client.plugins.microbot.shortestpath;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.PathfinderConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VarrockMuseumGateRequirementsTest
{
    private static final int MUSEUM_GATE_OBJECT_ID = 24536;
    private static final WorldPoint SOUTH_OF_GATE = new WorldPoint(3261, 3446, 0);
    private static final WorldPoint NORTH_OF_GATE = new WorldPoint(3261, 3447, 0);

    @Test
    public void museumCleaningGateRequiresAMembersWorldInBothDirections()
    {
        assertMembersGate(
                SOUTH_OF_GATE,
                NORTH_OF_GATE);
        assertMembersGate(
                NORTH_OF_GATE,
                SOUTH_OF_GATE);
    }

    @Test
    public void museumCleaningGateEndpointsAreRestrictedOnlyOnF2pWorlds() throws Exception
    {
        List<Restriction> museumGateRestrictions = Restriction.loadAllFromResources().stream()
                .filter(restriction -> restriction.getPackedWorldPoint() == WorldPointUtil.packWorldPoint(SOUTH_OF_GATE)
                        || restriction.getPackedWorldPoint() == WorldPointUtil.packWorldPoint(NORTH_OF_GATE))
                .collect(Collectors.toList());

        assertEquals("both Museum gate endpoint restrictions must be present", 2, museumGateRestrictions.size());
        assertTrue("Museum gate endpoint restrictions must require a members world",
                museumGateRestrictions.stream().allMatch(Restriction::isMembers));

        Set<Integer> f2pRestrictions = filteredRestrictionPoints(museumGateRestrictions, EnumSet.noneOf(WorldType.class));
        assertTrue("F2P routing must not enter the Museum cleaning area from the south",
                f2pRestrictions.contains(WorldPointUtil.packWorldPoint(SOUTH_OF_GATE)));
        assertTrue("F2P routing must not enter the Museum cleaning area from the north",
                f2pRestrictions.contains(WorldPointUtil.packWorldPoint(NORTH_OF_GATE)));

        Set<Integer> membersRestrictions = filteredRestrictionPoints(museumGateRestrictions,
                EnumSet.of(WorldType.MEMBERS));
        assertFalse("members routing must be able to stand south of the Museum cleaning gate",
                membersRestrictions.contains(WorldPointUtil.packWorldPoint(SOUTH_OF_GATE)));
        assertFalse("members routing must be able to stand north of the Museum cleaning gate",
                membersRestrictions.contains(WorldPointUtil.packWorldPoint(NORTH_OF_GATE)));
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

    private static Set<Integer> filteredRestrictionPoints(List<Restriction> restrictions, EnumSet<WorldType> worldTypes)
            throws Exception
    {
        Client client = mock(Client.class);
        when(client.getWorldType()).thenReturn(worldTypes);
        PathfinderConfig config = new PathfinderConfig(null, Collections.emptyMap(), restrictions, client, null);
        Method refreshRestrictionData = PathfinderConfig.class.getDeclaredMethod("refreshRestrictionData");
        refreshRestrictionData.setAccessible(true);
        refreshRestrictionData.invoke(config);
        return config.getRestrictedPointsPacked();
    }
}
