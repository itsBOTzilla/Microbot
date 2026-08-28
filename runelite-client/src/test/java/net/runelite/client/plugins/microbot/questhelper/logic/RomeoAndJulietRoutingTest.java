package net.runelite.client.plugins.microbot.questhelper.logic;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.Assert.assertFalse;

public class RomeoAndJulietRoutingTest {
    @Test
    public void potionStepUsesTheStandardObjectStepInsteadOfCustomCanvasWalking() throws IOException {
        boolean[] invokesCustomPotionClimb = {false};

        try (InputStream classBytes = RomeoAndJuliet.class
                .getResourceAsStream("/" + RomeoAndJuliet.class.getName().replace('.', '/') + ".class")) {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!"executeCustomLogic".equals(name)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String invokedName,
                                                    String invokedDescriptor, boolean isInterface) {
                            if (owner.equals(RomeoAndJuliet.class.getName().replace('.', '/'))
                                    && "climbToJulietWithPotion".equals(invokedName)) {
                                invokesCustomPotionClimb[0] = true;
                            }
                        }
                    };
                }
            }, 0);
        }

        assertFalse("The potion step must fall through to QuestScript's door-aware ObjectStep",
                invokesCustomPotionClimb[0]);
    }
}
