package net.runelite.client.plugins.microbot.questhelper;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class QuestCustomLogicLifecycleTest
{
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
