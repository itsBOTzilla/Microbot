package net.runelite.client.plugins.microbot.api.actor;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

public class Rs2ActorModelContractTest
{
    @Test
    public void nullActorHasNoWorldView()
    {
        assertNull(new Rs2ActorModel(null).getWorldView());
    }

    @Test
    public void wrappersOfTheSameActorHaveStableIdentityEquality()
    {
        Actor actor = mock(Actor.class);
        Rs2ActorModel first = new Rs2ActorModel(actor);
        Rs2ActorModel second = new Rs2ActorModel(actor);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void wrappersOfDifferentActorsAreNotEqual()
    {
        assertNotEquals(new Rs2ActorModel(mock(Actor.class)), new Rs2ActorModel(mock(Actor.class)));
    }

    @Test
    public void differentWrapperTypesAreNotEqualEvenForTheSameActor()
    {
        NPC npc = mock(NPC.class);
        assertFalse(new Rs2ActorModel(npc).equals(new Rs2NpcModel(npc)));
    }

    @Test
    public void nullActorWrappersAreOnlyEqualToThemselves()
    {
        assertNotEquals(new Rs2ActorModel(null), new Rs2ActorModel(null));
    }
}
