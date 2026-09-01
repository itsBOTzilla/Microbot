package net.runelite.client.plugins.microbot.util.bank;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Rs2BankPacingWiringTest
{
    private static final Path BANK_SOURCE = Path.of(
            "src/main/java/net/runelite/client/plugins/microbot/util/bank/Rs2Bank.java");
    private static final Path PLUGIN_SOURCE = Path.of(
            "src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java");

    @Test
    public void sharedMutationAndCloseFunnelsOwnPacing() throws IOException
    {
        String source = Files.readString(BANK_SOURCE);

        String itemMenu = between(source,
                "private static boolean invokeMenuPaced",
                "public static Rectangle itemBounds");
        Assert.assertTrue("item menu actions must pass the shared action gate",
                itemMenu.contains("if (!awaitBeforeBankAction()) return false;"));

        String bulkDeposit = between(source,
                "public static boolean depositAll()",
                "public static boolean depositAllExcept(Predicate");
        Assert.assertTrue("bulk inventory deposit must use the same action gate",
                bulkDeposit.contains("if (!awaitBeforeBankAction()) return false;")
                        && bulkDeposit.contains("Rs2Widget.clickWidget(widget);"));

        String equipmentDeposit = between(source,
                "public static boolean depositEquipment()",
                "public static boolean depositEquippedItem(EquipmentInventorySlot");
        Assert.assertTrue("bulk equipment deposit must use pacing and a bank-container postcondition",
                equipmentDeposit.contains("if (!awaitBeforeBankAction()) return false;")
                        && equipmentDeposit.contains("if (!Rs2Widget.clickWidget(widget)) return false;")
                        && equipmentDeposit.contains("syncBankInventoryAfterChange(epochBeforeDeposit)"));

        String emptyContainers = between(source,
                "public static boolean emptyContainers()",
                "public static boolean depositAll()");
        Assert.assertTrue("container deposit must use pacing and a bank-container postcondition",
                emptyContainers.contains("if (!awaitBeforeBankAction()) return false;")
                        && emptyContainers.contains("syncBankInventoryAfterChange(epochBeforeDeposit)"));
        Assert.assertFalse("container deposit must not add a second fixed/randomized wait",
                emptyContainers.contains("sleep("));

        String close = between(source,
                "public static boolean closeBank()",
                "public static Rs2ItemModel findBankItem(String name)");
        Assert.assertTrue("close must pace both sides of its verified postcondition",
                close.contains("if (!awaitBeforeBankClose(closingSessionToken)) return false;")
                        && close.contains("return awaitAfterBankClose(closingSessionToken);"));
        Assert.assertTrue("an interrupted close must cancel its pacing session",
                close.contains("Thread.currentThread().isInterrupted()")
                        && close.contains("BANK_PACING.cancelSession(closingSessionToken);"));

        String alreadyOpen = between(source,
                "private static boolean beginBankPacingForAlreadyOpenBank()",
                "public static int getBankLiveEpoch()");
        Assert.assertTrue("already-open sessions require a live bank-container epoch",
                alreadyOpen.contains("verifyBankMirrorAfterOpen(true, BANK_LIVE_EPOCH.get())"));
    }

    @Test
    public void supersededLocalSleepsStayRemoved() throws IOException
    {
        String source = Files.readString(BANK_SOURCE);

        Assert.assertFalse(source.contains("sleep(Rs2Random.randomGaussian(400,200))"));
        Assert.assertFalse(source.contains("sleep(Rs2Random.randomGaussian(800,200))"));
    }

    @Test
    public void gameSessionTransitionsResetBankPacing() throws IOException
    {
        String pluginSource = Files.readString(PLUGIN_SOURCE);

        Assert.assertTrue(pluginSource.contains("Rs2Bank.resetBankPacing();"));
    }

    private static String between(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        Assert.assertTrue("missing source markers: " + startMarker + " -> " + endMarker,
                start >= 0 && end > start);
        return source.substring(start, end);
    }
}
