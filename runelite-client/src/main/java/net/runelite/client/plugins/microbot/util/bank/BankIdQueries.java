package net.runelite.client.plugins.microbot.util.bank;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntUnaryOperator;

final class BankIdQueries
{
    private BankIdQueries()
    {
    }

    static boolean containsAny(IntUnaryOperator countById, int... ids)
    {
        if (countById == null || ids == null)
        {
            return false;
        }
        for (int id : ids)
        {
            if (id > 0 && countById.applyAsInt(id) > 0)
            {
                return true;
            }
        }
        return false;
    }

    static boolean containsAll(IntUnaryOperator countById, int... ids)
    {
        if (countById == null || ids == null || ids.length == 0)
        {
            return false;
        }
        for (int id : ids)
        {
            if (id <= 0 || countById.applyAsInt(id) <= 0)
            {
                return false;
            }
        }
        return true;
    }

    static int count(IntUnaryOperator countById, int... ids)
    {
        if (countById == null || ids == null)
        {
            return 0;
        }
        Set<Integer> uniqueIds = new LinkedHashSet<>();
        for (int id : ids)
        {
            if (id > 0)
            {
                uniqueIds.add(id);
            }
        }
        return uniqueIds.stream().mapToInt(id -> countById.applyAsInt(id)).sum();
    }

    static int firstAvailableId(IntUnaryOperator countById, int... ids)
    {
        if (countById == null || ids == null)
        {
            return -1;
        }
        for (int id : ids)
        {
            if (id > 0 && countById.applyAsInt(id) > 0)
            {
                return id;
            }
        }
        return -1;
    }
}
