package net.runelite.client.plugins.microbot.questhelper;

import java.lang.reflect.Method;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class QuestDetailedStepTest
{
    @Test
    public void locationlessDetailedStepDoesNotCrash() throws Exception
    {
        QuestScript script = new QuestScript();
        DetailedQuestStep step = new DetailedQuestStep(null, "Read the notes.");
        Method applyDetailedQuestStep = QuestScript.class.getDeclaredMethod(
            "applyDetailedQuestStep", DetailedQuestStep.class);
        applyDetailedQuestStep.setAccessible(true);

        assertFalse((boolean) applyDetailedQuestStep.invoke(script, step));
    }
}
