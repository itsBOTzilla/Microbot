package net.runelite.client.plugins.microbot.util;

import net.runelite.api.Actor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

public class ActorModelContractTest
{
    @Test
    public void nullActorHasNoWorldView()
    {
        assertNull(new ActorModel(null).getWorldView());
    }

    @Test
    public void equalityUsesWrappedActorIdentity()
    {
        Actor actor = mock(Actor.class);
        ActorModel first = new ActorModel(actor);
        ActorModel second = new ActorModel(actor);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new ActorModel(mock(Actor.class)));
    }
}
