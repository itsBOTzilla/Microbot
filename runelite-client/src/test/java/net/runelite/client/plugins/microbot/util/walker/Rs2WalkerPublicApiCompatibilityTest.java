package net.runelite.client.plugins.microbot.util.walker;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;
import net.runelite.api.GameObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins the script-facing walker descriptors while the internal engine is replaced. */
public class Rs2WalkerPublicApiCompatibilityTest
{
    @Test
    public void preservesScriptFacingWalkingDescriptors() throws Exception
    {
        assertPublicStatic("walkTo", boolean.class, int.class, int.class, int.class);
        assertPublicStatic("walkTo", boolean.class,
                int.class, int.class, int.class, int.class);
        assertPublicStatic("walkTo", boolean.class, WorldPoint.class);
        assertPublicStatic("walkTo", boolean.class, WorldPoint.class, int.class);
        assertPublicStatic("walkUntil", boolean.class,
                WorldPoint.class, int.class, BooleanSupplier.class);
        assertPublicStatic("walkWithStateUntil", WalkerState.class,
                WorldPoint.class, int.class, BooleanSupplier.class);
        assertPublicStatic("walkWithState", WalkerState.class, WorldPoint.class);
        assertPublicStatic("walkWithState", WalkerState.class, WorldPoint.class, int.class);
        assertPublicStatic("walkWithStateTry", WalkerState.class,
                WorldPoint.class, int.class, long.class);
        assertPublicStatic("walkStep", WalkerState.class, WorldPoint.class, int.class);

        assertPublicStatic("walkWithBankedTransports", boolean.class, WorldPoint.class);
        assertPublicStatic("walkWithBankedTransports", boolean.class,
                WorldPoint.class, boolean.class);
        assertPublicStatic("walkWithBankedTransports", boolean.class,
                WorldPoint.class, int.class, boolean.class);
        assertPublicStatic("walkWithBankedTransportsAndState", WalkerState.class,
                WorldPoint.class, int.class, boolean.class);

        assertPublicStatic("walkNextTo", boolean.class, GameObject.class);
        assertPublicStatic("walkNextToInstance", void.class, GameObject.class);
        assertPublicStatic("walkMiniMap", boolean.class, WorldPoint.class);
        assertPublicStatic("walkMiniMap", boolean.class, WorldPoint.class, double.class);
        assertPublicStatic("walkFastLocal", void.class, LocalPoint.class);
        assertPublicStatic("walkFastCanvas", boolean.class, WorldPoint.class);
        assertPublicStatic("walkFastCanvas", boolean.class, WorldPoint.class, boolean.class);
        assertPublicStatic("walkCanvas", WorldPoint.class, WorldPoint.class);

        assertPublicStatic("getCurrentTarget", WorldPoint.class);
        assertPublicStatic("setTarget", void.class, WorldPoint.class);
        assertPublicStatic("setTarget", void.class, WorldPoint.class, String.class);
        assertPublicStatic("clearWalkingRoute", void.class, String.class);
        assertPublicStatic("recalculatePath", void.class);
    }

    private static void assertPublicStatic(String name, Class<?> returnType,
                                           Class<?>... parameters) throws Exception
    {
        Method method = Rs2Walker.class.getDeclaredMethod(name, parameters);
        assertTrue(name + " must remain public", Modifier.isPublic(method.getModifiers()));
        assertTrue(name + " must remain static", Modifier.isStatic(method.getModifiers()));
        assertEquals(name + " return type changed", returnType, method.getReturnType());
    }
}
