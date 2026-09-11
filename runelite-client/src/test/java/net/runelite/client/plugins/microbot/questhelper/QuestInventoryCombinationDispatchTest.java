package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;

public class QuestInventoryCombinationDispatchTest
{
    @Test
    public void highlightedItemPairsUseCombineInsteadOfIndependentItemActions() throws IOException
    {
        DispatchCalls calls = readDispatchCalls();

        assertEquals("An item pair must dispatch through the inventory combine API", 1, calls.combineCalls);
        assertEquals("The item-pair dispatcher must never invoke an independent item action", 0, calls.interactCalls);
    }

    private static DispatchCalls readDispatchCalls() throws IOException
    {
        String resource = "/" + Type.getInternalName(QuestScript.class) + ".class";
        DispatchCalls calls = new DispatchCalls();
        try (InputStream input = QuestScript.class.getResourceAsStream(resource))
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
                    if (!name.equals("combineHighlightedInventoryItems"))
                    {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (!owner.equals(Type.getInternalName(Rs2Inventory.class)))
                            {
                                return;
                            }
                            if (methodName.equals("combine"))
                            {
                                calls.combineCalls++;
                            }
                            if (methodName.equals("interact"))
                            {
                                calls.interactCalls++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private static final class DispatchCalls
    {
        private int combineCalls;
        private int interactCalls;
    }
}
