package net.runelite.client.plugins.microbot.questhelper.helpers.quests.runemysteries;

import java.util.Collection;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.questhelper.steps.ConditionalStep;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.NpcStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuneMysteriesRoutingTest
{
	@Test
	public void crossPlaneStagesRouteToNpcDestinationsInsteadOfIntermediateStairs()
	{
		RuneMysteries helper = new RuneMysteries();
		Map<Integer, QuestStep> steps = helper.loadSteps();

		assertNpcDestinations(steps.get(0), new WorldPoint(3209, 3222, 1));
		assertNpcDestinations(steps.get(1), new WorldPoint(3104, 9571, 0));
	}

	private static void assertNpcDestinations(QuestStep step, WorldPoint expectedDestination)
	{
		assertTrue("Cross-plane stage must remain conditional", step instanceof ConditionalStep);
		Collection<QuestStep> routeChoices = ((ConditionalStep) step).getSteps();
		assertTrue("Cross-plane stage must have at least one route choice", !routeChoices.isEmpty());

		for (QuestStep routeChoice : routeChoices)
		{
			assertTrue("Route choices must target the final NPC, not an intermediate object",
				routeChoice instanceof NpcStep);
			assertEquals(expectedDestination,
				((DetailedQuestStep) routeChoice).getDefinedPoint().getWorldPoint());
		}
	}
}
