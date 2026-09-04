package net.runelite.client.plugins.microbot.util.walker;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.junit.Assert.assertEquals;
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
	public void directContinuationDelegatesBankCloseAndMovementToTheBehavioralGuard() throws IOException
	{
		AtomicBoolean methodFound = new AtomicBoolean();
		AtomicBoolean usesGuard = new AtomicBoolean();
		String walkerOwner = Type.getInternalName(Rs2Walker.class);

		visitMethodCalls("walkDirectAfterBankBootstrap", (owner, name) ->
		{
			methodFound.set(true);
			usesGuard.set(usesGuard.get()
				|| owner.equals(walkerOwner) && name.equals("continueDirectAfterBankBootstrap"));
		});

		assertTrue("direct-route continuation helper is required", methodFound.get());
		assertTrue("direct continuation must use the close-state guard", usesGuard.get());
	}

	@Test
	public void directContinuationAllowsMovementWhenBankIsAlreadyClosed()
	{
		AtomicInteger liveBankChecks = new AtomicInteger();
		AtomicInteger closeCalls = new AtomicInteger();
		AtomicInteger observedCloseChecks = new AtomicInteger();
		AtomicInteger movementCalls = new AtomicInteger();

		WalkerState state = continueDirect(() ->
		{
			liveBankChecks.incrementAndGet();
			return false;
		}, () ->
		{
			closeCalls.incrementAndGet();
			return true;
		}, () ->
		{
			observedCloseChecks.incrementAndGet();
			return true;
		}, () ->
		{
			movementCalls.incrementAndGet();
			return WalkerState.MOVING;
		});

		assertEquals(WalkerState.MOVING, state);
		assertEquals(1, liveBankChecks.get());
		assertEquals(0, closeCalls.get());
		assertEquals(0, observedCloseChecks.get());
		assertEquals(1, movementCalls.get());
	}

	@Test
	public void directContinuationClosesOpenBankAndAllowsOneMovementAfterObservedClose()
	{
		AtomicInteger closeCalls = new AtomicInteger();
		AtomicInteger observedCloseChecks = new AtomicInteger();
		AtomicInteger movementCalls = new AtomicInteger();

		WalkerState state = continueDirect(() -> true, () ->
		{
			closeCalls.incrementAndGet();
			return true;
		}, () ->
		{
			observedCloseChecks.incrementAndGet();
			return true;
		}, () ->
		{
			movementCalls.incrementAndGet();
			return WalkerState.ARRIVED;
		});

		assertEquals(WalkerState.ARRIVED, state);
		assertEquals(1, closeCalls.get());
		assertEquals(1, observedCloseChecks.get());
		assertEquals(1, movementCalls.get());
	}

	@Test
	public void directContinuationExitsWithoutMovementWhenCloseDispatchFails()
	{
		AtomicInteger observedCloseChecks = new AtomicInteger();
		AtomicInteger movementCalls = new AtomicInteger();

		WalkerState state = continueDirect(() -> true, () -> false, () ->
		{
			observedCloseChecks.incrementAndGet();
			return true;
		}, () ->
		{
			movementCalls.incrementAndGet();
			return WalkerState.MOVING;
		});

		assertEquals(WalkerState.EXIT, state);
		assertEquals(0, observedCloseChecks.get());
		assertEquals(0, movementCalls.get());
	}

	@Test
	public void directContinuationExitsWithoutMovementWhenBankRemainsOpen()
	{
		AtomicInteger movementCalls = new AtomicInteger();

		WalkerState state = continueDirect(() -> true, () -> true, () -> false, () ->
		{
			movementCalls.incrementAndGet();
			return WalkerState.MOVING;
		});

		assertEquals(WalkerState.EXIT, state);
		assertEquals(0, movementCalls.get());
	}

	@Test
	public void directContinuationRechecksLiveBankStateOnRetryAfterFailedClose()
	{
		AtomicInteger liveBankChecks = new AtomicInteger();
		AtomicInteger closeCalls = new AtomicInteger();
		AtomicInteger movementCalls = new AtomicInteger();
		BooleanSupplier bankOpen = () -> liveBankChecks.getAndIncrement() == 0;
		Supplier<WalkerState> movement = () ->
		{
			movementCalls.incrementAndGet();
			return WalkerState.ARRIVED;
		};

		WalkerState first = continueDirect(bankOpen, () ->
		{
			closeCalls.incrementAndGet();
			return false;
		}, () -> true, movement);
		WalkerState retry = continueDirect(bankOpen, () ->
		{
			closeCalls.incrementAndGet();
			return false;
		}, () -> true, movement);

		assertEquals(WalkerState.EXIT, first);
		assertEquals(WalkerState.ARRIVED, retry);
		assertEquals(2, liveBankChecks.get());
		assertEquals(1, closeCalls.get());
		assertEquals(1, movementCalls.get());
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

	private static WalkerState continueDirect(BooleanSupplier bankOpen, BooleanSupplier closeBank,
		BooleanSupplier observedClosed, Supplier<WalkerState> movement)
	{
		return Rs2Walker.continueDirectAfterBankBootstrap(bankOpen, closeBank, observedClosed, movement);
	}

	@FunctionalInterface
	private interface CallVisitor
	{
		void visit(String owner, String name);
	}
}
