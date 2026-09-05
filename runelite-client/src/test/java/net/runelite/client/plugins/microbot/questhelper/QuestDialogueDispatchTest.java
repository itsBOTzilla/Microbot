package net.runelite.client.plugins.microbot.questhelper;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

import static org.junit.Assert.*;

public class QuestDialogueDispatchTest
{
    @Test
    public void spaceIsDispatchedOnlyThroughTheGuardedContinuationCallback() throws Exception
    {
        ClassNode script = new ClassNode();
        try (InputStream input = QuestScript.class.getResourceAsStream("QuestScript.class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(script, ClassReader.SKIP_FRAMES);
        }
        String callback = null;
        boolean pageGate = false;
        for (MethodNode method : script.methods)
        {
            if (!method.name.equals("continueReadyDialogue")) continue;
            for (AbstractInsnNode instruction : method.instructions)
            {
                if (instruction instanceof InvokeDynamicInsnNode)
                {
                    for (Object argument : ((InvokeDynamicInsnNode) instruction).bsmArgs)
                    {
                        if (argument instanceof Handle) callback = ((Handle) argument).getName();
                    }
                }
                if (instruction instanceof MethodInsnNode)
                {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.owner.endsWith("/QuestDialogueContinuation") && call.name.equals("advance"))
                        pageGate = true;
                }
            }
        }
        assertNotNull("The guarded continuation must own a dispatch callback", callback);
        assertTrue("The existing ready-page and retry gate must remain", pageGate);
        int spaces = 0;
        for (MethodNode method : script.methods)
        {
            for (AbstractInsnNode instruction : method.instructions)
            {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (method.name.equals(callback))
                    assertFalse("Continue must not click the mouse", call.name.equals("click"));
                AbstractInsnNode argument = instruction.getPrevious();
                if (call.owner.endsWith("/Rs2Keyboard") && call.name.equals("keyPress")
                        && argument instanceof IntInsnNode
                        && ((IntInsnNode) argument).operand == java.awt.event.KeyEvent.VK_SPACE)
                {
                    spaces++;
                    assertEquals("Space must stay inside the guarded callback", callback, method.name);
                }
            }
        }
        assertEquals("A ready continuation must press Space once", 1, spaces);
    }
}
