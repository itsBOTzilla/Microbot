package net.runelite.client.plugins.microbot.api.tileobject;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;

/**
 * Resolves tile-object coordinates and transformed metadata inside instanced regions.
 */
@Slf4j
public final class Rs2InstancedRegion
{
    private Rs2InstancedRegion()
    {
    }

    public static WorldPoint instancePublicWorldPoint(Rs2TileObjectModel object)
    {
        if (object == null)
        {
            return null;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
        {
            LocalPoint localPoint = object.getLocalLocation();
            return localPoint == null ? null : WorldPoint.fromLocalInstance(Microbot.getClient(), localPoint);
        }).orElse(null);
    }

    public static String resolveObjectName(Rs2TileObjectModel object)
    {
        if (object == null)
        {
            return null;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
        {
            try
            {
                String name = object.getName();
                if (isRealName(name))
                {
                    return name;
                }

                ObjectComposition base = Microbot.getClient().getObjectDefinition(object.getId());
                if (base == null)
                {
                    return null;
                }
                if (isRealName(base.getName()))
                {
                    return base.getName();
                }

                int[] impostorIds = base.getImpostorIds();
                if (impostorIds != null)
                {
                    for (int impostorId : impostorIds)
                    {
                        if (impostorId < 0)
                        {
                            continue;
                        }
                        ObjectComposition variant = Microbot.getClient().getObjectDefinition(impostorId);
                        if (variant != null && isRealName(variant.getName()))
                        {
                            return variant.getName();
                        }
                    }
                }
                return null;
            }
            catch (Exception ignored)
            {
                return null;
            }
        }).orElse(null);
    }

    public static int resolveImpostorId(Rs2TileObjectModel object)
    {
        if (object == null)
        {
            return -1;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
        {
            try
            {
                ObjectComposition base = Microbot.getClient().getObjectDefinition(object.getId());
                if (base != null && base.getImpostorIds() != null)
                {
                    ObjectComposition selected = base.getImpostor();
                    if (selected != null)
                    {
                        return selected.getId();
                    }
                }
            }
            catch (Exception ignored)
            {
                // Metadata can disappear between the cache query and this lookup.
            }
            return -1;
        }).orElse(-1);
    }

    public static String[] safeActions(Rs2TileObjectModel object)
    {
        if (object == null)
        {
            return null;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() ->
        {
            ObjectComposition base = Microbot.getClient().getObjectDefinition(object.getId());
            ObjectComposition selected = base == null ? null
                    : base.getImpostorIds() == null ? base : base.getImpostor();
            String[] actions = selected == null ? null : selected.getActions();
            return hasNonNullAction(actions) ? actions.clone() : null;
        }).orElse(null);
    }

    public static boolean hasNonNullAction(String[] actions)
    {
        if (actions == null)
        {
            return false;
        }
        for (String action : actions)
        {
            if (action != null && !action.isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAction(Rs2TileObjectModel object, String... verbs)
    {
        String[] actions = safeActions(object);
        if (actions == null || verbs == null)
        {
            return false;
        }
        for (String action : actions)
        {
            if (action == null)
            {
                continue;
            }
            String normalizedAction = action.toLowerCase(Locale.ROOT);
            for (String verb : verbs)
            {
                if (verb != null && normalizedAction.contains(verb.toLowerCase(Locale.ROOT)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean safeReachable(Rs2TileObjectModel object)
    {
        try
        {
            return object != null && object.isReachable();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    public static boolean safeClickAction(Rs2TileObjectModel object, String action)
    {
        try
        {
            return object != null && object.click(action);
        }
        catch (Exception ex)
        {
            log.debug("safeClickAction({}) failed", action, ex);
            return false;
        }
    }

    public static boolean safeClick(Rs2TileObjectModel object)
    {
        try
        {
            return object != null && object.click();
        }
        catch (Exception ex)
        {
            log.debug("safeClick failed", ex);
            return false;
        }
    }

    private static boolean isRealName(String name)
    {
        return name != null && !name.isEmpty() && !"null".equalsIgnoreCase(name);
    }
}
