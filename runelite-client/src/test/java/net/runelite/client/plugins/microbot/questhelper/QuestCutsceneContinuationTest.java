package net.runelite.client.plugins.microbot.questhelper;

import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class QuestCutsceneContinuationTest
{
    @Test
    public void cutsceneReadyPromptAdvancesWithoutEnteringWalkingBranch()
    {
        Widget prompt = mock(Widget.class);
        when(prompt.getText()).thenReturn("Click here to continue");
        when(prompt.getBounds()).thenReturn(new Rectangle(10, 10, 100, 20));
        AtomicInteger clicks = new AtomicInteger();
        QuestDialogueContinuation continuation = new QuestDialogueContinuation();
        if (!QuestInteractionFlow.handleCutscene(true, () -> continuation.advance(
                QuestDialogueContinuation.capture(prompt), 0, bounds -> clicks.incrementAndGet())))
        {
            fail("Cutscene must not enter walking or object dispatch");
        }
        assertEquals(1, clicks.get());
        assertTrue(QuestInteractionFlow.handleCutscene(true, () -> continuation.advance(
                QuestDialogueContinuation.capture(prompt), 400, bounds -> clicks.incrementAndGet())));
        assertEquals("Cutscene continuation retains the existing retry cooldown", 1, clicks.get());
    }

    @Test
    public void cutsceneWithoutReadyPromptStillSuppressesWalking()
    {
        for (String text : new String[]{"Please wait...", "Select an Option", ""})
        {
            Widget prompt = mock(Widget.class);
            when(prompt.getText()).thenReturn(text);
            when(prompt.getBounds()).thenReturn(new Rectangle(10, 10, 100, 20));
            assertTrue(QuestInteractionFlow.handleCutscene(true,
                    () -> new QuestDialogueContinuation().advance(
                            QuestDialogueContinuation.capture(prompt), 0, bounds -> fail("No ready prompt"))));
        }
        assertTrue(QuestInteractionFlow.handleCutscene(true,
                () -> new QuestDialogueContinuation().advance(null, 0, bounds -> fail("No prompt"))));
    }

    @Test
    public void normalTickDoesNotConsumeItsDialoguePhase()
    {
        assertFalse(QuestInteractionFlow.handleCutscene(false, () -> fail("Normal flow owns dialogue")));
    }

    @Test
    public void visibleOptionsPanelStillBlocksTheReadyPrompt() throws Exception
    {
        net.runelite.api.Client client = mock(net.runelite.api.Client.class);
        net.runelite.client.callback.ClientThread thread = mock(net.runelite.client.callback.ClientThread.class);
        when(thread.runOnClientThreadOptional(any())).thenAnswer(invocation ->
                java.util.Optional.ofNullable(((java.util.concurrent.Callable<?>) invocation.getArgument(0)).call()));
        Widget options = mock(Widget.class);
        when(client.getWidget(net.runelite.api.widgets.ComponentID.DIALOG_OPTION_OPTIONS)).thenReturn(options);
        java.lang.reflect.Field clientField = net.runelite.client.plugins.microbot.Microbot.class.getDeclaredField("client");
        java.lang.reflect.Field threadField = net.runelite.client.plugins.microbot.Microbot.class.getDeclaredField("clientThread");
        clientField.setAccessible(true);
        threadField.setAccessible(true);
        Object previousClient = clientField.get(null);
        Object previousThread = threadField.get(null);
        try
        {
            clientField.set(null, client);
            threadField.set(null, thread);
            java.lang.reflect.Method capture = QuestScript.class.getDeclaredMethod("readyDialoguePrompt");
            capture.setAccessible(true);
            assertNull(capture.invoke(new QuestScript()));
            verify(client).getWidget(net.runelite.api.widgets.ComponentID.DIALOG_OPTION_OPTIONS);
        }
        finally
        {
            clientField.set(null, previousClient);
            threadField.set(null, previousThread);
        }
    }

    @Test
    public void questLoopReturnsImmediatelyAfterCutsceneContinuation() throws Exception
    {
        org.objectweb.asm.tree.ClassNode script = new org.objectweb.asm.tree.ClassNode();
        try (java.io.InputStream input = QuestScript.class.getResourceAsStream("QuestScript.class"))
        {
            new org.objectweb.asm.ClassReader(input).accept(script, 0);
        }
        int guards = 0;
        for (org.objectweb.asm.tree.MethodNode method : script.methods)
        {
            boolean callback = false;
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions)
            {
                if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode)
                {
                    for (Object argument : ((org.objectweb.asm.tree.InvokeDynamicInsnNode) instruction).bsmArgs)
                    {
                        if (argument instanceof org.objectweb.asm.Handle
                                && ((org.objectweb.asm.Handle) argument).getName().equals("continueReadyDialogue"))
                            callback = true;
                    }
                }
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode
                        && ((org.objectweb.asm.tree.MethodInsnNode) instruction).name.equals("handleCutscene"))
                {
                    guards++;
                    assertTrue("Cutscene must reuse the guarded prompt dispatcher", callback);
                    org.objectweb.asm.tree.AbstractInsnNode branch = nextInstruction(instruction);
                    assertEquals(org.objectweb.asm.Opcodes.IFEQ, branch.getOpcode());
                    assertEquals(org.objectweb.asm.Opcodes.RETURN, nextInstruction(branch).getOpcode());
                }
            }
        }
        assertEquals(1, guards);
    }

    private static org.objectweb.asm.tree.AbstractInsnNode nextInstruction(
            org.objectweb.asm.tree.AbstractInsnNode instruction)
    {
        do { instruction = instruction.getNext(); }
        while (instruction != null && instruction.getOpcode() < 0);
        assertNotNull(instruction);
        return instruction;
    }
}
