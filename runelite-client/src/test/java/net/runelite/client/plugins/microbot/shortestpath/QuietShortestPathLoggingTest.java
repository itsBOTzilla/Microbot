package net.runelite.client.plugins.microbot.shortestpath;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shortestpath.components.CheckboxPanel;
import net.runelite.client.plugins.microbot.shortestpath.pathfinder.PathfinderConfig;
import net.runelite.client.plugins.microbot.util.events.WelcomeScreenEvent;
import net.runelite.client.plugins.microbot.util.walker.WebWalkLog;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class QuietShortestPathLoggingTest
{
	@Test
	public void absentOptionalPohCheckboxesDefaultQuietly() throws Exception
	{
		ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getConfiguration(
			ShortestPathPlugin.CONFIG_GROUP, "mountedGlory", Boolean.class)).thenReturn(null);
		Object previousConfigManager = swapMicrobotStatic("configManager", configManager);
		Logger logger = (Logger) LoggerFactory.getLogger(Microbot.class);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);

		try
		{
			CheckboxPanel panel = new CheckboxPanel();
			assertTrue(panel.getTeleports().isEmpty());
			assertFalse(events.list.stream().anyMatch(event ->
				event.getFormattedMessage().startsWith("Failed to poh checkbox config")));
		}
		finally
		{
			logger.detachAppender(events);
			events.stop();
			swapMicrobotStatic("configManager", previousConfigManager);
		}
	}

	@Test
	public void absentOptionalPohExitTileDefaultsQuietly() throws Exception
	{
		ConfigManager configManager = mock(ConfigManager.class);
		Object previousConfigManager = swapMicrobotStatic("configManager", configManager);
		PohPanel previousInstance = PohPanel.instance;
		Logger logger = (Logger) LoggerFactory.getLogger(Microbot.class);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);

		try
		{
			new PohPanel(mock(ShortestPathConfig.class));
			assertTrue(PohPanel.getAvailableTransports(new HashMap<>()).isEmpty());
			assertFalse(events.list.stream().anyMatch(event ->
				event.getFormattedMessage().equals("Failed to load exit portal config")));
		}
		finally
		{
			logger.detachAppender(events);
			events.stop();
			PohPanel.instance = previousInstance;
			swapMicrobotStatic("configManager", previousConfigManager);
		}
	}

	@Test
	public void initialTransportCacheMissUsesDebugLogging() throws IOException
	{
		AtomicBoolean cacheMissUsesDebug = new AtomicBoolean();
		AtomicBoolean cacheMissUsesInfo = new AtomicBoolean();
		String webWalkLogOwner = Type.getInternalName(WebWalkLog.class);

		try (InputStream stream = PathfinderConfig.class.getResourceAsStream("PathfinderConfig.class"))
		{
			assertTrue(stream != null);
			new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
			{
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions)
				{
					if (!name.equals("refreshTransports"))
					{
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9)
					{
						private boolean cacheMissMessage;

						@Override
						public void visitLdcInsn(Object value)
						{
							if (value instanceof String
								&& ((String) value).contains("refresh_transports cache_miss"))
							{
								cacheMissMessage = true;
							}
						}

						@Override
						public void visitMethodInsn(int opcode, String owner, String methodName,
							String methodDescriptor, boolean isInterface)
						{
							if (!cacheMissMessage || !owner.equals(webWalkLogOwner))
							{
								return;
							}
							cacheMissUsesDebug.set(methodName.equals("cfg"));
							cacheMissUsesInfo.set(methodName.equals("cfgSlow"));
							cacheMissMessage = false;
						}
					};
				}
			}, 0);
		}

		assertTrue(cacheMissUsesDebug.get());
		assertFalse(cacheMissUsesInfo.get());
	}

	@Test
	public void liveCollisionComparisonUsesDebugLogging() throws IOException
	{
		AtomicBoolean invokesDebug = new AtomicBoolean();
		AtomicBoolean invokesInfo = new AtomicBoolean();
		String webWalkLogOwner = Type.getInternalName(WebWalkLog.class);

		visitMethodCalls(ShortestPathPlugin.class, "logLiveStaticConflicts",
			(owner, name) ->
			{
				if (owner.equals(webWalkLogOwner))
				{
					invokesDebug.set(invokesDebug.get() || name.equals("spDebug"));
					invokesInfo.set(invokesInfo.get() || name.equals("spInfo"));
				}
			});

		assertTrue(invokesDebug.get());
		assertFalse(invokesInfo.get());
	}

	@Test
	public void welcomeScreenPollingHasNoInfoLogging() throws IOException
	{
		AtomicBoolean invokesInfo = new AtomicBoolean();
		String loggerOwner = Type.getInternalName(org.slf4j.Logger.class);

		visitMethodCalls(WelcomeScreenEvent.class, null,
			(owner, name) -> invokesInfo.set(invokesInfo.get()
				|| owner.equals(loggerOwner) && name.equals("info")));

		assertFalse(invokesInfo.get());
	}

	@Test
	public void routineWalkerProgressUsesDebugLogging() throws IOException
	{
		assertDebugOnly(WebWalkLog.class, "movementDispatch");
		assertDebugOnly(WebWalkLog.class, "checkpointReleased");
		assertDebugOnly(WebWalkLog.class, "tmark");
	}

	private static void assertDebugOnly(Class<?> type, String targetMethod) throws IOException
	{
		AtomicBoolean invokesDebug = new AtomicBoolean();
		AtomicBoolean invokesInfo = new AtomicBoolean();
		String loggerOwner = Type.getInternalName(org.slf4j.Logger.class);

		visitMethodCalls(type, targetMethod, (owner, name) ->
		{
			if (owner.equals(loggerOwner))
			{
				invokesDebug.set(invokesDebug.get() || name.equals("debug"));
				invokesInfo.set(invokesInfo.get() || name.equals("info"));
			}
		});

		assertTrue(targetMethod + " must remain available when verbose logging is enabled", invokesDebug.get());
		assertFalse(targetMethod + " is routine progress and must not fill the normal console", invokesInfo.get());
	}

	private static Object swapMicrobotStatic(String name, Object value) throws Exception
	{
		Field field = Microbot.class.getDeclaredField(name);
		field.setAccessible(true);
		Object previous = field.get(null);
		field.set(null, value);
		return previous;
	}

	private static void visitMethodCalls(Class<?> type, String targetMethod, CallVisitor visitor)
		throws IOException
	{
		try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class"))
		{
			assertTrue(stream != null);
			new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
			{
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions)
				{
					if (targetMethod != null && !targetMethod.equals(name))
					{
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9)
					{
						@Override
						public void visitMethodInsn(int opcode, String owner, String methodName,
							String methodDescriptor, boolean isInterface)
						{
							visitor.visit(owner, methodName);
						}
					};
				}
			}, 0);
		}
	}

	@FunctionalInterface
	private interface CallVisitor
	{
		void visit(String owner, String name);
	}
}
