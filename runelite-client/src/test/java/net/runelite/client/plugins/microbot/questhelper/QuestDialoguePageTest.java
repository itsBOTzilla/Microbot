package net.runelite.client.plugins.microbot.questhelper;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class QuestDialoguePageTest
{
    @Test
    public void changedOptionsUnderSameTitleProduceNewPage()
    {
        Widget first = widget(1, "Select an option", false, widget(2, "Yes", false));
        Widget second = widget(1, "Select an option", false, widget(2, "No", false));
        assertNotEquals(QuestDialoguePage.snapshot(Collections.singleton(first), false),
            QuestDialoguePage.snapshot(Collections.singleton(second), false));
    }

    @Test
    public void equalContentsAreEqualDespiteDifferentWidgetInstances()
    {
        Widget first = widget(1, "Title", false, widget(2, "Option", false));
        Widget second = widget(1, "Title", false, widget(2, "Option", false));
        assertEquals(QuestDialoguePage.snapshot(Collections.singleton(first), true),
            QuestDialoguePage.snapshot(Collections.singleton(second), true));
    }

    @Test
    public void hiddenWidgetsAndTheirChildrenDoNotAffectPage()
    {
        Widget visible = widget(1, "Title", false);
        Widget hidden = widget(2, "Hidden", true, widget(3, "Hidden child", false));
        assertEquals(QuestDialoguePage.snapshot(Collections.singleton(visible), false),
            QuestDialoguePage.snapshot(Arrays.asList(visible, hidden), false));
    }

    @Test
    public void continueReadinessChangesPage()
    {
        List<Widget> roots = Collections.singletonList(widget(1, "Title", false));
        assertNotEquals(QuestDialoguePage.snapshot(roots, false), QuestDialoguePage.snapshot(roots, true));
    }

    @Test
    public void duplicateChildrenAndCyclesAreVisitedOnce()
    {
        Widget[] children = new Widget[1];
        Widget root = widget(1, "Title", false, children);
        children[0] = root;
        assertEquals(QuestDialoguePage.snapshot(Collections.singleton(widget(1, "Title", false)), false),
            QuestDialoguePage.snapshot(Arrays.asList(root, root), false));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void snapshotIsImmutable()
    {
        QuestDialoguePage.snapshot(Collections.emptyList(), false).add("mutation");
    }

    private static Widget widget(int id, String text, boolean hidden, Widget... children)
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getId": return id;
                    case "getIndex": return 0;
                    case "getText": return text;
                    case "isHidden": return hidden;
                    case "getChildren":
                    case "getDynamicChildren":
                    case "getStaticChildren":
                    case "getNestedChildren": return children;
                    default: throw new AssertionError("Unexpected widget read: " + method.getName());
                }
            });
    }
}
