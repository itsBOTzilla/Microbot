package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;

public class QuestScriptLifecycleTest
{
    @Test
    public void pluginShutdownStopsQuestAutomationLoop() throws IOException
    {
        String resource = "/" + Type.getInternalName(QuestHelperPlugin.class) + ".class";
        int[] shutdownCalls = {0};
        try (InputStream input = QuestHelperPlugin.class.getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IOException("Unable to load " + resource);
            }

            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    boolean pluginShutdown = name.equals("shutDown") && descriptor.equals("()V");
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (pluginShutdown
                                    && owner.equals(Type.getInternalName(QuestScript.class))
                                    && methodName.equals("shutdown")
                                    && methodDescriptor.equals("()V"))
                            {
                                shutdownCalls[0]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        assertEquals("Disabling Quest Helper must cancel its automation schedule", 1, shutdownCalls[0]);
    }
}
