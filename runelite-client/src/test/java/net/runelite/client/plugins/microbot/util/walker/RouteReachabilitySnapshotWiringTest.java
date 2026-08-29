package net.runelite.client.plugins.microbot.util.walker;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RouteReachabilitySnapshotWiringTest
{
    private static final String RS2_WALKER =
            "net/runelite/client/plugins/microbot/util/walker/Rs2Walker";
    private static final String RS2_PLAYER =
            "net/runelite/client/plugins/microbot/util/player/Rs2Player";
    private static final String GLOBAL =
            "net/runelite/client/plugins/microbot/util/Global";
    private static final String WALKER_ROUTE_STATE =
            "net/runelite/client/plugins/microbot/util/walker/state/WalkerRouteState";
    private static final String SELECTOR_DESCRIPTOR =
            "(Ljava/util/List;Lnet/runelite/api/coords/WorldPoint;IILjava/util/Map;)"
                    + "Lnet/runelite/api/coords/WorldPoint;";

    @Test
    public void routeSelectionUsesTheSnapshotCapturedAfterInterimHandling() throws Exception
    {
        ClassNode classNode = new ClassNode();
        try (InputStream input = Rs2Walker.class.getResourceAsStream("/" + RS2_WALKER + ".class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(classNode, ClassReader.SKIP_FRAMES);
        }

        int selectorCalls = 0;
        for (MethodNode processWalk : classNode.methods)
        {
            if (!"processWalk".equals(processWalk.name))
            {
                continue;
            }
            for (AbstractInsnNode instruction = processWalk.instructions.getFirst();
                 instruction != null; instruction = instruction.getNext())
            {
                if (!(instruction instanceof MethodInsnNode))
                {
                    continue;
                }
                MethodInsnNode selector = (MethodInsnNode) instruction;
                if (!RS2_WALKER.equals(selector.owner)
                        || !"selectRouteClickTarget".equals(selector.name)
                        || !SELECTOR_DESCRIPTOR.equals(selector.desc))
                {
                    continue;
                }

                selectorCalls++;
                MethodInsnNode capture = previousCall(selector, RS2_WALKER,
                        "getClosestIndexReachableTiles");
                assertNotNull("route selection must have a reachability capture", capture);
                MethodInsnNode interimDoorScan = previousCall(capture, RS2_WALKER,
                        "handlePendingDoorDuringInterim");
                assertNotNull("reachability must be captured after interim door handling", interimDoorScan);
                assertTrue("the interim movement wait must precede the reachability capture",
                        containsCallBetween(interimDoorScan, capture, GLOBAL, "sleepUntil"));
                assertFalse("no wait may age the reachability snapshot before route selection",
                        containsCallBetween(capture, selector, GLOBAL, "sleepUntil"));

                MethodInsnNode interimClear = previousCall(capture, RS2_WALKER,
                        "clearInterimTarget");
                assertNotNull("expired or handed-off interim ownership must be cleared", interimClear);
                assertFalse("a cleared interim target must not be reread as a sticky route command",
                        containsFieldReadBetween(interimClear, selector, WALKER_ROUTE_STATE,
                                "interimTargetWp"));

                MethodInsnNode playerRead = previousCall(capture, RS2_PLAYER, "getWorldLocation");
                assertNotNull("reachability capture must follow a fresh player snapshot", playerRead);
                assertFalse("the player and collision snapshots must be captured together",
                        containsCallBetween(playerRead, capture, GLOBAL, "sleepUntil"));

                AbstractInsnNode mapStore = nextInstruction(capture);
                AbstractInsnNode mapLoad = previousInstruction(selector);
                assertTrue(mapStore instanceof VarInsnNode && mapStore.getOpcode() == Opcodes.ASTORE);
                assertTrue(mapLoad instanceof VarInsnNode && mapLoad.getOpcode() == Opcodes.ALOAD);
                assertEquals("selector must receive the map captured at the decision boundary",
                        ((VarInsnNode) mapStore).var, ((VarInsnNode) mapLoad).var);
            }
        }

        assertEquals("processWalk must have one normal route-selection site", 1, selectorCalls);
    }

    private static boolean containsFieldReadBetween(AbstractInsnNode start,
                                                    AbstractInsnNode end,
                                                    String owner,
                                                    String name)
    {
        for (AbstractInsnNode instruction = start.getNext();
             instruction != null && instruction != end; instruction = instruction.getNext())
        {
            if (instruction instanceof FieldInsnNode)
            {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.GETFIELD
                        && owner.equals(field.owner)
                        && name.equals(field.name))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsCallBetween(AbstractInsnNode start,
                                               AbstractInsnNode end,
                                               String owner,
                                               String name)
    {
        for (AbstractInsnNode instruction = start.getNext();
             instruction != null && instruction != end; instruction = instruction.getNext())
        {
            if (instruction instanceof MethodInsnNode)
            {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.equals(call.owner) && name.equals(call.name))
                {
                    return true;
                }
            }
        }
        return false;
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
