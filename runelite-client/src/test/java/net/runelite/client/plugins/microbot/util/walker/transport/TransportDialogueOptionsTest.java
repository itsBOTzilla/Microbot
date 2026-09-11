package net.runelite.client.plugins.microbot.util.walker.transport;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TransportDialogueOptionsTest
{
    @Test
    public void veosPiscariliusRouteAcceptsTheGreatKourendDialogueWording()
    {
        assertEquals(
                List.of("Port Piscarilius", "Great Kourend"),
                TransportDialogueOptions.destinationAliases("Veos", "Port Piscarilius"));
    }

    @Test
    public void ordinaryDestinationsKeepTheirConfiguredDisplayName()
    {
        assertEquals(
                List.of("Port Sarim"),
                TransportDialogueOptions.destinationAliases("Veos", "Port Sarim"));
    }
}
