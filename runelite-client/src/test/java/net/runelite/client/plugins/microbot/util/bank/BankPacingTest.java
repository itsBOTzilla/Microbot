package net.runelite.client.plugins.microbot.util.bank;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BankPacingTest
{
    @After
    public void clearInterruptedFlag()
    {
        Thread.interrupted();
    }

    @Test
    public void phasesUseTheApprovedRangesInOrder()
    {
        Fixture fixture = new Fixture(50, 700, 500, 800, 600);
        fixture.pacing.beginSession();
        long sessionToken = fixture.pacing.sessionToken();

        Assert.assertTrue(fixture.pacing.awaitBeforeAction());
        Assert.assertTrue(fixture.pacing.awaitBeforeAction());
        Assert.assertTrue(fixture.pacing.awaitBeforeClose(sessionToken));
        Assert.assertTrue(fixture.pacing.awaitAfterClose(sessionToken));

        Assert.assertEquals(Arrays.asList(
                new Range(1, 100),
                new Range(450, 1400),
                new Range(250, 950),
                new Range(450, 1350),
                new Range(300, 1100)
        ), fixture.random.requestedRanges);
        Assert.assertEquals(Arrays.asList(700L, 500L, 800L, 600L), fixture.sleeper.requestedSleeps);
        Assert.assertFalse(fixture.pacing.isSessionActive());
    }

    @Test
    public void earlyWakeupKeepsTheOriginalDeadlineAndSample()
    {
        Fixture fixture = new Fixture(50, 1000);
        fixture.sleeper.firstWakeAdvanceMillis = 400;
        fixture.pacing.beginSession();

        Assert.assertTrue(fixture.pacing.awaitBeforeAction());

        Assert.assertEquals(Arrays.asList(1000L, 600L), fixture.sleeper.requestedSleeps);
        Assert.assertEquals(Arrays.asList(
                new Range(1, 100),
                new Range(450, 1400)
        ), fixture.random.requestedRanges);
    }

    @Test
    public void aSessionGetsAtMostOneLongHesitation()
    {
        Fixture fixture = new Fixture(1, 0, 500, 3000, 300, 600, 400);
        fixture.pacing.beginSession();
        long sessionToken = fixture.pacing.sessionToken();

        Assert.assertTrue(fixture.pacing.awaitBeforeAction());
        Assert.assertTrue(fixture.pacing.awaitBeforeAction());
        Assert.assertTrue(fixture.pacing.awaitBeforeClose(sessionToken));
        Assert.assertTrue(fixture.pacing.awaitAfterClose(sessionToken));

        Assert.assertEquals(Arrays.asList(3500L, 300L, 600L, 400L), fixture.sleeper.requestedSleeps);
        Assert.assertEquals(1, fixture.random.requestedRanges.stream()
                .filter(range -> range.equals(new Range(2500, 4000)))
                .count());
    }

    @Test
    public void resetStartsTheNextSessionAtTheFirstActionPhase()
    {
        Fixture fixture = new Fixture(50, 650, 50, 900);
        fixture.pacing.beginSession();
        Assert.assertTrue(fixture.pacing.awaitBeforeAction());

        fixture.pacing.reset();
        fixture.pacing.beginSession();
        Assert.assertTrue(fixture.pacing.awaitBeforeAction());

        Assert.assertEquals(Arrays.asList(650L, 900L), fixture.sleeper.requestedSleeps);
        Assert.assertEquals(2, fixture.random.requestedRanges.stream()
                .filter(range -> range.equals(new Range(450, 1400)))
                .count());
    }

    @Test
    public void interruptionCancelsAndResetsTheSession()
    {
        Fixture fixture = new Fixture(50, 750);
        fixture.sleeper.interruptNextSleep = true;
        fixture.pacing.beginSession();

        Assert.assertFalse(fixture.pacing.awaitBeforeAction());

        Assert.assertTrue(Thread.currentThread().isInterrupted());
        Assert.assertFalse(fixture.pacing.isSessionActive());
    }

    @Test
    public void resetDoesNotBlockBehindAnInFlightWait() throws Exception
    {
        FakeClock clock = new FakeClock();
        ScriptedRandom random = new ScriptedRandom(50, 700);
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch releaseSleep = new CountDownLatch(1);
        CountDownLatch resetFinished = new CountDownLatch(1);
        AtomicBoolean actionResult = new AtomicBoolean(true);
        BankPacing pacing = new BankPacing(random, clock, millis -> {
            sleeping.countDown();
            releaseSleep.await();
            clock.nowMillis += millis;
        });
        pacing.beginSession();

        Thread actionThread = new Thread(() -> actionResult.set(pacing.awaitBeforeAction()),
                "bank-pacing-action-test");
        actionThread.start();
        Assert.assertTrue("action should enter its wait", sleeping.await(1, TimeUnit.SECONDS));

        Thread resetThread = new Thread(() -> {
            pacing.reset();
            resetFinished.countDown();
        }, "bank-pacing-reset-test");
        resetThread.start();
        boolean resetWasImmediate = resetFinished.await(1, TimeUnit.SECONDS);

        releaseSleep.countDown();
        actionThread.join(1000);
        resetThread.join(1000);

        Assert.assertTrue("reset must not wait behind a pacing sleep", resetWasImmediate);
        Assert.assertFalse("the reset session must not dispatch its pending action", actionResult.get());
        Assert.assertFalse(pacing.isSessionActive());
    }

    @Test
    public void clientThreadActionInvalidatesAnOlderWaitAndAdvancesThePhase() throws Exception
    {
        FakeClock clock = new FakeClock();
        ScriptedRandom random = new ScriptedRandom(50, 700, 400);
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch releaseSleep = new CountDownLatch(1);
        AtomicBoolean delayedActionResult = new AtomicBoolean(true);
        BankPacing pacing = new BankPacing(random, clock, millis -> {
            sleeping.countDown();
            releaseSleep.await();
            clock.nowMillis += millis;
        });
        pacing.beginSession();

        Thread delayedAction = new Thread(() -> delayedActionResult.set(pacing.awaitBeforeAction()),
                "bank-pacing-delayed-action-test");
        delayedAction.start();
        Assert.assertTrue(sleeping.await(1, TimeUnit.SECONDS));

        Assert.assertTrue(pacing.recordActionWithoutWait());
        releaseSleep.countDown();
        delayedAction.join(1000);

        Assert.assertFalse("the superseded background action must not dispatch", delayedActionResult.get());
        Assert.assertTrue("the next action must use the between-actions phase", pacing.awaitBeforeAction());
        Assert.assertTrue(random.requestedRanges.contains(new Range(250, 950)));
    }

    @Test
    public void completedCloseCannotResetANewerSession()
    {
        Fixture fixture = new Fixture(50, 600, 50, 700);
        fixture.pacing.beginSession();
        long closingSession = fixture.pacing.sessionToken();
        Assert.assertTrue(fixture.pacing.awaitBeforeClose(closingSession));

        fixture.pacing.reset();
        fixture.pacing.beginSession();
        long newerSession = fixture.pacing.sessionToken();

        Assert.assertNotEquals(closingSession, newerSession);
        Assert.assertFalse(fixture.pacing.awaitAfterClose(closingSession));
        Assert.assertTrue(fixture.pacing.isSessionActive());
        Assert.assertEquals(newerSession, fixture.pacing.sessionToken());
        Assert.assertTrue(fixture.pacing.awaitBeforeAction());
    }

    @Test
    public void interruptedStaleWaiterCannotResetANewerSession() throws Exception
    {
        FakeClock clock = new FakeClock();
        ScriptedRandom random = new ScriptedRandom(50, 700, 50);
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch releaseSleep = new CountDownLatch(1);
        AtomicBoolean staleActionResult = new AtomicBoolean(true);
        BankPacing pacing = new BankPacing(random, clock, millis -> {
            sleeping.countDown();
            releaseSleep.await();
            clock.nowMillis += millis;
        });
        pacing.beginSession();

        Thread staleWaiter = new Thread(() -> staleActionResult.set(pacing.awaitBeforeAction()),
                "bank-pacing-stale-interrupt-test");
        staleWaiter.start();
        Assert.assertTrue(sleeping.await(1, TimeUnit.SECONDS));

        pacing.reset();
        pacing.beginSession();
        long newerSession = pacing.sessionToken();
        staleWaiter.interrupt();
        staleWaiter.join(1000);
        releaseSleep.countDown();

        Assert.assertFalse(staleActionResult.get());
        Assert.assertTrue("the stale interruption must not cancel the newer session", pacing.isSessionActive());
        Assert.assertEquals(newerSession, pacing.sessionToken());
    }

    private static final class Fixture
    {
        private final FakeClock clock = new FakeClock();
        private final ScriptedRandom random;
        private final FakeSleeper sleeper = new FakeSleeper(clock);
        private final BankPacing pacing;

        private Fixture(int... samples)
        {
            random = new ScriptedRandom(samples);
            pacing = new BankPacing(random, clock, sleeper);
        }
    }

    private static final class FakeClock implements BankPacing.MonotonicClock
    {
        private long nowMillis;

        @Override
        public long nowMillis()
        {
            return nowMillis;
        }
    }

    private static final class FakeSleeper implements BankPacing.Sleeper
    {
        private final FakeClock clock;
        private final List<Long> requestedSleeps = new ArrayList<>();
        private long firstWakeAdvanceMillis = -1;
        private boolean interruptNextSleep;

        private FakeSleeper(FakeClock clock)
        {
            this.clock = clock;
        }

        @Override
        public void sleep(long millis) throws InterruptedException
        {
            requestedSleeps.add(millis);
            if (interruptNextSleep)
            {
                interruptNextSleep = false;
                throw new InterruptedException("test interruption");
            }
            if (firstWakeAdvanceMillis >= 0)
            {
                clock.nowMillis += firstWakeAdvanceMillis;
                firstWakeAdvanceMillis = -1;
                return;
            }
            clock.nowMillis += millis;
        }
    }

    private static final class ScriptedRandom implements BankPacing.RandomSource
    {
        private final Deque<Integer> samples = new ArrayDeque<>();
        private final List<Range> requestedRanges = new ArrayList<>();

        private ScriptedRandom(int... samples)
        {
            for (int sample : samples)
            {
                this.samples.add(sample);
            }
        }

        @Override
        public int betweenInclusive(int minimum, int maximum)
        {
            requestedRanges.add(new Range(minimum, maximum));
            Assert.assertFalse("missing scripted random sample for " + minimum + "-" + maximum,
                    samples.isEmpty());
            int sample = samples.removeFirst();
            Assert.assertTrue("sample below requested range", sample >= minimum);
            Assert.assertTrue("sample above requested range", sample <= maximum);
            return sample;
        }
    }

    private static final class Range
    {
        private final int minimum;
        private final int maximum;

        private Range(int minimum, int maximum)
        {
            this.minimum = minimum;
            this.maximum = maximum;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Range))
            {
                return false;
            }
            Range range = (Range) other;
            return minimum == range.minimum && maximum == range.maximum;
        }

        @Override
        public int hashCode()
        {
            return 31 * minimum + maximum;
        }

        @Override
        public String toString()
        {
            return minimum + "-" + maximum;
        }
    }
}
