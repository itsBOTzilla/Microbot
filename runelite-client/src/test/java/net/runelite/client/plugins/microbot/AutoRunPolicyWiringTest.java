package net.runelite.client.plugins.microbot;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoRunPolicyWiringTest
{
	private static final String MICROBOT_OWNER =
		"net/runelite/client/plugins/microbot/Microbot";
	private static final String PLAYER_OWNER =
		"net/runelite/client/plugins/microbot/util/player/Rs2Player";

	@Test
	public void scriptUsesSharedPolicyAsItsOnlyRunTogglePath() throws Exception
	{
		assertSharedPolicyWiring(Script.class, "run");
	}

	@Test
	public void walkerUsesSharedPolicyAsItsOnlyRunTogglePath() throws Exception
	{
		assertSharedPolicyWiring(Rs2Walker.class, "manageRunEnergy");
	}

	private static void assertSharedPolicyWiring(Class<?> owner, String methodName) throws Exception
	{
		List<String> calls = methodCalls(owner, methodName);

		assertTrue(calls.contains(MICROBOT_OWNER + ".shouldEnableAutoRun"));
		assertTrue(calls.contains(MICROBOT_OWNER + ".onAutoRunEnabled"));
		assertEquals(1, calls.stream()
			.filter((call) -> call.equals(PLAYER_OWNER + ".toggleRunEnergy"))
			.count());
		assertTrue(calls.indexOf(MICROBOT_OWNER + ".shouldEnableAutoRun")
			< calls.indexOf(PLAYER_OWNER + ".toggleRunEnergy"));
		assertTrue(calls.indexOf(PLAYER_OWNER + ".toggleRunEnergy")
			< calls.indexOf(MICROBOT_OWNER + ".onAutoRunEnabled"));
	}

	private static List<String> methodCalls(Class<?> owner, String methodName) throws Exception
	{
		List<String> calls = new ArrayList<>();
		String resourceName = "/" + owner.getName().replace('.', '/') + ".class";
		try (InputStream input = owner.getResourceAsStream(resourceName))
		{
			if (input == null)
			{
				throw new IllegalStateException("Missing class resource " + resourceName);
			}
			new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9)
			{
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions)
				{
					if (!name.equals(methodName))
					{
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9)
					{
						@Override
						public void visitMethodInsn(int opcode, String invokedOwner,
							String invokedName, String invokedDescriptor, boolean isInterface)
						{
							calls.add(invokedOwner + "." + invokedName);
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
		}
		return calls;
	}
}
