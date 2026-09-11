package net.runelite.client.plugins.microbot.questhelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;

final class QuestBankItemSelector
{
    private static final int UNUSABLE = Integer.MAX_VALUE;
    private static final Map<Integer, Integer> PICKAXE_LEVELS = createPickaxeLevels();

    private QuestBankItemSelector()
    {
    }

    static boolean containsPickaxe(List<Integer> itemIds)
    {
        return itemIds.stream().anyMatch(PICKAXE_LEVELS::containsKey);
    }

    static int selectBankId(List<Integer> itemIds, IntUnaryOperator bankCount, int needed, int miningLevel)
    {
        if (itemIds == null || needed <= 0)
        {
            return -1;
        }

        if (containsPickaxe(itemIds))
        {
            for (Integer id : itemIds)
            {
                if (id != null
                        && miningLevel >= PICKAXE_LEVELS.getOrDefault(id, UNUSABLE)
                        && bankCount.applyAsInt(id) >= needed)
                {
                    return id;
                }
            }
            return -1;
        }

        int bestId = -1;
        int bestCount = 0;
        for (Integer id : itemIds)
        {
            if (id == null || id <= 0)
            {
                continue;
            }
            int count = bankCount.applyAsInt(id);
            if (count >= needed && count > bestCount)
            {
                bestCount = count;
                bestId = id;
            }
        }
        return bestId;
    }

    private static Map<Integer, Integer> createPickaxeLevels()
    {
        Map<Integer, Integer> levels = new HashMap<>();
        levels.put(ItemID.TRAILBLAZER_PICKAXE, 1);
        levels.put(ItemID.LEAGUE_TRAILBLAZER_PICKAXE, 1);
        levels.put(ItemID.CRYSTAL_PICKAXE, 71);
        levels.put(ItemID.CRYSTAL_PICKAXE_INACTIVE, UNUSABLE);
        levels.put(ItemID._3A_PICKAXE, 61);
        levels.put(ItemID.INFERNAL_PICKAXE, 61);
        levels.put(ItemID.TRAILBLAZER_PICKAXE_NO_INFERNAL, 1);
        levels.put(ItemID.DRAGON_PICKAXE_PRETTY, 61);
        levels.put(ItemID.ZALCANO_PICKAXE, 61);
        levels.put(ItemID.DRAGON_PICKAXE, 61);
        levels.put(ItemID.RUNE_PICKAXE, 41);
        levels.put(ItemID.TRAIL_GILDED_PICKAXE, 41);
        levels.put(ItemID.ADAMANT_PICKAXE, 31);
        levels.put(ItemID.MITHRIL_PICKAXE, 21);
        levels.put(ItemID.BLACK_PICKAXE, 11);
        levels.put(ItemID.STEEL_PICKAXE, 6);
        levels.put(ItemID.IRON_PICKAXE, 1);
        levels.put(ItemID.BRONZE_PICKAXE, 1);
        return Map.copyOf(levels);
    }
}
