package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Rs2WalkerBankBootstrapTest
{
	@Test
	public void bootstrapLeavesBankOpenForImmediateWithdrawal() throws IOException
	{
		AtomicBoolean opensBank = new AtomicBoolean();
		AtomicBoolean closesBank = new AtomicBoolean();
		String bankOwner = Type.getInternalName(Rs2Bank.class);

		visitMethodCalls("bootstrapBankMirrorForBankedPathing", (owner, name) ->
		{
			if (owner.equals(bankOwner))
			{
				opensBank.set(opensBank.get() || name.equals("openBank"));
				closesBank.set(closesBank.get() || name.equals("closeBank"));
			}
		});

		assertTrue("bootstrap must still open the bank to populate the live mirror", opensBank.get());
		assertFalse("closing here forces the withdrawal workflow to reopen the same bank", closesBank.get());
	}

	@Test
	public void directContinuationClosesBootstrapBankBeforeWalking() throws IOException
	{
		AtomicBoolean methodFound = new AtomicBoolean();
		AtomicInteger callIndex = new AtomicInteger();
		AtomicInteger closesBankAt = new AtomicInteger(-1);
		AtomicInteger continuesWalkAt = new AtomicInteger(-1);
		String bankOwner = Type.getInternalName(Rs2Bank.class);
		String walkerOwner = Type.getInternalName(Rs2Walker.class);

		visitMethodCalls("walkDirectAfterBankBootstrap", (owner, name) ->
		{
			methodFound.set(true);
			int index = callIndex.getAndIncrement();
			if (owner.equals(bankOwner) && name.equals("closeBank"))
			{
				closesBankAt.compareAndSet(-1, index);
			}
			if (owner.equals(walkerOwner) && name.equals("walkWithStateInternal"))
			{
				continuesWalkAt.compareAndSet(-1, index);
			}
		});

		assertTrue("direct-route continuation helper is required", methodFound.get());
		assertTrue("a bank left open for comparison must be closed before direct movement", closesBankAt.get() >= 0);
		assertTrue("the direct route must continue after closing the bootstrap bank", continuesWalkAt.get() >= 0);
		assertTrue("bank closure must be dispatched before direct movement",
			closesBankAt.get() < continuesWalkAt.get());
	}

	@Test
	public void everyDirectSelectionUsesTheBankCloseGuard() throws IOException
	{
		AtomicInteger guardedBranches = new AtomicInteger();
		AtomicBoolean bypassesGuard = new AtomicBoolean();
		String walkerOwner = Type.getInternalName(Rs2Walker.class);

		visitMethodCalls("walkWithBankedTransportsAndStateLocked", (owner, name) ->
		{
			if (!owner.equals(walkerOwner))
			{
				return;
			}
			if (name.equals("walkDirectAfterBankBootstrap"))
			{
				guardedBranches.incrementAndGet();
			}
			if (name.equals("walkWithStateInternal"))
			{
				bypassesGuard.set(true);
			}
		});

		assertTrue("all four direct route selections must use the close guard", guardedBranches.get() >= 4);
		assertFalse("direct selection must not bypass the bank close guard", bypassesGuard.get());
	}

	private static void visitMethodCalls(String targetMethod, CallVisitor visitor) throws IOException
	{
		try (InputStream stream = Rs2Walker.class.getResourceAsStream("Rs2Walker.class"))
		{
			assertTrue(stream != null);
			new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9)
			{
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions)
				{
					if (!targetMethod.equals(name))
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
