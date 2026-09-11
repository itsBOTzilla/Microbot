package net.runelite.client.plugins.microbot.util.walker.transport;

import java.util.List;

public final class TransportDialogueOptions
{
    private TransportDialogueOptions()
    {
    }

    public static List<String> destinationAliases(String npcName, String displayInfo)
    {
        if ("Veos".equals(npcName) && "Port Piscarilius".equals(displayInfo))
        {
            return List.of(displayInfo, "Great Kourend");
        }
        return List.of(displayInfo);
    }
}
