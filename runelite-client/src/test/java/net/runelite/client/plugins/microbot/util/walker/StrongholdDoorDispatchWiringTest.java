package net.runelite.client.plugins.microbot.util.walker;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StrongholdDoorDispatchWiringTest
{
    private static final String RS2_WALKER =
            "net/runelite/client/plugins/microbot/util/walker/Rs2Walker";
    private static final String RS2_GAME_OBJECT =
            "net/runelite/client/plugins/microbot/util/gameobject/Rs2GameObject";

    @Test
    public void strongholdDialogueHandlerNeverDispatchesTheDoorInteraction() throws Exception
    {
        ClassNode classNode = readWalkerClass();
        MethodNode handler = findMethod(classNode, "handleStrongholdOfSecurityAnswer");

        int interactionCalls = countCalls(handler, RS2_GAME_OBJECT, "interact");

        assertEquals("the shared door handler must own the only object interaction",
                0, interactionCalls);
    }

    @Test
    public void strongholdDialogueRunsInsideTheSharedDoorLifecycle() throws Exception
    {
        ClassNode classNode = readWalkerClass();
        int strongholdCallers = 0;

        for (MethodNode method : classNode.methods)
        {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext())
            {
                if (!isCall(instruction, RS2_WALKER, "handleDoorPostInteraction"))
                {
                    continue;
                }

                strongholdCallers++;
                assertNotNull("door interaction must be dispatched before Stronghold dialogue handling",
                        previousCall(instruction, RS2_GAME_OBJECT, "interact"));
                assertNotNull("successful dispatch must claim door-approach ownership before dialogue handling",
                        previousCall(instruction, RS2_WALKER, "recordDoorApproachOwnership"));
                assertNotNull("Stronghold dialogue handling must rejoin common traversal verification",
                        nextCall(instruction, RS2_WALKER, "waitForDoorInteractionProgress"));
            }
        }

        assertEquals("both shared door detection paths must use the lifecycle", 2, strongholdCallers);
    }

    @Test
    public void strongholdDialogueCannotVetoSharedTraversalVerification() throws Exception
    {
        ClassNode classNode = readWalkerClass();
        int strongholdCallers = 0;

        for (MethodNode method : classNode.methods)
        {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext())
            {
                if (!isCall(instruction, RS2_WALKER, "handleDoorPostInteraction"))
                {
                    continue;
                }

                strongholdCallers++;
                MethodInsnNode call = (MethodInsnNode) instruction;
                assertEquals("Stronghold dialogue handling is advisory; traversal checks own success",
                        "()V", call.desc);
            }
        }

        assertEquals("both shared door detection paths must use the advisory hook",
                2, strongholdCallers);
    }

    private static ClassNode readWalkerClass() throws Exception
    {
        ClassNode classNode = new ClassNode();
        try (InputStream input = Rs2Walker.class.getResourceAsStream("/" + RS2_WALKER + ".class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(classNode, ClassReader.SKIP_FRAMES);
        }
        return classNode;
    }

    private static MethodNode findMethod(ClassNode classNode, String name)
    {
        for (MethodNode method : classNode.methods)
        {
            if (name.equals(method.name))
            {
                return method;
            }
        }
        throw new AssertionError("Missing method: " + name);
    }

    private static int countCalls(MethodNode method, String owner, String name)
    {
        int calls = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext())
        {
            if (isCall(instruction, owner, name))
            {
                calls++;
            }
        }
        return calls;
    }

    private static MethodInsnNode previousCall(AbstractInsnNode start, String owner, String name)
    {
        for (AbstractInsnNode instruction = start.getPrevious();
             instruction != null; instruction = instruction.getPrevious())
        {
            if (isCall(instruction, owner, name))
            {
                return (MethodInsnNode) instruction;
            }
        }
        return null;
    }

    private static MethodInsnNode nextCall(AbstractInsnNode start, String owner, String name)
    {
        for (AbstractInsnNode instruction = start.getNext();
             instruction != null; instruction = instruction.getNext())
        {
            if (isCall(instruction, owner, name))
            {
                return (MethodInsnNode) instruction;
            }
        }
        return null;
    }

    private static boolean isCall(AbstractInsnNode instruction, String owner, String name)
    {
        if (!(instruction instanceof MethodInsnNode))
        {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return owner.equals(call.owner) && name.equals(call.name);
    }
}
