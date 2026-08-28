package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;

public class QuestMissingItemPromptFlowTest
{
    @Test
    public void missingItemsAreDetectedBeforeAskingWhetherToObtainThem() throws IOException
    {
        PromptCalls calls = readPromptCalls();

        assertEquals("Missing-item handling must own the obtain-items decision",
                1, calls.callsFromMissingItemHandler);
        assertEquals("The quest loop must not ask before it knows an item is missing",
                0, calls.callsFromOtherMethods);
        assertEquals("Missing-item handling must inspect only the active step requirements",
                1, calls.currentStepRequirementReads);
        assertEquals("Missing-item handling must not preempt the active step with future quest requirements",
                0, calls.allQuestRequirementReads);
    }

    private static PromptCalls readPromptCalls() throws IOException
    {
        String resource = "/" + Type.getInternalName(QuestScript.class) + ".class";
        PromptCalls calls = new PromptCalls();
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
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface)
                        {
                            if (!owner.equals(Type.getInternalName(QuestScript.class))
                                    || !methodName.equals("shouldObtainMissingItems"))
                            {
                                if (name.equals("handleMissingItemRequirements")
                                        && owner.equals(Type.getInternalName(DetailedQuestStep.class))
                                        && methodName.equals("getRequirements"))
                                {
                                    calls.currentStepRequirementReads++;
                                }
                                if (name.equals("handleMissingItemRequirements")
                                        && owner.equals(Type.getInternalName(QuestScript.class))
                                        && methodName.equals("collectAllItemRequirements"))
                                {
                                    calls.allQuestRequirementReads++;
                                }
                                return;
                            }

                            if (name.equals("handleMissingItemRequirements"))
                            {
                                calls.callsFromMissingItemHandler++;
                            }
                            else
                            {
                                calls.callsFromOtherMethods++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private static final class PromptCalls
    {
        private int callsFromMissingItemHandler;
        private int callsFromOtherMethods;
        private int currentStepRequirementReads;
        private int allQuestRequirementReads;
    }
}
