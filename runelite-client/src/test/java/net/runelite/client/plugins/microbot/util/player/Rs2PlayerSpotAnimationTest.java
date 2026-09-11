package net.runelite.client.plugins.microbot.util.player;

import net.runelite.api.Player;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Rs2PlayerSpotAnimationTest
{
    @Test
    public void missingLocalPlayerHasNoSpotAnimation()
    {
        assertFalse(Rs2Player.hasSpotAnimation(null, 245));
    }

    @Test
    public void delegatesToPresentLocalPlayer()
    {
        Player player = mock(Player.class);
        when(player.hasSpotAnim(245)).thenReturn(true);
        assertTrue(Rs2Player.hasSpotAnimation(player, 245));
    }
}
