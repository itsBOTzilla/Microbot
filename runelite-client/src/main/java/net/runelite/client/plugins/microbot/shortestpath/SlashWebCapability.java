package net.runelite.client.plugins.microbot.shortestpath;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/** Capability checks shared by slash-web route planning and execution. */
public final class SlashWebCapability {
    private static final Set<Integer> KNOWN_SLASH_WEAPONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    ItemID.BRONZE_SCIMITAR,
                    ItemID.IRON_SCIMITAR,
                    ItemID.STEEL_SCIMITAR,
                    ItemID.BLACK_SCIMITAR,
                    ItemID.MITHRIL_SCIMITAR,
                    ItemID.ADAMANT_SCIMITAR,
                    ItemID.RUNE_SCIMITAR,
                    ItemID.DRAGON_SCIMITAR)));

    private SlashWebCapability() {
    }

    public static boolean applies(Transport transport) {
        return transport != null
                && transport.getObjectId() == 733
                && "Slash".equalsIgnoreCase(transport.getAction())
                && "Web".equalsIgnoreCase(transport.getName());
    }

    public static boolean containsSlashWeapon(Collection<Integer> itemIds) {
        return itemIds != null && itemIds.stream().anyMatch(SlashWebCapability::isSlashWeapon);
    }

    public static boolean isSlashWeapon(int itemId) {
        if (KNOWN_SLASH_WEAPONS.contains(itemId)) {
            return true;
        }
        if (Microbot.getItemManager() == null) {
            return false;
        }
        ItemStats stats = Microbot.getItemManager().getItemStats(itemId);
        ItemEquipmentStats equipment = stats == null ? null : stats.getEquipment();
        return equipment != null
                && equipment.getSlot() == EquipmentInventorySlot.WEAPON.getSlotIdx()
                && equipment.getAslash() > 0;
    }

    public static boolean hasCarriedSlashWeapon() {
        return Rs2Equipment.all().anyMatch(item -> isSlashWeapon(item.getId()))
                || Rs2Inventory.items().anyMatch(item -> isSlashWeapon(item.getId()));
    }

    public static boolean prepareTool() {
        if (Rs2Inventory.hasItem(ItemID.KNIFE)
                || Rs2Equipment.all().anyMatch(item -> isSlashWeapon(item.getId()))) {
            return true;
        }

        Rs2ItemModel slashWeapon = inventorySlashWeapon();
        if (slashWeapon == null) {
            return false;
        }
        String equipAction = slashWeapon.getActionFromList(Arrays.asList("wield", "wear", "equip"));
        if (equipAction == null || !Rs2Inventory.interact(slashWeapon, equipAction)) {
            return false;
        }
        int itemId = slashWeapon.getId();
        return sleepUntil(() -> Rs2Equipment.isWearing(itemId), 2_000);
    }

    public static boolean isTransportObjectPresent(Transport transport) {
        if (transport == null || transport.getOrigin() == null || transport.getObjectId() <= 0) {
            return false;
        }
        return !Rs2GameObject.getAll(
                object -> object.getId() == transport.getObjectId(), transport.getOrigin(), 2).isEmpty();
    }

    public static Rs2ItemModel inventorySlashWeapon() {
        return Rs2Inventory.items()
                .filter(item -> isSlashWeapon(item.getId()))
                .findFirst()
                .orElse(null);
    }
}
