package net.runelite.client.plugins.microbot.util.player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Rs2PlayerNullSafetyTest
{
    private ClientThread clientThread;
    private Object previousClientThread;

    @Before
    public void setUp() throws Exception
    {
        clientThread = mock(ClientThread.class);
        when(clientThread.runOnClientThreadOptional(any())).thenAnswer(invocation ->
        {
            Callable<?> callable = invocation.getArgument(0);
            return Optional.ofNullable(callable.call());
        });
        previousClientThread = swapClientThread(clientThread);
    }

    @After
    public void tearDown() throws Exception
    {
        swapClientThread(previousClientThread);
    }

    @Test
    public void worldLocationReturnsNullWhenLocalPlayerDisappears() throws Exception
    {
        Method resolver = Arrays.stream(Rs2Player.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("worldLocationFromClient"))
                .findFirst()
                .orElse(null);
        assertNotNull("Rs2Player needs a directly testable null-safe location resolver", resolver);

        Client client = mock(Client.class);
        resolver.setAccessible(true);

        assertNull(resolver.invoke(null, client));
        verify(clientThread).runOnClientThreadOptional(any());
        verify(client).getLocalPlayer();
    }

    private static Object swapClientThread(Object value) throws Exception
    {
        Field field = Microbot.class.getDeclaredField("clientThread");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }
}
