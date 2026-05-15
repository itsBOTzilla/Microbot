package net.runelite.client.plugins.microbot.util.inventory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcCache;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class Rs2InventoryNpcTargetTest {
    @Test
    public void selectedInventoryItemKeepsNpcAndObjectTargetOpcodes() throws Exception {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        when(client.isWidgetSelected()).thenReturn(true);
        ClientThread callback = new ClientThread();
        field(ClientThread.class, "client").set(callback, client);
        Field clientField = field(Microbot.class, "client");
        Field threadField = field(Microbot.class, "clientThread");
        Object previousClient = clientField.get(null);
        Object previousThread = threadField.get(null);
        try {
            clientField.set(null, client);
            threadField.set(null, callback);
            java.lang.reflect.Method npcAction = Rs2NpcModel.class.getDeclaredMethod("getMenuAction", int.class);
            npcAction.setAccessible(true);
            assertEquals(net.runelite.api.MenuAction.WIDGET_TARGET_ON_NPC,
                    npcAction.invoke(new Rs2NpcModel(mock(net.runelite.api.NPC.class)), 0));

            net.runelite.api.GameObject raw = mock(net.runelite.api.GameObject.class);
            when(raw.getId()).thenReturn(100);
            when(raw.getLocalLocation()).thenReturn(new net.runelite.api.coords.LocalPoint(1536, 2816));
            when(raw.sizeX()).thenReturn(1);
            when(raw.sizeY()).thenReturn(1);
            WorldView view = mock(WorldView.class);
            when(view.getId()).thenReturn(WorldView.TOPLEVEL);
            when(raw.getWorldView()).thenReturn(view);
            net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel model =
                    new net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel(raw);
            java.lang.reflect.Method objectClick = model.getClass().getDeclaredMethod(
                    "clickWithIdentifier", String.class, int.class, boolean.class, boolean.class);
            objectClick.setAccessible(true);
            assertEquals(true, objectClick.invoke(model, null, 100, true, false));
            verify(client).menuAction(12, 22, net.runelite.api.MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
                    100, -1, "", "");
        } finally {
            clientField.set(null, previousClient);
            threadField.set(null, previousThread);
        }
    }

    @Test
    public void selectedItemCanTargetAnNpcBehindACounter() throws Exception {
        Client client = mock(Client.class);
        Player player = mock(Player.class);
        WorldView worldView = mock(WorldView.class);
        when(client.isClientThread()).thenReturn(true);
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
        when(player.getWorldView()).thenReturn(worldView);
        ClientThread callback = new ClientThread();
        field(ClientThread.class, "client").set(callback, client);
        Rs2NpcCache cache = Guice.createInjector(new AbstractModule() {
            @Override protected void configure() {
                bind(Client.class).toInstance(client);
                bind(ClientThread.class).toInstance(callback);
            }
        }).getInstance(Rs2NpcCache.class);
        Rs2NpcModel counterNpc = mock(Rs2NpcModel.class);
        when(counterNpc.getId()).thenReturn(123);
        when(counterNpc.getIndex()).thenReturn(17);
        when(counterNpc.getWorldLocation()).thenReturn(new WorldPoint(3201, 3200, 0));
        when(counterNpc.isReachable()).thenReturn(false);
        when(counterNpc.click()).thenReturn(true);
        field(Rs2NpcCache.class, "npcs").set(cache, Arrays.asList(counterNpc));

        Field clientField = field(Microbot.class, "client");
        Field threadField = field(Microbot.class, "clientThread");
        Field cacheField = field(Microbot.class, "rs2NpcCache");
        Object previousClient = clientField.get(null);
        Object previousThread = threadField.get(null);
        Object previousCache = cacheField.get(null);
        try {
            clientField.set(null, client);
            threadField.set(null, callback);
            cacheField.set(null, cache);
            assertTrue(Rs2Inventory.clickSelectedItemOnNpc(npc -> npc.getId() == 123));
            assertTrue(Rs2Inventory.clickSelectedItemOnNpc(npc -> npc.getIndex() == 17));
            assertFalse(Rs2Inventory.clickSelectedItemOnNpc(npc -> npc.getIndex() == 99));
            verify(counterNpc, times(2)).click();
            verify(counterNpc, never()).isReachable();
        } finally {
            clientField.set(null, previousClient);
            threadField.set(null, previousThread);
            cacheField.set(null, previousCache);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
