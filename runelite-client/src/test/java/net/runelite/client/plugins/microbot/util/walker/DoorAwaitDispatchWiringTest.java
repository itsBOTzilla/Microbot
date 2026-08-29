package net.runelite.client.plugins.microbot.util.walker;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DoorAwaitDispatchWiringTest
{
    private static final String RS2_WALKER =
            "net/runelite/client/plugins/microbot/util/walker/Rs2Walker";
    private static final String RS2_GAME_OBJECT =
            "net/runelite/client/plugins/microbot/util/gameobject/Rs2GameObject";
    private static final String RS2_PLAYER =
            "net/runelite/client/plugins/microbot/util/player/Rs2Player";
    private static final String WORLD_POINT = "Lnet/runelite/api/coords/WorldPoint;";
    private static final String AWAIT_DESCRIPTOR =
            "(" + WORLD_POINT + WORLD_POINT + WORLD_POINT + ")V";

    @Test
    public void everyDoorAwaitReceivesThePositionCapturedBeforeInteraction() throws Exception
    {
        ClassNode classNode = new ClassNode();
        try (InputStream input = Rs2Walker.class.getResourceAsStream("/" + RS2_WALKER + ".class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(classNode, ClassReader.SKIP_FRAMES);
        }

        int awaitCalls = 0;
        for (MethodNode method : classNode.methods)
        {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext())
            {
                if (!(instruction instanceof MethodInsnNode))
                {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!RS2_WALKER.equals(call.owner)
                        || !"waitForDoorInteractionProgress".equals(call.name)
                        || !AWAIT_DESCRIPTOR.equals(call.desc))
                {
                    continue;
                }

                awaitCalls++;
                AbstractInsnNode toLoad = previousInstruction(call);
                AbstractInsnNode fromLoad = previousInstruction(toLoad);
                AbstractInsnNode beforeLoad = previousInstruction(fromLoad);
                assertTrue(beforeLoad instanceof VarInsnNode
                        && beforeLoad.getOpcode() == Opcodes.ALOAD);
                assertTrue(fromLoad instanceof VarInsnNode
                        && fromLoad.getOpcode() == Opcodes.ALOAD);
                assertTrue(toLoad instanceof VarInsnNode
                        && toLoad.getOpcode() == Opcodes.ALOAD);
                int beforeLocal = ((VarInsnNode) beforeLoad).var;

                MethodInsnNode interaction = previousCall(beforeLoad, RS2_GAME_OBJECT, "interact");
                assertNotNull("door await must follow an Rs2GameObject interaction", interaction);
                MethodInsnNode positionRead = previousCall(interaction, RS2_PLAYER, "getWorldLocation");
                assertNotNull("door interaction must have a pre-dispatch position read", positionRead);
                AbstractInsnNode positionStore = nextInstruction(positionRead);
                assertTrue(positionStore instanceof VarInsnNode
                        && positionStore.getOpcode() == Opcodes.ASTORE);
                assertEquals("door await must reuse the pre-dispatch position local",
                        beforeLocal, ((VarInsnNode) positionStore).var);
            }
        }

        assertEquals("all three shared door-await dispatch paths must be covered", 3, awaitCalls);
    }

    private static MethodInsnNode previousCall(AbstractInsnNode start, String owner, String name)
    {
        for (AbstractInsnNode instruction = previousInstruction(start);
             instruction != null; instruction = previousInstruction(instruction))
        {
            if (instruction instanceof MethodInsnNode)
            {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name))
                {
                    return call;
                }
            }
        }
        return null;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction)
    {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0)
        {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction)
    {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0)
        {
            next = next.getNext();
        }
        return next;
    }
}
