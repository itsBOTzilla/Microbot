package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.Assert.assertTrue;

public class TargetPreflightOrderingTest
{
    @Test
    public void rejectsInvalidTargetBeforeStartingItsPathfinder() throws IOException
    {
        ClassNode classNode = loadClassNode(Rs2Walker.class);
        MethodNode method = classNode.methods.stream()
                .filter(candidate -> candidate.name.equals("walkWithStateInternal"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("walkWithStateInternal is missing"));

        int preflightIndex = -1;
        int setTargetIndex = -1;
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions)
        {
            if (instruction instanceof MethodInsnNode)
            {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("net/runelite/client/plugins/microbot/util/walker/Rs2Walker"))
                {
                    if (preflightIndex < 0 && call.name.equals("hasWalkableTileWithin"))
                    {
                        preflightIndex = index;
                    }
                    if (setTargetIndex < 0 && call.name.equals("setTarget"))
                    {
                        setTargetIndex = index;
                    }
                }
            }
            index++;
        }

        assertTrue("target preflight call is missing", preflightIndex >= 0);
        assertTrue("pathfinder target call is missing", setTargetIndex >= 0);
        assertTrue("an invalid dungeon target must be rejected before its pathfinder starts",
                preflightIndex < setTargetIndex);
    }

    private static ClassNode loadClassNode(Class<?> type) throws IOException
    {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IOException("Missing class resource " + resource);
            }
            ClassNode classNode = new ClassNode();
            new ClassReader(input).accept(classNode, 0);
            return classNode;
        }
    }
}
