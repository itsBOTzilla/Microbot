package net.runelite.client.plugins.microbot.questhelper;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.IntPredicate;

import static org.junit.Assert.assertEquals;

public class QuestShopAutomationTest {

    @Test
    public void shopNpcStepPrefersTradeOverTalkTo() {
        String action = QuestScript.chooseNpcAction(
                Collections.singletonList("Purchase a bucket from the general store."),
                new String[]{"Talk-to", "Trade"},
                true);

        assertEquals("Trade", action);
    }

    @Test
    public void ordinaryNpcStepStillPrefersMatchingTalkAction() {
        String action = QuestScript.chooseNpcAction(
                Collections.singletonList("Talk to the cook."),
                new String[]{"Talk-to", "Trade"},
                false);

        assertEquals("Talk-to", action);
    }

    @Test
    public void combatNpcUsesFightWhenAttackIsNotAvailable() {
        assertEquals("Fight", QuestScript.chooseCombatNpcAction(
                new String[]{"Talk-to", "Fight", null}));
    }

    @Test
    public void combatNpcStillPrefersAttackWhenAvailable() {
        assertEquals("Attack", QuestScript.chooseCombatNpcAction(
                new String[]{"Talk-to", "Fight", "Attack"}));
    }

    @Test
    public void shopStepSelectsFirstItemNotAlreadyInInventory() {
        IntPredicate hasItem = itemId -> itemId == 100;

        int itemId = QuestScript.firstMissingShopItemId(Arrays.asList(100, 200), hasItem);

        assertEquals(200, itemId);
    }
}
