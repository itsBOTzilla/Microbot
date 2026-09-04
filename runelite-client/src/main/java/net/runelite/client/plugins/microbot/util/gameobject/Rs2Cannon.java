package net.runelite.client.plugins.microbot.util.gameobject;

import net.runelite.api.ObjectID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.plugins.cannon.CannonPlugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

public class Rs2Cannon {

    public static boolean repair() {
        Rs2TileObjectModel brokenCannon = Microbot.getRs2TileObjectCache().query()
                .withIds(ObjectID.BROKEN_MULTICANNON_14916, ObjectID.BROKEN_MULTICANNON_43028)
                .nearest();

        if (brokenCannon == null) return false;

        if (!isOwnCannon(brokenCannon)) return false;

        Microbot.status = "Repairing Cannon";

        brokenCannon.click("Repair");
        return true;
    }

    public static boolean refill() {
        return refill(Rs2Random.between(10, 15));
    }

    public static boolean refill(int cannonRefillAmount) {
        if (!Rs2Inventory.hasItemAmount("cannonball", 15, true)) {
            System.out.println("Not enough cannonballs!");
            return false;
        }

        int cannonBallsLeft = Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getVarpValue(VarPlayer.CANNON_AMMO)).orElse(0);

        if (cannonBallsLeft > cannonRefillAmount) return false;

        Microbot.status = "Refilling Cannon";

        Rs2TileObjectModel cannon = Microbot.getRs2TileObjectCache().query()
                .withIds(ObjectID.DWARF_MULTICANNON, ObjectID.DWARF_MULTICANNON_43027)
                .nearest();
        if (cannon == null) return false;

        if (!isOwnCannon(cannon)) return false;
		Microbot.pauseAllScripts.compareAndSet(false, true);
        cannon.click("Fire");
        Rs2Player.waitForWalking();
        sleep(1200);
        cannon.click("Fire");
        sleepUntil(() -> Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getVarpValue(VarPlayer.CANNON_AMMO)).orElse(0) > Rs2Random.between(10, 15));
		Microbot.pauseAllScripts.compareAndSet(true, false);
        return true;
    }

    static boolean isOwnCannon(Rs2TileObjectModel cannon) {
        WorldArea ownedArea = CannonPlugin.getCannonPosition();
        if (cannon == null || ownedArea == null) return false;
        // The RuneLite object exposes its center; the query model exposes its southwest tile.
        WorldPoint center = Microbot.getClientThread().runOnClientThreadOptional(
                () -> cannon.getTileObject().getWorldLocation()).orElse(null);
        return center != null && center.dx(-1).dy(-1).equals(ownedArea.toWorldPoint());
    }
}
