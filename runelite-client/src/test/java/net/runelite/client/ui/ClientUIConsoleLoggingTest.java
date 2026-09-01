package net.runelite.client.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientUIConsoleLoggingTest
{
	@Test
	public void inAppConsoleUsesSingleStdoutIngestionPath() throws IOException
	{
		AtomicInteger stdoutRedirects = new AtomicInteger();
		AtomicBoolean attachesLoggerAppender = new AtomicBoolean();
		String systemOwner = Type.getInternalName(System.class);
		String loggerOwner = Type.getInternalName(ch.qos.logback.classic.Logger.class);

		try (InputStream stream = ClientUI.class.getResourceAsStream("ClientUI.class"))
		{
			assertTrue(stream != null);
			new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
			{
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions)
				{
					if (!name.equals("initializeConsoleLogging"))
					{
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9)
					{
						@Override
						public void visitMethodInsn(int opcode, String owner, String methodName,
							String methodDescriptor, boolean isInterface)
						{
							if (owner.equals(systemOwner) && methodName.equals("setOut"))
							{
								stdoutRedirects.incrementAndGet();
							}
							if (owner.equals(loggerOwner) && methodName.equals("addAppender"))
							{
								attachesLoggerAppender.set(true);
							}
						}
					};
				}
			}, 0);
		}

		assertEquals(1, stdoutRedirects.get());
		assertTrue("root logger appender would duplicate stdout events", !attachesLoggerAppender.get());
	}
}
