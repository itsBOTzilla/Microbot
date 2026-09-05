package net.runelite.client.plugins.microbot.questhelper;

import java.io.InputStream;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class QuestCustomLogicLifecycleTest
{
    @Test
    public void selectedQuestIsCapturedWithOneNullablePluginRead() throws Exception
    {
        QuestHelperPlugin plugin = mock(QuestHelperPlugin.class);
        QuestHelper selected = mock(QuestHelper.class);
        when(plugin.getSelectedQuest()).thenReturn(selected, (QuestHelper) null);

        assertSame(selected, QuestScript.selectedQuestSnapshot(plugin));
        verify(plugin, times(1)).getSelectedQuest();
        assertNull(QuestScript.selectedQuestSnapshot(null));
    }

    @Test
    public void selectedQuestReadsAreCentralized() throws Exception
    {
        ClassNode script = new ClassNode();
        try (InputStream input = QuestScript.class.getResourceAsStream("QuestScript.class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(script, ClassReader.SKIP_FRAMES);
        }

        int selectedQuestReads = 0;
        for (MethodNode method : script.methods)
        {
            for (AbstractInsnNode instruction : method.instructions)
            {
                if (instruction instanceof MethodInsnNode)
                {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.endsWith("/QuestHelperPlugin") && call.name.equals("getSelectedQuest"))
                    {
                        selectedQuestReads++;
                        assertEquals("Only the null-safe snapshot helper may read the mutable selection",
                                "selectedQuestSnapshot", method.name);
                    }
                }
            }
        }
        assertEquals(1, selectedQuestReads);
    }

    @Test
    public void schedulerTickUsesOneQuestAndCurrentStepSnapshot() throws Exception
    {
        ClassNode script = new ClassNode();
        try (InputStream input = QuestScript.class.getResourceAsStream("QuestScript.class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(script, ClassReader.SKIP_FRAMES);
        }

        int schedulerTickMethods = 0;
        for (MethodNode method : script.methods)
        {
            int selectedQuestSnapshots = 0;
            int currentStepReads = 0;
            boolean schedulerTick = false;
            for (AbstractInsnNode instruction : method.instructions)
            {
                if (!(instruction instanceof MethodInsnNode))
                {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.endsWith("/QuestScript") && call.name.equals("observePendingInteraction"))
                {
                    schedulerTick = true;
                }
                if (call.owner.endsWith("/QuestScript") && call.name.equals("selectedQuestSnapshot"))
                {
                    selectedQuestSnapshots++;
                }
                if (call.owner.endsWith("/QuestHelper") && call.name.equals("getCurrentStep"))
                {
                    currentStepReads++;
                }
            }
            if (schedulerTick)
            {
                schedulerTickMethods++;
                assertEquals("A scheduler tick must use one selected-quest snapshot", 1, selectedQuestSnapshots);
                assertEquals("A scheduler tick must use one current-step snapshot", 1, currentStepReads);
            }
        }
        assertEquals(1, schedulerTickMethods);
    }

    @Test
    public void onlyOptedInCustomLogicRunsDuringAnimation()
    {
        assertTrue(QuestScript.shouldPauseBeforeCustomLogic(false, false, true, false));
        assertFalse(QuestScript.shouldPauseBeforeCustomLogic(false, false, true, true));
        assertTrue(QuestScript.shouldPauseBeforeCustomLogic(false, true, true, true));
        assertFalse(QuestScript.shouldPauseBeforeCustomLogic(true, false, true, false));
    }

    @Test
    public void clearingInteractionStateAlsoClearsCustomQuestState() throws Exception
    {
        ClassNode script = new ClassNode();
        try (InputStream input = QuestScript.class.getResourceAsStream("QuestScript.class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(script, ClassReader.SKIP_FRAMES);
        }

        int resetCalls = 0;
        for (MethodNode method : script.methods)
        {
            if (!method.name.equals("clearInteractionState"))
            {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions)
            {
                if (instruction instanceof MethodInsnNode)
                {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.endsWith("/QuestRegistry") && call.name.equals("resetAll"))
                    {
                        resetCalls++;
                    }
                }
            }
        }
        assertEquals("Quest custom state must not survive a stopped or restarted script", 1, resetCalls);
    }
}
