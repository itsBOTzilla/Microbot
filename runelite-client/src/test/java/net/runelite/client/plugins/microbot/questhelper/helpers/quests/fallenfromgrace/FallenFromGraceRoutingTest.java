package net.runelite.client.plugins.microbot.questhelper.helpers.quests.fallenfromgrace;

import java.lang.reflect.Field;
import java.util.Map;
import net.runelite.client.plugins.microbot.questhelper.QuestHelperConfig;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.steps.ConditionalStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class FallenFromGraceRoutingTest
{
	@Test
	public void stageSixRetainsTheIslandTravelFallback() throws Exception
	{
		FallenFromGrace helper = new FallenFromGrace();
		Field config = QuestHelper.class.getDeclaredField("config");
		config.setAccessible(true);
		config.set(helper, mock(QuestHelperConfig.class));
		Map<Integer, QuestStep> steps = helper.loadSteps();

		assertTrue(steps.get(6) instanceof ConditionalStep);
		assertTrue(((ConditionalStep) steps.get(6)).getSteps().size() >= 2);
	}
}
