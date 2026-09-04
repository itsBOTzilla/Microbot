package net.runelite.client.plugins.microbot.questhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.runelite.api.widgets.Widget;

/** Copies dialogue identity without retaining live widgets or exposing text to logs. */
final class QuestDialoguePage
{
    private static final int MAX_DEPTH = 16;

    private QuestDialoguePage()
    {
    }

    /** All widget access must run on the client thread. The returned key contains only copied values. */
    static List<String> snapshot(Iterable<Widget> roots, boolean hasContinue)
    {
        List<String> values = new ArrayList<>();
        values.add(Boolean.toString(hasContinue));
        Set<Widget> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (roots != null)
        {
            for (Widget root : roots)
            {
                append(root, 0, values, visited);
            }
        }
        return Collections.unmodifiableList(values);
    }

    private static void append(Widget widget, int depth, List<String> values, Set<Widget> visited)
    {
        if (widget == null || depth > MAX_DEPTH || !visited.add(widget) || widget.isHidden())
        {
            return;
        }
        values.add(Integer.toString(widget.getId()));
        values.add(Integer.toString(widget.getIndex()));
        values.add(widget.getText());
        values.add("visible");
        appendChildren(widget.getChildren(), depth, values, visited);
        appendChildren(widget.getDynamicChildren(), depth, values, visited);
        appendChildren(widget.getStaticChildren(), depth, values, visited);
        appendChildren(widget.getNestedChildren(), depth, values, visited);
    }

    private static void appendChildren(Widget[] children, int depth, List<String> values, Set<Widget> visited)
    {
        if (children != null)
        {
            for (Widget child : children)
            {
                append(child, depth + 1, values, visited);
            }
        }
    }
}
