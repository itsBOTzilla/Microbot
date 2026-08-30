package net.runelite.client.plugins.microbot.questhelper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.plugins.microbot.questhelper.helpers.quests.thebloodmoonrises.GildedBookPuzzle;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertTrue;

public class QuestStepInheritanceWiringTest
{
	@Test
	public void detailedStepPreservesCutsceneTracking() throws IOException
	{
		assertCallsQuestStepSuper(DetailedQuestStep.class, "onVarbitChanged",
			Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(VarbitChanged.class)));
	}

	@Test
	public void gildedBookPreservesLifecycleAndCutsceneTracking() throws IOException
	{
		assertCallsQuestStepSuper(GildedBookPuzzle.class, "startUp", "()V");
		assertCallsQuestStepSuper(GildedBookPuzzle.class, "onVarbitChanged",
			Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(VarbitChanged.class)));
	}

	private static void assertCallsQuestStepSuper(Class<?> type, String methodName, String descriptor)
		throws IOException
	{
		AtomicBoolean found = new AtomicBoolean(false);
		try (InputStream input = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class"))
		{
			assertTrue(input != null);
			new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9)
			{
				@Override
				public MethodVisitor visitMethod(int access, String name, String desc, String signature,
					String[] exceptions)
				{
					if (!methodName.equals(name) || !descriptor.equals(desc))
					{
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9)
					{
						@Override
						public void visitMethodInsn(int opcode, String owner, String invokedName,
							String invokedDescriptor, boolean isInterface)
						{
							if (opcode == Opcodes.INVOKESPECIAL
								&& Type.getInternalName(QuestStep.class).equals(owner)
								&& methodName.equals(invokedName)
								&& descriptor.equals(invokedDescriptor))
							{
								found.set(true);
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
		}
		assertTrue(type.getSimpleName() + '.' + methodName + " must delegate to QuestStep", found.get());
	}
}
