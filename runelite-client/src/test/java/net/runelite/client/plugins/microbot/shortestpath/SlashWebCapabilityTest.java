package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.microbot.Microbot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SlashWebCapabilityTest {
    private ItemManager originalItemManager;
    private ClientThread originalClientThread;
    private ItemManager itemManager;
    private ClientThread clientThread;

    @Before
    public void setUp() throws Exception {
        originalItemManager = Microbot.getItemManager();
        originalClientThread = Microbot.getClientThread();

        itemManager = mock(ItemManager.class);
        clientThread = mock(ClientThread.class);
        setMicrobotField("itemManager", itemManager);
        setMicrobotField("clientThread", clientThread);

        when(clientThread.runOnClientThreadOptional(any())).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Optional.ofNullable(callable.call());
        });
    }

    @After
    public void tearDown() throws Exception {
        setMicrobotField("itemManager", originalItemManager);
        setMicrobotField("clientThread", originalClientThread);
    }

    @Test
    public void dynamicSlashWeaponClassificationRunsThroughClientThread() {
        ItemEquipmentStats equipment = ItemEquipmentStats.builder()
                .slot(EquipmentInventorySlot.WEAPON.getSlotIdx())
                .aslash(4)
                .build();
        ItemStats stats = new ItemStats(true, 0, 0, equipment);
        when(itemManager.getItemStats(ItemID.BRONZE_SWORD)).thenReturn(stats);

        assertTrue(SlashWebCapability.containsSlashWeapon(Set.of(ItemID.BRONZE_SWORD)));

        verify(clientThread).runOnClientThreadOptional(any());
    }

    private static void setMicrobotField(String name, Object value) throws Exception {
        Field field = Microbot.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
