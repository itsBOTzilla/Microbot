package net.runelite.client.plugins.microbot.api.tileobject.models;

import java.awt.Rectangle;
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Rs2TileObjectModelInteractionTest
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
    public void clickReturnsFalseWhenCompositionAndLiveActionsAreUnavailable()
    {
        TileObject tileObject = mock(TileObject.class);
        when(tileObject.getLocalLocation()).thenReturn(new LocalPoint(128, 128));

        Rs2TileObjectModel model = new Rs2TileObjectModel(tileObject)
        {
            @Override
            public String getName()
            {
                return null;
            }

            @Override
            public ObjectComposition getObjectComposition()
            {
                return null;
            }
        };

        assertFalse(model.click("Use"));
    }

    @Test
    public void resolveLiveActionUsesShownOperationIndex()
    {
        TileObject tileObject = mock(TileObject.class);
        when(tileObject.isOpShown(2)).thenReturn(true);
        when(tileObject.getOpOverride(2)).thenReturn("Use");

        assertEquals(2, new Rs2TileObjectModel(tileObject).resolveActionIndex("Use"));
    }

    @Test
    public void resolveDefaultActionUsesFirstMenuSlot()
    {
        assertEquals(0, new Rs2TileObjectModel(mock(TileObject.class)).resolveActionIndex(""));
    }

    @Test
    public void resolveActionRejectsUnresolvedImpostorVariant()
    {
        TileObject tileObject = mock(TileObject.class);
        when(tileObject.getId()).thenReturn(34825);
        ObjectComposition base = mock(ObjectComposition.class);
        ObjectComposition variant = mock(ObjectComposition.class);
        when(base.getImpostorIds()).thenReturn(new int[]{-1, 34826});
        when(base.getImpostor()).thenReturn(null);
        when(variant.getActions()).thenReturn(new String[]{null, null, "Use", null, null});
        when(client.getObjectDefinition(34825)).thenReturn(base);
        when(client.getObjectDefinition(34826)).thenReturn(variant);

        assertEquals(-1, new Rs2TileObjectModel(tileObject).resolveActionIndex("Use"));
    }

    @Test
    public void interactionIdentifierMustBelongToDispatcherVariants()
    {
        TileObject tileObject = mock(TileObject.class);
        when(tileObject.getId()).thenReturn(34825);
        ObjectComposition base = mock(ObjectComposition.class);
        when(base.getImpostorIds()).thenReturn(new int[]{-1, 34774, 34775, 34776, 34777, 34778});
        when(client.getObjectDefinition(34825)).thenReturn(base);
        Rs2TileObjectModel model = new Rs2TileObjectModel(tileObject);

        assertEquals(34776, model.resolveInteractionIdentifier(34776));
        assertEquals(-1, model.resolveInteractionIdentifier(34826));
    }

    @Test
    public void metadataAccessReturnsNullWhenObjectDefinitionIsUnavailable()
    {
        TileObject tileObject = mock(TileObject.class);
        when(tileObject.getId()).thenReturn(43700);
        when(client.getObjectDefinition(43700)).thenReturn(null);
        Rs2TileObjectModel model = new Rs2TileObjectModel(tileObject);

        assertNull(model.getName());
        assertNull(model.getObjectComposition());
    }

    @Test
    public void actionsAreResolvedAndSnapshottedOnClientThread()
    {
        TileObject tileObject = mock(TileObject.class);
        when(tileObject.getId()).thenReturn(34825);
        ObjectComposition base = mock(ObjectComposition.class);
        ObjectComposition variant = mock(ObjectComposition.class);
        String[] liveActions = {null, "Tend-to", "Rest", null, null};
        when(base.getImpostorIds()).thenReturn(new int[]{34826});
        when(base.getImpostor()).thenReturn(variant);
        when(variant.getActions()).thenReturn(liveActions);
        when(client.getObjectDefinition(34825)).thenReturn(base);

        String[] snapshot = new Rs2TileObjectModel(tileObject).getActions();

        assertEquals("Tend-to", snapshot[1]);
        liveActions[1] = "Changed";
        assertEquals("Tend-to", snapshot[1]);
    }

    @Test
    public void directActionDispatchesExactIdentifierThroughRuneLiteMenuApi()
    {
        assertTrue(Rs2TileObjectModel.dispatchDirectAction(
                client, 12, 34, MenuAction.GAME_OBJECT_THIRD_OPTION,
                34776, WorldView.TOPLEVEL, "Use", null));

        verify(client).menuAction(
                12, 34, MenuAction.GAME_OBJECT_THIRD_OPTION,
                34776, -1, "Use", "");
    }

    @Test
    public void directVariantActionUsesRuneLiteMenuApiEvenWhenClickboxIsVisible()
    {
        GameObject tileObject = mock(GameObject.class);
        WorldView worldView = mock(WorldView.class);
        ObjectComposition base = mock(ObjectComposition.class);
        when(tileObject.getId()).thenReturn(34825);
        when(tileObject.getLocalLocation()).thenReturn(new LocalPoint(1536, 2816));
        when(tileObject.sizeX()).thenReturn(1);
        when(tileObject.sizeY()).thenReturn(1);
        when(tileObject.getWorldView()).thenReturn(worldView);
        when(tileObject.isOpShown(0)).thenReturn(true);
        when(tileObject.getOpOverride(0)).thenReturn("Use");
        when(tileObject.getClickbox()).thenReturn(new Rectangle(100, 100, 20, 20));
        when(worldView.getId()).thenReturn(WorldView.TOPLEVEL);
        when(base.getImpostorIds()).thenReturn(new int[]{34776});
        ObjectComposition requested = mock(ObjectComposition.class);
        when(requested.getActions()).thenReturn(new String[]{"Use"});
        when(client.getObjectDefinition(34776)).thenReturn(requested);
        when(client.getObjectDefinition(34825)).thenReturn(base);
        Rs2TileObjectModel model = new Rs2TileObjectModel(tileObject)
        {
            @Override
            public String getName()
            {
                return "Portal";
            }
        };

        assertTrue(model.clickVariantDirect("Use", 34776));

        verify(client).menuAction(
                12, 22, MenuAction.GAME_OBJECT_FIRST_OPTION,
                34776, -1, "Use", "Portal");
        verify(tileObject, never()).getClickbox();
    }

    @Test
    public void directRuneLiteMenuApiRejectsChildWorldView()
    {
        assertFalse(Rs2TileObjectModel.dispatchDirectAction(
                client, 12, 34, MenuAction.GAME_OBJECT_THIRD_OPTION,
                34825, 7, "Use", "Portal"));

        verify(client, never()).menuAction(
                anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(),
                anyString(), anyString());
    }

    @Test
    public void inactiveVariantCannotSupplyAnActionForTheSelectedObject()
    {
        TileObject object = mock(TileObject.class);
        when(object.getId()).thenReturn(100);
        ObjectComposition base = mock(ObjectComposition.class);
        ObjectComposition current = mock(ObjectComposition.class);
        ObjectComposition inactive = mock(ObjectComposition.class);
        when(client.getObjectDefinition(100)).thenReturn(base);
        when(base.getImpostorIds()).thenReturn(new int[]{101, 102});
        when(base.getImpostor()).thenReturn(current);
        when(current.getActions()).thenReturn(new String[]{"Close"});
        when(client.getObjectDefinition(102)).thenReturn(inactive);
        when(inactive.getActions()).thenReturn(new String[]{"Open", "Close"});
        Rs2TileObjectModel model = new Rs2TileObjectModel(object);

        assertEquals(-1, model.resolveActionIndex("Open"));
        assertEquals(0, model.resolveActionIndex("Close"));
        assertEquals(1, model.resolveVariantActionIndex("Close", 102));
        assertEquals(-1, model.resolveVariantActionIndex("Open", 999));
    }

    @Test
    public void liveOverrideReplacesDefinitionAction()
    {
        TileObject object = mock(TileObject.class);
        when(object.getId()).thenReturn(100);
        when(object.isOpShown(0)).thenReturn(true);
        when(object.getOpOverride(0)).thenReturn("Close");
        ObjectComposition definition = mock(ObjectComposition.class);
        when(client.getObjectDefinition(100)).thenReturn(definition);
        when(definition.getActions()).thenReturn(new String[]{"Open"});
        Rs2TileObjectModel model = new Rs2TileObjectModel(object);

        assertEquals(-1, model.resolveActionIndex("Open"));
        assertEquals(0, model.resolveActionIndex("Close"));
    }

    @Test
    public void definitionActionsNeverEscapeClientCallback() throws Exception
    {
        java.util.concurrent.atomic.AtomicBoolean inside = new java.util.concurrent.atomic.AtomicBoolean();
        ClientThread callbacks = new ClientThread()
        {
            @Override
            public <T> T invoke(java.util.function.Supplier<T> supplier)
            {
                inside.set(true);
                try { return supplier.get(); }
                finally { inside.set(false); }
            }
        };
        setStaticField(Microbot.class, "clientThread", callbacks);
        TileObject object = mock(TileObject.class);
        when(object.getId()).thenReturn(100);
        ObjectComposition definition = mock(ObjectComposition.class);
        when(client.getObjectDefinition(100)).thenReturn(definition);
        when(definition.getActions()).thenAnswer(call ->
        {
            assertTrue("ObjectComposition read outside client callback", inside.get());
            return new String[]{"Open"};
        });
        Rs2TileObjectModel model = new Rs2TileObjectModel(object);

        assertEquals("Open", model.getActions()[0]);
        assertEquals(0, model.resolveActionIndex("Open"));
        assertFalse(inside.get());
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
