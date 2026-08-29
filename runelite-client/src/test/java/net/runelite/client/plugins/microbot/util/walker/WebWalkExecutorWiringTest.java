package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebWalkExecutorWiringTest
{
    @Test
    public void blockingWalkerUsesSingleActionExecutorInsteadOfLegacyProcessLoop() throws IOException
    {
        AtomicBoolean createsSession = new AtomicBoolean();
        AtomicBoolean createsRuntime = new AtomicBoolean();
        AtomicBoolean invokesExecutor = new AtomicBoolean();
        AtomicBoolean invokesLegacyLoop = new AtomicBoolean();
        AtomicBoolean pollsPathfinderBeforeExecutor = new AtomicBoolean();
        AtomicBoolean readsDebugSafeguard = new AtomicBoolean();
        String walkerOwner = Type.getInternalName(Rs2Walker.class);

        try (InputStream stream = Rs2Walker.class.getResourceAsStream("Rs2Walker.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    if (!name.equals("walkWithStateInternal")
                            || !descriptor.equals(Type.getMethodDescriptor(
                            Type.getType(WalkerState.class), Type.getType(WorldPoint.class), Type.INT_TYPE)))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName,
                                                   String fieldDescriptor)
                        {
                            if (opcode == Opcodes.GETSTATIC && owner.equals(walkerOwner)
                                    && fieldName.equals("debug"))
                            {
                                readsDebugSafeguard.set(true);
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type)
                        {
                            if (opcode == Opcodes.NEW && type.equals(Type.getInternalName(WebWalkSession.class)))
                            {
                                createsSession.set(true);
                            }
                            if (opcode == Opcodes.NEW
                                    && type.equals(Type.getInternalName(RuneLiteWebWalkRuntime.class)))
                            {
                                createsRuntime.set(true);
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (owner.equals(Type.getInternalName(WebWalkExecutor.class))
                                    && methodName.equals("walk"))
                            {
                                invokesExecutor.set(true);
                            }
                            if (owner.equals(walkerOwner) && methodName.equals("processWalk"))
                            {
                                invokesLegacyLoop.set(true);
                            }
                            if (owner.endsWith("/Pathfinder") && methodName.equals("isDone"))
                            {
                                pollsPathfinderBeforeExecutor.set(true);
                            }
                        }
                    };
                }
            }, 0);
        }

        assertTrue(createsSession.get());
        assertTrue(createsRuntime.get());
        assertTrue(invokesExecutor.get());
        assertFalse(invokesLegacyLoop.get());
        assertFalse(pollsPathfinderBeforeExecutor.get());
        assertTrue(readsDebugSafeguard.get());
    }

    @Test
    public void runtimeObserveAlwaysPropagatesRunStateToDirectObservations() throws IOException
    {
        AtomicInteger legacyConstructors = new AtomicInteger();
        AtomicInteger runAwareConstructors = new AtomicInteger();
        String observationOwner = Type.getInternalName(WebWalkRuntime.Observation.class);
        String legacyDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                Type.INT_TYPE, Type.getType(WorldPoint.class),
                Type.getType(WebWalkRuntime.Status.class),
                Type.getType(WebWalkRuntime.RouteSnapshot.class), Type.INT_TYPE,
                Type.getType(WorldPoint.class), Type.INT_TYPE, Type.BOOLEAN_TYPE);
        String runAwareDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE,
                Type.INT_TYPE, Type.getType(WorldPoint.class),
                Type.getType(WebWalkRuntime.Status.class),
                Type.getType(WebWalkRuntime.RouteSnapshot.class), Type.INT_TYPE,
                Type.getType(WorldPoint.class), Type.INT_TYPE, Type.BOOLEAN_TYPE,
                Type.BOOLEAN_TYPE);

        try (InputStream stream = RuneLiteWebWalkRuntime.class.getResourceAsStream(
                "RuneLiteWebWalkRuntime.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    if (!name.equals("observe"))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (opcode != Opcodes.INVOKESPECIAL || !owner.equals(observationOwner)
                                    || !methodName.equals("<init>"))
                            {
                                return;
                            }
                            if (methodDescriptor.equals(legacyDescriptor))
                            {
                                legacyConstructors.incrementAndGet();
                            }
                            if (methodDescriptor.equals(runAwareDescriptor))
                            {
                                runAwareConstructors.incrementAndGet();
                            }
                        }
                    };
                }
            }, 0);
        }

        assertEquals(0, legacyConstructors.get());
        assertEquals(4, runAwareConstructors.get());
    }
}
