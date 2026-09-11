package net.runelite.client.plugins.microbot.util.bank;

import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Rs2BankMultiIdTest
{
    @Test
    public void exposesVarargBankQueries() throws Exception
    {
        for (String methodName : new String[] {"contains", "containsAll", "count", "get"})
        {
            Method method = Rs2Bank.class.getMethod(methodName, int[].class);
            assertNotNull(method);
        }
    }

    @Test
    public void multiIdPredicatesDistinguishAnyFromAll()
    {
        assertTrue(BankIdQueries.containsAny(id -> id == 2 ? 3 : 0, 1, 2));
        assertFalse(BankIdQueries.containsAll(id -> id == 2 ? 3 : 0, 1, 2));
        assertTrue(BankIdQueries.containsAll(id -> 1, 1, 2));
    }

    @Test
    public void countSumsUniqueIdsAndFirstAvailableRespectsPreferenceOrder()
    {
        assertEquals(7, BankIdQueries.count(id -> id == 1 ? 2 : 5, 1, 2, 1));
        assertEquals(2, BankIdQueries.firstAvailableId(id -> id == 2 ? 5 : 0, 1, 2, 3));
        assertEquals(-1, BankIdQueries.firstAvailableId(id -> 0, 1, 2, 3));
    }
}
