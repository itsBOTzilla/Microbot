package shortestpath.pathfinder;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import shortestpath.PrimitiveIntHashMap;
import shortestpath.PrimitiveIntList;
import shortestpath.ShortestPathConfig;
import shortestpath.TeleportationItem;
import shortestpath.WorldPointUtil;
import shortestpath.transport.Transport;
import shortestpath.transport.TransportType;
import shortestpath.transport.requirement.TransportItems;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VendoredPathfinderRegressionTest
{
	@Test
	public void concurrentArrayGrowthUsesTheShortestSnapshot() throws Exception
	{
		NodeGraph graph = new NodeGraph(2);
		int start = graph.createStart(WorldPointUtil.packWorldPoint(3200, 3200, 0));
		int next = graph.createTile(WorldPointUtil.packWorldPoint(3201, 3200, 0), start, false);

		setField(graph, "flags", new byte[1]);
		setField(graph, "packedPosition", new int[1]);

		assertTrue(graph.getPathSteps(next).isEmpty());
		assertEquals(WorldPointUtil.UNDEFINED, graph.getClosestTilePosition(next));
	}

	@Test
	public void outOfExtentRegionsNeverAliasAnInRangeCollisionSlice()
	{
		SplitFlagMap map = SplitFlagMap.fromResources();
		SplitFlagMap.RegionExtent extents = SplitFlagMap.getRegionExtents();
		// In the pinned collision archive, this out-of-range X used to flatten onto the populated
		// first region of the following row and return that region's bit zero.
		int outsideX = (extents.getMaxX() + 1) * 64;
		int aliasingY = (extents.getMinY() + 12) * 64;

		assertFalse(map.get(outsideX, aliasingY, 0, 0));
	}

	@Test
	public void blockedNeighborTransportUsesExpandedNeighborPosition()
	{
		SplitFlagMap staticMap = SplitFlagMap.fromResources();
		CollisionMap collisionMap = new CollisionMap(staticMap, (x, y, plane, flag) -> false);
		VisitedTiles visited = new VisitedTiles(collisionMap);
		PathfinderConfig config = mock(PathfinderConfig.class);
		NodeGraph graph = new NodeGraph(16);
		int current = WorldPointUtil.packWorldPoint(3200, 3200, 0);
		int expandedNeighbor = WorldPointUtil.packWorldPoint(3201, 3200, 0);
		Transport permutation = new Transport.TransportBuilder()
			.origin(Transport.LOCATION_PERMUTATION)
			.destination(WorldPointUtil.packWorldPoint(3210, 3200, 0))
			.type(TransportType.TRANSPORT)
			.build();
		PrimitiveIntHashMap<Transport[]> transports = new PrimitiveIntHashMap<>(1);
		transports.put(expandedNeighbor, new Transport[]{permutation});
		when(config.getTransportsPacked(false)).thenReturn(transports);
		when(config.getAdditionalWalkingCost(expandedNeighbor)).thenReturn(7);

		int start = graph.createStart(current);
		PrimitiveIntList neighbors = collisionMap.getNeighbors(start, visited, config, 0, false, graph);

		boolean foundExpandedNeighbor = false;
		boolean foundPermutationSentinel = false;
		for (int i = 0; i < neighbors.size(); i++)
		{
			int neighbor = neighbors.get(i);
			if (!graph.isTile(neighbor))
			{
				continue;
			}
			foundExpandedNeighbor |= graph.packedPosition(neighbor) == expandedNeighbor;
			foundPermutationSentinel |= graph.packedPosition(neighbor) == Transport.LOCATION_PERMUTATION;
		}

		assertTrue(foundExpandedNeighbor);
		assertFalse(foundPermutationSentinel);
		verify(config).getAdditionalWalkingCost(expandedNeighbor);
		verify(config, never()).getAdditionalWalkingCost(Transport.LOCATION_PERMUTATION);
	}

	@Test
	public void duplicateItemIdsAreAggregatedAcrossContainers() throws Exception
	{
		int itemId = 12345;
		Client client = mock(Client.class);
		ShortestPathConfig config = mock(ShortestPathConfig.class);
		ItemContainer inventory = containerWith(itemId, 2);
		ItemContainer equipment = containerWith(itemId, 3);
		ItemContainer bank = containerWith(itemId, 4);
		when(config.useTeleportationItems()).thenReturn(TeleportationItem.INVENTORY_AND_BANK);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
		PathfinderConfig pathfinderConfig = new PathfinderConfig(
			client,
			config,
			SplitFlagMap.fromResources(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap());
		pathfinderConfig.bank = bank;
		TransportItems requirement = new TransportItems(
			new int[][]{{itemId}},
			new int[][]{new int[0]},
			new int[][]{new int[0]},
			new int[]{9});
		Method hasRequiredItems = PathfinderConfig.class.getDeclaredMethod(
			"hasRequiredItems",
			TransportItems.class,
			boolean.class,
			boolean.class,
			boolean.class,
			boolean.class);
		hasRequiredItems.setAccessible(true);

		boolean available = (boolean) hasRequiredItems.invoke(
			pathfinderConfig, requirement, true, true, true, false);

		assertTrue(available);
	}

	private static ItemContainer containerWith(int itemId, int quantity)
	{
		ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[]{new Item(itemId, quantity)});
		return container;
	}

	private static void setField(NodeGraph graph, String name, Object value) throws Exception
	{
		Field field = NodeGraph.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(graph, value);
	}
}
