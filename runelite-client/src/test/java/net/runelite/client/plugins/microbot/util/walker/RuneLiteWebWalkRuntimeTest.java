package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RuneLiteWebWalkRuntimeTest
{
    @Test
    public void selectsFurthestReachableForwardTileInsideMinimapRange()
    {
        List<WorldPoint> path = line(0, 16);
        Set<WorldPoint> reachable = new HashSet<>(line(0, 12));

        RuneLiteWebWalkRuntime.ForwardCandidate candidate =
                RuneLiteWebWalkRuntime.selectForwardCandidate(
                        path, point(0), reachable, 12, index -> false);

        assertEquals(point(11), candidate.getTarget());
        assertEquals(11, candidate.getPathIndex());
    }

    @Test
    public void neverSelectsPastExactTransportEdge()
    {
        List<WorldPoint> path = line(0, 16);

        RuneLiteWebWalkRuntime.ForwardCandidate candidate =
                RuneLiteWebWalkRuntime.selectForwardCandidate(
                        path, point(0), new HashSet<>(path), 12, index -> index == 6);

        assertEquals(point(6), candidate.getTarget());
        assertEquals(6, candidate.getPathIndex());
    }

    @Test
    public void blockedForwardPathDoesNotRedispatchCurrentTile()
    {
        List<WorldPoint> path = line(0, 5);
        Set<WorldPoint> reachable = Set.of(point(0));

        RuneLiteWebWalkRuntime.ForwardCandidate candidate =
                RuneLiteWebWalkRuntime.selectForwardCandidate(
                        path, point(0), reachable, 12, index -> false);

        assertNull(candidate);
    }

    @Test
    public void rejectedDispatchCarriesNoAcceptedTarget()
    {
        WebWalkRuntime.DispatchResult result = WebWalkRuntime.DispatchResult.rejected();

        assertFalse(result.isAccepted());
        assertNull(result.getActualTarget());
        assertEquals(WebWalkRuntime.DispatchMethod.NONE, result.getMethod());
    }

    @Test
    public void closeRouteTargetUsesOneCanvasCommand()
    {
        AtomicInteger canvasCalls = new AtomicInteger();
        AtomicInteger minimapCalls = new AtomicInteger();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchMovementTarget(
                point(0), point(5),
                () -> { },
                () -> {
                    canvasCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    minimapCalls.incrementAndGet();
                    return WebWalkRuntime.DispatchResult.accepted(point(5));
                });

        assertTrue(result.isAccepted());
        assertEquals(point(5), result.getActualTarget());
        assertEquals(WebWalkRuntime.DispatchMethod.CANVAS, result.getMethod());
        assertEquals(1, canvasCalls.get());
        assertEquals(0, minimapCalls.get());
    }

    @Test
    public void farRouteTargetUsesOneMinimapCommand()
    {
        AtomicInteger canvasCalls = new AtomicInteger();
        AtomicInteger minimapCalls = new AtomicInteger();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchMovementTarget(
                point(0), point(6),
                () -> { },
                () -> {
                    canvasCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    minimapCalls.incrementAndGet();
                    return WebWalkRuntime.DispatchResult.accepted(point(6));
                });

        assertTrue(result.isAccepted());
        assertEquals(WebWalkRuntime.DispatchMethod.MINIMAP, result.getMethod());
        assertEquals(0, canvasCalls.get());
        assertEquals(1, minimapCalls.get());
    }

    @Test
    public void unavailableCloseCanvasFallsBackToOneMinimapCommand()
    {
        AtomicInteger canvasCalls = new AtomicInteger();
        AtomicInteger minimapCalls = new AtomicInteger();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchMovementTarget(
                point(0), point(5),
                () -> { },
                () -> {
                    canvasCalls.incrementAndGet();
                    return false;
                },
                () -> {
                    minimapCalls.incrementAndGet();
                    return WebWalkRuntime.DispatchResult.accepted(point(5));
                });

        assertTrue(result.isAccepted());
        assertEquals(WebWalkRuntime.DispatchMethod.MINIMAP, result.getMethod());
        assertEquals(1, canvasCalls.get());
        assertEquals(1, minimapCalls.get());
    }

    @Test
    public void crossPlaneTargetRejectsWithoutAnyInput()
    {
        AtomicInteger canvasCalls = new AtomicInteger();
        AtomicInteger minimapCalls = new AtomicInteger();
        AtomicInteger runPolicyCalls = new AtomicInteger();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchMovementTarget(
                point(0), new WorldPoint(3201, 3200, 1),
                runPolicyCalls::incrementAndGet,
                () -> {
                    canvasCalls.incrementAndGet();
                    return true;
                },
                () -> {
                    minimapCalls.incrementAndGet();
                    return WebWalkRuntime.DispatchResult.accepted(point(1));
                });

        assertFalse(result.isAccepted());
        assertEquals(WebWalkRuntime.DispatchMethod.NONE, result.getMethod());
        assertEquals(0, runPolicyCalls.get());
        assertEquals(0, canvasCalls.get());
        assertEquals(0, minimapCalls.get());
    }

    @Test
    public void canvasProjectionUsesClientThreadSnapshotBeforeInput() throws IOException
    {
        AtomicInteger clientThreadCalls = new AtomicInteger();
        AtomicInteger directProjectionCalls = new AtomicInteger();
        AtomicInteger clientThreadProjectionCalls = new AtomicInteger();
        AtomicInteger boundsHelperCalls = new AtomicInteger();
        String clientThreadOwner = "net/runelite/client/callback/ClientThread";
        String perspectiveOwner = "net/runelite/api/Perspective";
        String walkerOwner = Type.getInternalName(Rs2Walker.class);
        String wrapperDescriptor = "(Lnet/runelite/api/coords/WorldPoint;Ljava/lang/Runnable;)Z";
        String boundsDescriptor = "(Lnet/runelite/api/coords/WorldPoint;)Ljava/awt/Rectangle;";

        try (InputStream stream = Rs2Walker.class.getResourceAsStream("Rs2Walker.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    boolean wrapper = name.equals("walkFastCanvasOnScreenOnly")
                            && descriptor.equals(wrapperDescriptor);
                    boolean boundsHelper = name.equals("canvasWalkDispatchBounds")
                            && descriptor.equals(boundsDescriptor);
                    boolean boundsLambda = name.startsWith("lambda$canvasWalkDispatchBounds$");
                    if (!wrapper && !boundsHelper && !boundsLambda)
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (boundsHelper && owner.equals(clientThreadOwner)
                                    && methodName.equals("runOnClientThreadOptional"))
                            {
                                clientThreadCalls.incrementAndGet();
                            }
                            if (wrapper && owner.equals(walkerOwner)
                                    && methodName.equals("canvasWalkDispatchBounds")
                                    && methodDescriptor.equals(boundsDescriptor))
                            {
                                boundsHelperCalls.incrementAndGet();
                            }
                            if (wrapper && owner.equals(perspectiveOwner)
                                    && methodName.equals("localToCanvas"))
                            {
                                directProjectionCalls.incrementAndGet();
                            }
                            if (boundsLambda && owner.equals(perspectiveOwner)
                                    && methodName.equals("localToCanvas"))
                            {
                                clientThreadProjectionCalls.incrementAndGet();
                            }
                        }
                    };
                }
            }, 0);
        }

        assertEquals("canvas state must be captured through the RuneLite client thread", 1,
                clientThreadCalls.get());
        assertEquals("canvas dispatch must call the client-thread bounds helper", 1,
                boundsHelperCalls.get());
        assertEquals("the walker thread must not project live canvas state directly", 0,
                directProjectionCalls.get());
        assertEquals("canvas projection must remain inside the client-thread lambda", 1,
                clientThreadProjectionCalls.get());
    }

    @Test
    public void productionDispatchWiresCanvasBeforeMinimapPolicy() throws IOException
    {
        AtomicInteger policyCalls = new AtomicInteger();
        AtomicInteger canvasCalls = new AtomicInteger();
        AtomicInteger minimapCalls = new AtomicInteger();
        String runtimeOwner = Type.getInternalName(RuneLiteWebWalkRuntime.class);
        String walkerOwner = Type.getInternalName(Rs2Walker.class);

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
                    boolean dispatchMethod = name.equals("dispatchMinimap");
                    boolean dispatchLambda = name.startsWith("lambda$dispatchMinimap$");
                    if (!dispatchMethod && !dispatchLambda)
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (dispatchMethod && owner.equals(runtimeOwner)
                                    && methodName.equals("dispatchMovementTarget"))
                            {
                                policyCalls.incrementAndGet();
                            }
                            if (dispatchLambda && owner.equals(walkerOwner)
                                    && methodName.equals("walkFastCanvasOnScreenOnly")
                                    && methodDescriptor.equals("(Lnet/runelite/api/coords/WorldPoint;)Z"))
                            {
                                canvasCalls.incrementAndGet();
                            }
                            if (dispatchLambda && owner.equals(runtimeOwner)
                                    && methodName.equals("dispatchRouteMinimap"))
                            {
                                minimapCalls.incrementAndGet();
                            }
                        }
                    };
                }
            }, 0);
        }

        assertEquals(1, policyCalls.get());
        assertEquals(1, canvasCalls.get());
        assertEquals(1, minimapCalls.get());
    }

    @Test
    public void productionSelectionKeepsTargetsInsideReliableMinimapRadius() throws IOException
    {
        AtomicInteger selectorCalls = new AtomicInteger();
        List<Integer> selectorRadii = new ArrayList<>();
        String runtimeOwner = Type.getInternalName(RuneLiteWebWalkRuntime.class);

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
                        private Integer lastIntegerConstant;

                        @Override
                        public void visitIntInsn(int opcode, int operand)
                        {
                            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)
                            {
                                lastIntegerConstant = operand;
                            }
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                            {
                                lastIntegerConstant = opcode - Opcodes.ICONST_0;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (owner.equals(runtimeOwner) && methodName.equals("selectForwardCandidate"))
                            {
                                selectorCalls.incrementAndGet();
                                selectorRadii.add(lastIntegerConstant);
                            }
                        }
                    };
                }
            }, 0);
        }

        assertEquals(1, selectorCalls.get());
        assertEquals(List.of(10), selectorRadii);
    }

    @Test
    public void rejectedMinimapTargetFallsBackWithoutReturningToEarlierFold()
    {
        List<WorldPoint> foldedPath = List.of(
                point(1), point(2), point(3), point(4),
                point(0), new WorldPoint(3200, 3201, 0),
                new WorldPoint(3200, 3202, 0), new WorldPoint(3200, 3203, 0),
                new WorldPoint(3200, 3204, 0));
        List<WorldPoint> attempts = new ArrayList<>();
        Set<WorldPoint> reachable = new HashSet<>(foldedPath.subList(0, 4));
        reachable.add(foldedPath.get(8));

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchRouteMinimap(
                foldedPath, foldedPath.get(8), point(0), reachable,
                4, 8, 10, index -> false, candidate -> {
                    attempts.add(candidate);
                    return false;
                });

        assertFalse(result.isAccepted());
        assertEquals(List.of(foldedPath.get(8)), attempts);
    }

    @Test
    public void rejectedMinimapTargetFallbackStopsAtTransportOrigin()
    {
        List<WorldPoint> path = line(0, 10);
        List<WorldPoint> attempts = new ArrayList<>();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchRouteMinimap(
                path, point(8), point(2), new HashSet<>(path),
                2, 8, 10, index -> index == 4, candidate -> {
                    attempts.add(candidate);
                    return !candidate.equals(point(8));
                });

        assertTrue(result.isAccepted());
        assertEquals(point(4), result.getActualTarget());
        assertEquals(List.of(point(8), point(4)), attempts);
    }

    @Test
    public void currentIndexTargetCanDispatchWithoutBackwardFallback()
    {
        List<WorldPoint> path = line(0, 5);
        List<WorldPoint> attempts = new ArrayList<>();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchRouteMinimap(
                path, point(2), point(1), new HashSet<>(path),
                2, 2, 10, index -> false, candidate -> {
                    attempts.add(candidate);
                    return true;
                });

        assertTrue(result.isAccepted());
        assertEquals(point(2), result.getActualTarget());
        assertEquals(List.of(point(2)), attempts);
    }

    @Test
    public void dispatchRechecksReliableRadiusAgainstFreshPlayerPosition()
    {
        List<WorldPoint> path = line(0, 12);
        List<WorldPoint> attempts = new ArrayList<>();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchRouteMinimap(
                path, point(11), point(0), new HashSet<>(path),
                0, 11, 10, index -> false, candidate -> {
                    attempts.add(candidate);
                    return true;
                });

        assertTrue(result.isAccepted());
        assertEquals(point(10), result.getActualTarget());
        assertEquals(List.of(point(10)), attempts);
    }

    @Test
    public void freshForwardAnchorPreventsFallbackBehindPlayerProgress()
    {
        List<WorldPoint> path = line(0, 9);
        Set<WorldPoint> reachable = Set.of(point(3), point(5), point(8));
        List<WorldPoint> attempts = new ArrayList<>();

        WebWalkRuntime.DispatchResult result = RuneLiteWebWalkRuntime.dispatchRouteMinimap(
                path, point(8), point(5), reachable,
                1, 8, 10, index -> false, candidate -> {
                    attempts.add(candidate);
                    return candidate.equals(point(3));
                });

        assertFalse(result.isAccepted());
        assertEquals(List.of(point(8)), attempts);
    }

    private static List<WorldPoint> line(int fromInclusive, int toExclusive)
    {
        List<WorldPoint> points = new ArrayList<>();
        for (int x = fromInclusive; x < toExclusive; x++)
        {
            points.add(point(x));
        }
        return points;
    }

    private static WorldPoint point(int x)
    {
        return new WorldPoint(3200 + x, 3200, 0);
    }
}
