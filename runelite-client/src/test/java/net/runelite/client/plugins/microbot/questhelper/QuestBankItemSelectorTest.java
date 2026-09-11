package net.runelite.client.plugins.microbot.questhelper;

import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class QuestBankItemSelectorTest
{
    @Test
    public void levelTenMinerSkipsUnusablePickaxes()
    {
        Map<Integer, Integer> bank = Map.of(
                ItemID.RUNE_PICKAXE, 1,
                ItemID.BLACK_PICKAXE, 1,
                ItemID.STEEL_PICKAXE, 1);

        int selected = QuestBankItemSelector.selectBankId(
                List.of(ItemID.RUNE_PICKAXE, ItemID.BLACK_PICKAXE, ItemID.STEEL_PICKAXE),
                id -> bank.getOrDefault(id, 0), 1, 10);

        assertEquals(ItemID.STEEL_PICKAXE, selected);
    }

    @Test
    public void qualifiedMinerGetsBestAvailableUsablePickaxe()
    {
        Map<Integer, Integer> bank = Map.of(
                ItemID.DRAGON_PICKAXE, 1,
                ItemID.RUNE_PICKAXE, 1,
                ItemID.ADAMANT_PICKAXE, 1);

        int selected = QuestBankItemSelector.selectBankId(
                List.of(ItemID.DRAGON_PICKAXE, ItemID.RUNE_PICKAXE, ItemID.ADAMANT_PICKAXE),
                id -> bank.getOrDefault(id, 0), 1, 41);

        assertEquals(ItemID.RUNE_PICKAXE, selected);
    }

    @Test
    public void ordinaryAlternativesStillChooseACompleteBankStack()
    {
        int selected = QuestBankItemSelector.selectBankId(
                List.of(100, 200), id -> id == 100 ? 2 : 5, 3, 1);

        assertEquals(200, selected);
    }
}
