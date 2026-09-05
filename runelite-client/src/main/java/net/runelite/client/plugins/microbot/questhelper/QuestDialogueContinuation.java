package net.runelite.client.plugins.microbot.questhelper;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;

/** Tracks dispatched dialogue pages without sending keys to option or waiting widgets. */
final class QuestDialogueContinuation
{
    private static final long PAGE_INTERVAL_MS = 600;
    private static final long RETRY_INTERVAL_MS = 3_000;
    private String lastPage;
    private long lastAttempt;

    /** Must be called on the client thread; the result contains no live widget references. */
    static Prompt capture(Widget widget)
    {
        if (widget == null || widget.isHidden()
            || !"Click here to continue".equalsIgnoreCase(Rs2UiHelper.stripTagsToSpace(widget.getText())))
        {
            return null;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.isEmpty())
        {
            return null;
        }

        Widget root = widget;
        Set<Widget> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(root))
        {
            Widget parent = root.getParent();
            if (parent == null || (parent.getId() >>> 16) != (widget.getId() >>> 16)
                || visited.contains(parent))
            {
                break;
            }
            root = parent;
        }
        StringBuilder page = new StringBuilder().append(widget.getId()).append(':');
        appendText(root, page, Collections.newSetFromMap(new IdentityHashMap<>()));
        return new Prompt(page.toString(), new Rectangle(bounds));
    }

    private static void appendText(Widget widget, StringBuilder page, Set<Widget> visited)
    {
        if (widget == null || !visited.add(widget) || widget.isHidden())
        {
            return;
        }
        page.append(widget.getId()).append('=').append(widget.getText()).append('\n');
        Widget[][] children = {widget.getChildren(), widget.getStaticChildren(),
            widget.getDynamicChildren(), widget.getNestedChildren()};
        for (Widget[] group : children)
        {
            if (group != null)
            {
                for (Widget child : group)
                {
                    appendText(child, page, visited);
                }
            }
        }
    }

    boolean advance(Prompt prompt, long now, Consumer<Rectangle> click)
    {
        if (!canAdvance(prompt, now))
        {
            return false;
        }
        lastPage = prompt.page;
        lastAttempt = now;
        click.accept(new Rectangle(prompt.bounds));
        return true;
    }

    boolean canAdvance(Prompt prompt, long now)
    {
        if (prompt == null)
        {
            return false;
        }
        if (lastPage != null && now - lastAttempt
            < (lastPage.equals(prompt.page) ? RETRY_INTERVAL_MS : PAGE_INTERVAL_MS))
        {
            return false;
        }
        return true;
    }

    void reset()
    {
        lastPage = null;
        lastAttempt = 0;
    }

    static final class Prompt
    {
        private final String page;
        private final Rectangle bounds;

        private Prompt(String page, Rectangle bounds)
        {
            this.page = page;
            this.bounds = bounds;
        }
    }
}
