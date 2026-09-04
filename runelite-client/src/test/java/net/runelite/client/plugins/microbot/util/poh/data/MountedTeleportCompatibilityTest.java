package net.runelite.client.plugins.microbot.util.poh.data;

import net.runelite.api.DecorativeObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MountedTeleportCompatibilityTest {
    @Test
    public void existingPluginMethodDescriptorsRemainCompatible() throws Exception {
        for (Class<?> type : new Class<?>[]{MountedDigsite.class, MountedGlory.class,
                MountedMythical.class, MountedXerics.class}) {
            assertEquals(DecorativeObject.class, type.getMethod("getObject").getReturnType());
        }
        assertEquals(Integer[].class, MountedDigsite.class.getField("IDS").getType());
        assertEquals(Integer[].class, MountedXerics.class.getField("IDS").getType());
    }
}
