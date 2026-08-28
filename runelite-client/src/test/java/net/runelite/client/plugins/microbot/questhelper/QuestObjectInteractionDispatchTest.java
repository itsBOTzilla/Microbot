package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.steps.ObjectStep;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuestObjectInteractionDispatchTest
{
    @Test
    public void objectStepsUseTheRawGameObjectInteractionUtility() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertEquals("QuestScript must contain applyObjectStep", 1, calls.matchedMethods);
        assertTrue("Object steps must use the same raw-object interaction utility as walker transports",
                calls.rawGameObjectInteractions > 0);
        assertEquals("Object steps must not use the duplicated tile-object wrapper click path",
                0, calls.wrapperClicks);
    }

    @Test
    public void itemObjectStepsWaitForSelectionBeforeObjectDispatch() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertTrue("Object-step item selection must be dispatched", calls.inventoryUseOrder > 0);
        assertTrue("QuestScript must wait for the selected inventory item",
                calls.selectionWaitOrder > calls.inventoryUseOrder);
        assertTrue("The object target must be dispatched only after item selection is confirmed",
                calls.itemObjectInteractionOrder > calls.selectionWaitOrder);
        assertTrue("Selected items must be applied to objects with the Use action",
                calls.itemObjectInteractionUsesUseAction);
    }

    @Test
    public void loadedObjectApproachMustReachItsSelectedInteractionTileExactly() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertTrue("A wall must not satisfy the arrival distance for a selected interaction tile",
                calls.walkReachedDistances.contains(0));
    }

    @Test
    public void adjacentObjectBehindBarrierStillRequiresAnApproachWalk()
    {
        boolean objectAvailable = true;
        boolean moreThanOneTileFromStep = false;
        boolean objectInLineOfSight = false;

        assertTrue("Coordinate distance must not bypass the reachable interaction side",
                QuestScript.shouldWalkToObjectApproach(objectAvailable, moreThanOneTileFromStep,
                        objectInLineOfSight));
    }

    @Test
    public void objectApproachMustNotRunTheGlobalReachabilityPathfinder() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertEquals("Object approach must not block the quest thread in Rs2Walker.canReach",
                0, calls.globalReachabilityChecks);
    }

    @Test
    public void objectVisibilityUsesTheFullTileObjectFootprint() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertTrue("Multi-tile object visibility must not be reduced to the origin tile",
                calls.fullObjectLineOfSightChecks > 0);
    }

    @Test
    public void objectInteractionStartWaitIsBoundedToTwoGameTicks() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertEquals("Object steps must not inherit the unrelated five-second default wait",
                Integer.valueOf(1_200), calls.objectInteractionStartTimeout);
    }

    @Test
    public void itemDeselectionAloneDoesNotConfirmObjectInteraction()
    {
        assertFalse(QuestScript.isObjectInteractionConfirmed(
                false, false, false, false, false));
        assertTrue(QuestScript.isObjectInteractionConfirmed(
                true, false, false, false, false));
        assertTrue(QuestScript.isObjectInteractionConfirmed(
                false, true, false, false, false));
        assertTrue(QuestScript.isObjectInteractionConfirmed(
                false, false, true, false, false));
        assertTrue(QuestScript.isObjectInteractionConfirmed(
                false, false, false, true, false));
        assertTrue(QuestScript.isObjectInteractionConfirmed(
                false, false, false, false, true));
    }

    @Test
    public void objectDispatchWaitsForPreexistingMovementToSettle() throws IOException
    {
        DispatchCalls calls = readApplyObjectStepCalls();

        assertTrue(calls.preDispatchIdleWaitOrder > 0);
        assertTrue(calls.preDispatchIdleWaitOrder < calls.rawGameObjectInteractionOrder);
        assertFalse(QuestScript.isObjectInteractionIdle(true, false));
        assertFalse(QuestScript.isObjectInteractionIdle(false, true));
        assertTrue(QuestScript.isObjectInteractionIdle(false, false));
    }

    private static DispatchCalls readApplyObjectStepCalls() throws IOException
    {
        String resource = "/" + Type.getInternalName(QuestScript.class) + ".class";
        DispatchCalls calls = new DispatchCalls();
        try (InputStream input = QuestScript.class.getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IOException("Unable to load " + resource);
            }

            String expectedDescriptor = Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(ObjectStep.class));
            String rawInteractionDescriptor = Type.getMethodDescriptor(
                    Type.BOOLEAN_TYPE, Type.getType(TileObject.class), Type.getType(String.class));

            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    boolean expectedMethod = name.equals("applyObjectStep")
                            && descriptor.equals(expectedDescriptor);
                    if (expectedMethod)
                    {
                        calls.matchedMethods++;
                    }

                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        private int callOrder;
                        private String lastStringConstant;
                        private Integer lastIntConstant;

                        @Override
                        public void visitLdcInsn(Object value)
                        {
                            if (expectedMethod && value instanceof String)
                            {
                                lastStringConstant = (String) value;
                            }
                            if (expectedMethod && value instanceof Integer)
                            {
                                lastIntConstant = (Integer) value;
                            }
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (expectedMethod && opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                            {
                                lastIntConstant = opcode - Opcodes.ICONST_0;
                            }
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand)
                        {
                            if (expectedMethod && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH))
                            {
                                lastIntConstant = operand;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (!expectedMethod)
                            {
                                return;
                            }

                            callOrder++;

                            if (owner.equals(Type.getInternalName(Rs2Inventory.class))
                                    && methodName.equals("use")
                                    && methodDescriptor.equals(Type.getMethodDescriptor(
                                    Type.BOOLEAN_TYPE, Type.INT_TYPE)))
                            {
                                calls.inventoryUseOrder = callOrder;
                            }
                            if (owner.equals(Type.getInternalName(QuestScript.class))
                                    && methodName.equals("waitForSelectedInventoryItem"))
                            {
                                calls.selectionWaitOrder = callOrder;
                            }
                            if (owner.equals(Type.getInternalName(QuestScript.class))
                                    && methodName.equals("waitForObjectInteractionIdle"))
                            {
                                calls.preDispatchIdleWaitOrder = callOrder;
                            }

                            if (owner.equals(Type.getInternalName(Rs2GameObject.class))
                                    && methodName.equals("interact")
                                    && methodDescriptor.equals(rawInteractionDescriptor))
                            {
                                calls.rawGameObjectInteractions++;
                                if (calls.rawGameObjectInteractionOrder == 0)
                                {
                                    calls.rawGameObjectInteractionOrder = callOrder;
                                }
                                if (calls.selectionWaitOrder > 0
                                        && calls.itemObjectInteractionOrder == 0)
                                {
                                    calls.itemObjectInteractionOrder = callOrder;
                                    calls.itemObjectInteractionUsesUseAction = "Use".equals(lastStringConstant);
                                }
                            }
                            if (owner.equals(Type.getInternalName(Rs2TileObjectModel.class))
                                    && methodName.equals("click"))
                            {
                                calls.wrapperClicks++;
                            }
                            if (owner.equals(Type.getInternalName(net.runelite.client.plugins.microbot.util.walker.Rs2Walker.class))
                                    && methodName.equals("walkTo")
                                    && methodDescriptor.equals(Type.getMethodDescriptor(
                                    Type.BOOLEAN_TYPE, Type.getType(WorldPoint.class), Type.INT_TYPE)))
                            {
                                calls.walkReachedDistances.add(lastIntConstant);
                            }
                            if (owner.equals(Type.getInternalName(net.runelite.client.plugins.microbot.util.walker.Rs2Walker.class))
                                    && methodName.equals("canReach"))
                            {
                                    calls.globalReachabilityChecks++;
                            }
                            if (owner.equals(Type.getInternalName(Rs2GameObject.class))
                                    && methodName.equals("hasLineOfSight")
                                    && methodDescriptor.equals(Type.getMethodDescriptor(
                                    Type.BOOLEAN_TYPE,
                                    Type.getType(WorldPoint.class),
                                    Type.getType(TileObject.class))))
                            {
                                calls.fullObjectLineOfSightChecks++;
                            }
                            if (owner.equals(Type.getInternalName(QuestScript.class))
                                    && methodName.equals("sleepUntil")
                                    && methodDescriptor.equals(Type.getMethodDescriptor(
                                    Type.BOOLEAN_TYPE,
                                    Type.getType(java.util.function.BooleanSupplier.class),
                                    Type.INT_TYPE))
                                    && calls.rawGameObjectInteractions > 0
                                    && calls.objectInteractionStartTimeout == null)
                            {
                                calls.objectInteractionStartTimeout = lastIntConstant;
                            }

                            lastIntConstant = null;
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private static final class DispatchCalls
    {
        private int matchedMethods;
        private int rawGameObjectInteractions;
        private int wrapperClicks;
        private int inventoryUseOrder;
        private int selectionWaitOrder;
        private int itemObjectInteractionOrder;
        private int preDispatchIdleWaitOrder;
        private int rawGameObjectInteractionOrder;
        private int globalReachabilityChecks;
        private int fullObjectLineOfSightChecks;
        private boolean itemObjectInteractionUsesUseAction;
        private final List<Integer> walkReachedDistances = new ArrayList<>();
        private Integer objectInteractionStartTimeout;
    }
}
