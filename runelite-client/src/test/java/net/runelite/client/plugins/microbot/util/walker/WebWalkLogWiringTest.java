package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebWalkLogWiringTest
{
    @Test
    public void routeComparisonDetailRequiresTraceLogging() throws IOException
    {
        AtomicBoolean invokesTrace = new AtomicBoolean();
        AtomicBoolean invokesDebug = new AtomicBoolean();
        String loggerOwner = Type.getInternalName(org.slf4j.Logger.class);

        try (InputStream stream = WebWalkLog.class.getResourceAsStream("WebWalkLog.class"))
        {
            assertTrue(stream != null);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    if (!name.equals("compareDetail"))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (!owner.equals(loggerOwner))
                            {
                                return;
                            }
                            if (methodName.equals("trace"))
                            {
                                invokesTrace.set(true);
                            }
                            if (methodName.equals("debug"))
                            {
                                invokesDebug.set(true);
                            }
                        }
                    };
                }
            }, 0);
        }

        assertTrue(invokesTrace.get());
        assertFalse(invokesDebug.get());
    }
}
