package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.questhelper.steps.NpcStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class QuestScriptLifecycleTest
{
    @Test
    public void shutdownClearsPendingInteractionAndDialogueReadiness() throws Exception
    {
        QuestScript script = new QuestScript();
        QuestStep step = mock(NpcStep.class);
        Method begin = QuestScript.class.getDeclaredMethod("beginInteraction", QuestStep.class,
            int.class, long.class, WorldPoint.class, String.class, int.class, int.class);
        begin.setAccessible(true);
        begin.invoke(script, step, 1, 2L, new WorldPoint(3200, 3200, 0), "Talk-to", -1, 0);
        assertNotNull(field("pendingInteraction").get(script));

        QuestDialogueAdvance dialogue = (QuestDialogueAdvance) field("dialogueAdvance").get(script);
        assertTrue(dialogue.shouldAdvance("page", 0L));
        assertFalse(dialogue.shouldAdvance("page", 1L));
        field("targetReadyAt").setLong(script, 123L);
        field("lastCustomStep").set(script, step);
        field("customActionPending").setBoolean(script, true);
        script.dialogueStartedStep = step;
        script.unreachableTarget = true;
        AtomicLong generation = (AtomicLong) field("interactionResetGeneration").get(null);
        long previousGeneration = generation.get();

        script.shutdown();

        assertNull(field("pendingInteraction").get(script));
        assertEquals(0L, field("targetReadyAt").getLong(script));
        assertNull(field("lastCustomStep").get(script));
        assertFalse(field("customActionPending").getBoolean(script));
        assertNull(script.dialogueStartedStep);
        assertFalse(script.unreachableTarget);
        assertTrue("Same dialogue page becomes eligible immediately after reset",
            dialogue.shouldAdvance("page", 1L));
        assertEquals(previousGeneration + 1, generation.get());
    }

    private static Field field(String name) throws NoSuchFieldException
    {
        Field field = QuestScript.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

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
