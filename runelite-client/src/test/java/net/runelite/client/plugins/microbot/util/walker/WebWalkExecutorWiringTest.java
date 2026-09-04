package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
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
    public void checkpointReleaseDiagnosticsCoverPreemptionAndProgress() throws IOException
    {
        AtomicInteger releaseLogs = new AtomicInteger();
        String logOwner = Type.getInternalName(WebWalkLog.class);

        try (InputStream stream = WebWalkExecutor.class.getResourceAsStream("WebWalkExecutor.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    if (!name.equals("decide"))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (owner.equals(logOwner) && methodName.equals("checkpointReleased"))
                            {
                                releaseLogs.incrementAndGet();
                            }
                        }
                    };
                }
            }, 0);
        }

        assertEquals("route-action preemption and reached/passed release must both log", 2,
                releaseLogs.get());
    }

    @Test
    public void legacyProcessWalkHasNoActiveCaller() throws IOException
    {
        AtomicInteger activeLegacyCalls = new AtomicInteger();
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
                    if (name.equals("processWalk"))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (owner.equals(walkerOwner) && methodName.equals("processWalk"))
                            {
                                activeLegacyCalls.incrementAndGet();
                            }
                        }
                    };
                }
            }, 0);
        }

        assertEquals("legacy processWalk must have no active facade callers", 0,
                activeLegacyCalls.get());
    }

    @Test
    public void publicBlockingFacadeConvergesOnNewExecutorOwner() throws IOException
    {
        AtomicBoolean invokesInternalOwner = new AtomicBoolean();
        AtomicBoolean invokesLegacyLoop = new AtomicBoolean();
        String walkerOwner = Type.getInternalName(Rs2Walker.class);
        String facadeDescriptor = Type.getMethodDescriptor(
                Type.getType(WalkerState.class), Type.getType(WorldPoint.class), Type.INT_TYPE);
        String internalDescriptor = facadeDescriptor;

        try (InputStream stream = Rs2Walker.class.getResourceAsStream("Rs2Walker.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    if (!name.equals("walkWithState") || !descriptor.equals(facadeDescriptor))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (owner.equals(walkerOwner)
                                    && methodName.equals("walkWithStateInternal")
                                    && methodDescriptor.equals(internalDescriptor))
                            {
                                invokesInternalOwner.set(true);
                            }
                            if (owner.equals(walkerOwner) && methodName.equals("processWalk"))
                            {
                                invokesLegacyLoop.set(true);
                            }
                        }
                    };
                }
            }, 0);
        }

        assertTrue(invokesInternalOwner.get());
        assertFalse(invokesLegacyLoop.get());
    }

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
    public void runtimeObserveAlwaysPropagatesRunAndMovementStateToDirectObservations() throws IOException
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
                Type.BOOLEAN_TYPE, Type.BOOLEAN_TYPE);

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
        assertEquals(5, runAwareConstructors.get());
    }

    @Test
    public void checkpointReleasePolicyDoesNotDependOnRunState() throws IOException
    {
        AtomicBoolean readsRunState = new AtomicBoolean();
        String observationOwner = Type.getInternalName(WebWalkRuntime.Observation.class);

        try (InputStream stream = WebWalkExecutor.class.getResourceAsStream("WebWalkExecutor.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    if (!name.equals("decide"))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (owner.equals(observationOwner) && methodName.equals("isRunEnabled"))
                            {
                                readsRunState.set(true);
                            }
                        }
                    };
                }
            }, 0);
        }

        Set<String> fieldNames = new HashSet<>();
        for (java.lang.reflect.Field field : WebWalkExecutor.class.getDeclaredFields())
        {
            fieldNames.add(field.getName());
        }
        assertFalse(readsRunState.get());
        assertFalse(fieldNames.contains("RUN_CHECKPOINT_HANDOFF_DISTANCE"));
        assertFalse(fieldNames.contains("WALK_CHECKPOINT_HANDOFF_DISTANCE"));
        assertTrue(fieldNames.contains("CHECKPOINT_HANDOFF_DISTANCE"));
    }
}
