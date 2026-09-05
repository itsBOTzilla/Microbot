package net.runelite.client.plugins.microbot.questhelper;

import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class QuestDialogueContinuationTest
{
    @Test
    public void waitingHiddenAndEmptyPromptsDoNotDispatch()
    {
        Widget prompt = page("Hello");
        when(prompt.getText()).thenReturn("Please wait...");
        assertNull(QuestDialogueContinuation.capture(prompt));
        when(prompt.getText()).thenReturn("Click here to continue");
        when(prompt.isHidden()).thenReturn(true);
        assertNull(QuestDialogueContinuation.capture(prompt));
        when(prompt.isHidden()).thenReturn(false);
        when(prompt.getBounds()).thenReturn(new Rectangle());
        assertNull(QuestDialogueContinuation.capture(prompt));
        assertNull(QuestDialogueContinuation.capture(null));
        assertFalse(new QuestDialogueContinuation().advance(null, 0, bounds -> fail("No click expected")));
    }

    @Test
    public void repeatedReadyPageWaitsBeforeRetrying()
    {
        QuestDialogueContinuation continuation = new QuestDialogueContinuation();
        AtomicInteger clicks = new AtomicInteger();
        Widget prompt = page("Hello");
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(prompt), 0, b -> clicks.incrementAndGet()));
        for (long now : new long[]{400, 800, 1_200, 2_999})
        {
            assertFalse(continuation.advance(QuestDialogueContinuation.capture(prompt), now, b -> clicks.incrementAndGet()));
        }
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(prompt), 3_000, b -> clicks.incrementAndGet()));
        assertEquals(2, clicks.get());
    }

    @Test
    public void changedPageAdvancesWithoutWaitingForRetryTimeout()
    {
        QuestDialogueContinuation continuation = new QuestDialogueContinuation();
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(page("First line")), 0, b -> {}));
        assertFalse(continuation.advance(QuestDialogueContinuation.capture(page("Second line")), 400, b -> fail()));
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(page("Second line")), 600, b -> {}));
    }

    @Test
    public void replacementWidgetWithSamePageDoesNotCauseDuplicateClick()
    {
        QuestDialogueContinuation continuation = new QuestDialogueContinuation();
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(page("Same line")), 0, b -> {}));
        assertFalse(continuation.advance(QuestDialogueContinuation.capture(page("Same line")), 600, b -> fail()));
    }

    @Test
    public void missingPromptDoesNotClearPendingPage()
    {
        QuestDialogueContinuation continuation = new QuestDialogueContinuation();
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(page("Hello")), 0, b -> {}));
        assertFalse(continuation.advance(null, 400, b -> fail()));
        assertFalse(continuation.advance(QuestDialogueContinuation.capture(page("Hello")), 800, b -> fail()));
        continuation.reset();
        assertTrue(continuation.advance(QuestDialogueContinuation.capture(page("Hello")), 800, b -> {}));
    }

    @Test
    public void sharedGateIsNotReservedWhileClickGateIsWaiting()
    {
        QuestDialogueAdvance shared = new QuestDialogueAdvance();
        QuestDialogueContinuation clicks = new QuestDialogueContinuation();
        assertTrue(advanceThroughBoth(shared, clicks, "First", 0));
        assertFalse(advanceThroughBoth(shared, clicks, "Second", 200));
        assertTrue(advanceThroughBoth(shared, clicks, "Second", 600));
        assertFalse(advanceThroughBoth(shared, clicks, "Second", 1_800));
        assertFalse(advanceThroughBoth(shared, clicks, "Second", 3_599));
        assertTrue(advanceThroughBoth(shared, clicks, "Second", 3_600));
    }

    private static boolean advanceThroughBoth(QuestDialogueAdvance shared,
                                             QuestDialogueContinuation clicks, String text, long now)
    {
        QuestDialogueContinuation.Prompt prompt = QuestDialogueContinuation.capture(page(text));
        return clicks.canAdvance(prompt, now)
            && shared.shouldAdvance(text, now * 1_000_000)
            && clicks.advance(prompt, now, bounds -> {});
    }

    private static Widget page(String text)
    {
        Widget root = mock(Widget.class);
        Widget body = mock(Widget.class);
        Widget prompt = mock(Widget.class);
        when(root.getId()).thenReturn(231 << 16);
        when(body.getId()).thenReturn((231 << 16) | 6);
        when(body.getText()).thenReturn(text);
        when(prompt.getId()).thenReturn((231 << 16) | 5);
        when(prompt.getText()).thenReturn("<col=ffffff>Click here to continue</col>");
        when(prompt.getBounds()).thenReturn(new Rectangle(10, 10, 100, 20));
        when(prompt.getParent()).thenReturn(root);
        when(root.getStaticChildren()).thenReturn(new Widget[]{body, prompt});
        return prompt;
    }
}
