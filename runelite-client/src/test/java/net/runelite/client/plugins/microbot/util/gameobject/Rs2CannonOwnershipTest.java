package net.runelite.client.plugins.microbot.util.gameobject;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.Callable;
import net.runelite.api.GameObject;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.cannon.CannonPlugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class Rs2CannonOwnershipTest {
    @Test
    public void ownershipUsesRawCenterRatherThanQueryModelSouthwestTile() throws Exception {
        Field position = CannonPlugin.class.getDeclaredField("cannonPosition");
        position.setAccessible(true);
        Object previousPosition = position.get(null);
        Field thread = Microbot.class.getDeclaredField("clientThread");
        thread.setAccessible(true);
        Object previousThread = thread.get(null);
        try {
            thread.set(null, new ClientThread() {
                @Override
                public <T> Optional<T> runOnClientThreadOptional(Callable<T> callable) {
                    try { return Optional.ofNullable(callable.call()); }
                    catch (Exception ex) { throw new AssertionError(ex); }
                }
            });
            GameObject raw = mock(GameObject.class);
            Rs2TileObjectModel model = mock(Rs2TileObjectModel.class);
            when(model.getTileObject()).thenReturn(raw);
            when(raw.getWorldLocation()).thenReturn(new WorldPoint(3201, 3201, 0));
            when(model.getWorldLocation()).thenReturn(new WorldPoint(3200, 3200, 0));
            position.set(null, new WorldArea(3200, 3200, 3, 3, 0));

            assertTrue(Rs2Cannon.isOwnCannon(model));
            verify(model, never()).getWorldLocation();
            position.set(null, new WorldArea(3199, 3199, 3, 3, 0));
            assertFalse(Rs2Cannon.isOwnCannon(model));
            position.set(null, null);
            assertFalse(Rs2Cannon.isOwnCannon(model));
        } finally {
            position.set(null, previousPosition);
            thread.set(null, previousThread);
        }
    }
}
