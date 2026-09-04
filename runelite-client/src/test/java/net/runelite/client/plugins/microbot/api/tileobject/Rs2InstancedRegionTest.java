package net.runelite.client.plugins.microbot.api.tileobject;

import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Rs2InstancedRegionTest
{
    private Client previousClient;
    private ClientThread previousClientThread;
    private Client client;

    @Before
    public void setUp() throws Exception
    {
        previousClient = (Client) getStaticField(Microbot.class, "client");
        previousClientThread = (ClientThread) getStaticField(Microbot.class, "clientThread");
        client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        ClientThread clientThread = new ClientThread();
        setField(clientThread, "client", client);
        setStaticField(Microbot.class, "client", client);
        setStaticField(Microbot.class, "clientThread", clientThread);
    }

    @After
    public void tearDown() throws Exception
    {
        setStaticField(Microbot.class, "client", previousClient);
        setStaticField(Microbot.class, "clientThread", previousClientThread);
    }

    @Test
    public void unresolvedDispatcherMayNameAVariantButCannotExposeItsActions()
    {
        Rs2TileObjectModel model = mock(Rs2TileObjectModel.class);
        ObjectComposition base = mock(ObjectComposition.class);
        ObjectComposition variant = mock(ObjectComposition.class);
        when(model.getId()).thenReturn(34779);
        when(model.getName()).thenReturn(null);
        when(base.getImpostorIds()).thenReturn(new int[]{-1, 34776});
        when(base.getImpostor()).thenReturn(null);
        when(variant.getName()).thenReturn("Portal");
        when(variant.getActions()).thenReturn(new String[]{"Use", null, null, null, null});
        when(client.getObjectDefinition(34779)).thenReturn(base);
        when(client.getObjectDefinition(34776)).thenReturn(variant);

        assertEquals("Portal", Rs2InstancedRegion.resolveObjectName(model));
        assertNull(Rs2InstancedRegion.safeActions(model));
    }

    @Test
    public void selectedVariantReturnsItsIdentifier()
    {
        Rs2TileObjectModel model = mock(Rs2TileObjectModel.class);
        ObjectComposition base = mock(ObjectComposition.class);
        ObjectComposition variant = mock(ObjectComposition.class);
        when(model.getId()).thenReturn(34779);
        when(base.getImpostorIds()).thenReturn(new int[]{34776});
        when(base.getImpostor()).thenReturn(variant);
        when(variant.getId()).thenReturn(34776);
        when(client.getObjectDefinition(34779)).thenReturn(base);

        assertEquals(34776, Rs2InstancedRegion.resolveImpostorId(model));
    }

    @Test
    public void onlySelectedActionsAreCopiedWithinClientCallback() throws Exception
    {
        java.util.concurrent.atomic.AtomicBoolean inside = new java.util.concurrent.atomic.AtomicBoolean();
        ClientThread callbacks = new ClientThread()
        {
            @Override
            public <T> java.util.Optional<T> runOnClientThreadOptional(java.util.concurrent.Callable<T> callable)
            {
                inside.set(true);
                try { return java.util.Optional.ofNullable(callable.call()); }
                catch (Exception ex) { throw new AssertionError(ex); }
                finally { inside.set(false); }
            }
        };
        setStaticField(Microbot.class, "clientThread", callbacks);
        Rs2TileObjectModel model = mock(Rs2TileObjectModel.class);
        ObjectComposition base = mock(ObjectComposition.class);
        ObjectComposition selected = mock(ObjectComposition.class);
        when(model.getId()).thenReturn(100);
        when(client.getObjectDefinition(100)).thenReturn(base);
        when(base.getImpostorIds()).thenReturn(new int[]{101, 102});
        when(base.getImpostor()).thenReturn(selected);
        String[] actions = {"Close"};
        when(selected.getActions()).thenAnswer(call ->
        {
            assertTrue("ObjectComposition read outside client callback", inside.get());
            return actions;
        });

        String[] snapshot = Rs2InstancedRegion.safeActions(model);
        actions[0] = "Changed";
        assertArrayEquals(new String[]{"Close"}, snapshot);
        org.mockito.Mockito.doReturn(new String[]{null}).when(selected).getActions();
        ObjectComposition inactive = mock(ObjectComposition.class);
        when(client.getObjectDefinition(102)).thenReturn(inactive);
        when(inactive.getActions()).thenReturn(new String[]{"Open"});
        assertNull(Rs2InstancedRegion.safeActions(model));
    }

    private static Object getStaticField(Class<?> type, String name) throws Exception
    {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception
    {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
