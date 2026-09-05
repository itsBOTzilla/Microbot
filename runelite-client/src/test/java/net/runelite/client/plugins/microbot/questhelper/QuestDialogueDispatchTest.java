package net.runelite.client.plugins.microbot.questhelper;

import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class QuestDialogueDispatchTest
{
    @Test
    public void questLoopDoesNotSendUnconditionalSpaceKeys() throws Exception
    {
        int[] spaceDispatches = {0};
        try (InputStream input = QuestScript.class.getResourceAsStream("QuestScript.class"))
        {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions)
                {
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        private boolean space;

                        @Override
                        public void visitIntInsn(int opcode, int operand)
                        {
                            space = operand == java.awt.event.KeyEvent.VK_SPACE;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                    String descriptor, boolean isInterface)
                        {
                            if (space && owner.endsWith("/Rs2Keyboard") && method.equals("keyPress"))
                            {
                                spaceDispatches[0]++;
                            }
                            space = false;
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        assertEquals("Dialogue continuation must use a ready prompt, not raw Space", 0, spaceDispatches[0]);
    }
}
