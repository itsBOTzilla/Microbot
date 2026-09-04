package net.runelite.client.plugins.microbot.api.tileobject.models;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.IEntity;
import net.runelite.client.plugins.microbot.api.boat.Rs2BoatCache;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

@Slf4j
public class Rs2TileObjectModel implements TileObject, IEntity {

    public Rs2TileObjectModel(GameObject gameObject) {
        this.tileObject = gameObject;
        this.tileObjectType = TileObjectType.GAME;
    }

    public Rs2TileObjectModel(DecorativeObject tileObject) {
        this.tileObject = tileObject;
        this.tileObjectType = TileObjectType.DECORATIVE;
    }

    public Rs2TileObjectModel(WallObject tileObject) {
        this.tileObject = tileObject;
        this.tileObjectType = TileObjectType.WALL;
    }

    public Rs2TileObjectModel(GroundObject tileObject) {
        this.tileObject = tileObject;
        this.tileObjectType = TileObjectType.GROUND;
    }

    public Rs2TileObjectModel(TileObject tileObject) {
        this.tileObject = tileObject;
        this.tileObjectType = TileObjectType.GENERIC;
    }

    @Getter
    private final TileObjectType tileObjectType;
    private final TileObject tileObject;


    @Override
    public long getHash() {
        return tileObject.getHash();
    }

    @Override
    public int getX() {
        return tileObject.getX();
    }

    @Override
    public int getY() {
        return tileObject.getY();
    }

    @Override
    public int getZ() {
        return tileObject.getZ();
    }

    @Override
    public int getPlane() {
        return tileObject.getPlane();
    }

    @Override
    public WorldView getWorldView() {
        return tileObject.getWorldView();
    }

    public int getId() {
        return tileObject.getId();
    }

    @Override
    public @NotNull WorldPoint getWorldLocation() {
        WorldPoint worldLocation = tileObject.getWorldLocation();

        if (!(tileObject instanceof GameObject)) {
            return worldLocation;
        }

        GameObject go = (GameObject) tileObject;
        WorldView wv = getWorldView();
        Point sceneMin = go.getSceneMinLocation();

        if (wv == null || sceneMin == null) {
            return worldLocation;
        }

        return WorldPoint.fromScene(wv, sceneMin.getX(), sceneMin.getY(), wv.getPlane());
    }

    public String getName() {
        return Microbot.getClientThread().invoke(() -> {
            ObjectComposition composition = Microbot.getClient().getObjectDefinition(tileObject.getId());
            if (composition == null) {
                return null;
            }
            if (composition.getImpostorIds() != null) {
                composition = composition.getImpostor();
            }
            if (composition == null)
                return null;
            return Rs2UiHelper.stripColTags(composition.getName());
        });
    }

    @Override
    public @NotNull LocalPoint getLocalLocation() {
        return tileObject.getLocalLocation();
    }

    @Override
    public @Nullable Point getCanvasLocation() {
        return tileObject.getCanvasLocation();
    }

    @Override
    public @Nullable Point getCanvasLocation(int zOffset) {
        return tileObject.getCanvasLocation();
    }

    @Override
    public @Nullable Polygon getCanvasTilePoly() {
        return tileObject.getCanvasTilePoly();
    }

    @Override
    public @Nullable Point getCanvasTextLocation(Graphics2D graphics, String text, int zOffset) {
        return tileObject.getCanvasTextLocation(graphics, text, zOffset);
    }

    @Override
    public @Nullable Point getMinimapLocation() {
        return tileObject.getMinimapLocation();
    }

    @Override
    public @Nullable Shape getClickbox() {
        return tileObject.getClickbox();
    }

    @Override
    public @Nullable String getOpOverride(int index) {
        return tileObject.getOpOverride(index);
    }

    @Override
    public boolean isOpShown(int index) {
        return tileObject.isOpShown(index);
    }

    public ObjectComposition getObjectComposition() {
        return Microbot.getClientThread().invoke(() -> {
            ObjectComposition composition = Microbot.getClient().getObjectDefinition(tileObject.getId());
            if (composition == null) {
                return null;
            }
            if (composition.getImpostorIds() != null) {
                composition = composition.getImpostor();
            }
            return composition;
        });
    }

    /**
     * Returns a defensive snapshot of the selected object's actions.
     */
    public String[] getActions() {
        return Microbot.getClientThread().invoke(() -> {
            ObjectComposition base = Microbot.getClient().getObjectDefinition(tileObject.getId());
            ObjectComposition selected = base == null ? null
                    : base.getImpostorIds() == null ? base : base.getImpostor();
            String[] actions = selected == null ? null : selected.getActions();
            return actions == null ? null : actions.clone();
        });
    }

    @Override
    public boolean isReachable() {
        WorldView objectWorldView = getWorldView();
        if (objectWorldView == null) {
            return false;
        }

        WorldView playerWorldView = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player player = Microbot.getClient().getLocalPlayer();
            return player != null ? player.getWorldView() : null;
        }).orElse(null);

        if (playerWorldView == null) {
            return false;
        }

        if (objectWorldView.getId() == playerWorldView.getId()) {
            return true;
        }

        return IEntity.super.isReachable();
    }

    public boolean click() {
        return click("");
    }

    /**
     * Clicks on the specified tile object with no specific action.
     * Delegates to Rs2GameObject.clickObject.
     *
     * @param action the action to perform (e.g., "Open", "Climb")
     * @return true if the interaction was successful, false otherwise
     */
    public boolean click(String action) {
        return clickWithIdentifier(action, getId(), false, false);
    }

    /**
     * Clicks a declared transformed variant of this object.
     */
    public boolean clickVariant(String action, int variantIdentifier) {
        int interactionIdentifier = resolveInteractionIdentifier(variantIdentifier);
        return interactionIdentifier >= 0 && clickWithIdentifier(action, interactionIdentifier, false, true);
    }

    /**
     * Dispatches an exact transformed object action without requiring a clickbox.
     */
    public boolean clickVariantDirect(String action, int variantIdentifier) {
        int interactionIdentifier = resolveInteractionIdentifier(variantIdentifier);
        return interactionIdentifier >= 0 && clickWithIdentifier(action, interactionIdentifier, true, true);
    }

    private boolean clickWithIdentifier(String action, int interactionIdentifier, boolean direct, boolean explicitVariant) {
        try {
            LocalPoint localLocation = getLocalLocation();
            if (localLocation == null) {
                return false;
            }
            WorldView worldView = getWorldView();
            InteractionSnapshot snapshot = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                int param0;
                int param1;
                if (getTileObjectType() == TileObjectType.GAME) {
                    GameObject gameObject = (GameObject) tileObject;
                    param0 = localLocation.getSceneX()
                            - (gameObject.sizeX() > 1 ? gameObject.sizeX() / 2 : 0);
                    param1 = localLocation.getSceneY()
                            - (gameObject.sizeY() > 1 ? gameObject.sizeY() / 2 : 0);
                } else {
                    param0 = localLocation.getSceneX();
                    param1 = localLocation.getSceneY();
                }
                int worldViewId = worldView == null ? -1 : worldView.getId();
                return new InteractionSnapshot(param0, param1, worldViewId,
                        Microbot.getClient().isWidgetSelected());
            }).orElse(null);
            if (snapshot == null || snapshot.worldViewId < 0) {
                return false;
            }

            int index = explicitVariant ? resolveVariantActionIndex(action, interactionIdentifier) : resolveActionIndex(action);
            if (index < 0) {
                log.warn("Failed to interact with object {} - action '{}' not found", getId(), action);
                return false;
            }

            String objectName = getName();
            Microbot.status = (action == null ? "" : action) + " " + objectName;
            if (objectName != null && objectName.toLowerCase().contains("train cart")) {
                Rs2Equipment.unEquip(EquipmentInventorySlot.WEAPON);
                Rs2Equipment.unEquip(EquipmentInventorySlot.SHIELD);
                sleepUntil(() -> Rs2Equipment.get(EquipmentInventorySlot.WEAPON) == null
                        && Rs2Equipment.get(EquipmentInventorySlot.SHIELD) == null);
            }

            MenuAction menuAction = resolveMenuAction(index);
            if (snapshot.widgetSelected) {
                menuAction = MenuAction.WIDGET_TARGET_ON_GAME_OBJECT;
            }

            String normalizedAction = action == null ? "" : action;
            String normalizedTarget = objectName == null ? "" : objectName;

            if (direct) {
                return dispatchDirectAction(Microbot.getClient(), snapshot.param0, snapshot.param1,
                        menuAction, interactionIdentifier, snapshot.worldViewId,
                        normalizedAction, normalizedTarget);
            }

            if (!Rs2Camera.isTileOnScreen(localLocation)) {
                Rs2Camera.turnTo(tileObject);
            }
            Microbot.doInvoke(new NewMenuEntry()
                            .param0(snapshot.param0)
                            .param1(snapshot.param1)
                            .opcode(menuAction.getId())
                            .identifier(interactionIdentifier)
                            .itemId(-1)
                            .option(normalizedAction)
                            .target(normalizedTarget)
                            .setWorldViewId(snapshot.worldViewId)
                            .gameObject(tileObject),
                    Rs2UiHelper.getObjectClickbox(tileObject));
            return true;
        } catch (Exception ex) {
            log.error("Failed to interact with object: ", ex);
            return false;
        }
    }

    int resolveInteractionIdentifier(int requestedIdentifier) {
        if (requestedIdentifier == getId()) {
            return requestedIdentifier;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            ObjectComposition base = Microbot.getClient().getObjectDefinition(getId());
            if (base == null || base.getImpostorIds() == null) {
                return -1;
            }
            for (int variantId : base.getImpostorIds()) {
                if (variantId == requestedIdentifier) {
                    return requestedIdentifier;
                }
            }
            return -1;
        }).orElse(-1);
    }

    static boolean dispatchDirectAction(Client client, int param0, int param1,
                                        MenuAction menuAction, int identifier, int worldViewId,
                                        String option, String target) {
        if (client == null || menuAction == null || worldViewId != WorldView.TOPLEVEL) {
            return false;
        }
        try {
            return Microbot.getClientThread().runOnClientThreadOptional(() -> {
                client.menuAction(param0, param1, menuAction, identifier, -1,
                        option == null ? "" : option, target == null ? "" : target);
                return true;
            }).orElse(false);
        } catch (RuntimeException ex) {
            log.debug("Direct object menu action failed for id={}", identifier, ex);
            return false;
        }
    }

    int resolveActionIndex(String requestedAction) {
        if (requestedAction == null || requestedAction.isBlank()) {
            return 0;
        }
        return Microbot.getClientThread().invoke(() -> {
            ObjectComposition base = Microbot.getClient().getObjectDefinition(getId());
            ObjectComposition selected = base == null ? null
                    : base.getImpostorIds() == null ? base : base.getImpostor();
            String[] actions = selected == null ? null : selected.getActions();
            for (int i = 0; i < 5; i++) {
                String liveAction = isOpShown(i) ? getOpOverride(i) : null;
                // An override replaces the definition's operation in this slot.
                String effectiveAction = liveAction != null ? liveAction
                        : actions != null && i < actions.length ? actions[i] : null;
                if (effectiveAction != null
                        && requestedAction.equalsIgnoreCase(Rs2UiHelper.stripColTags(effectiveAction))) {
                    return i;
                }
            }
            return -1;
        });
    }

    int resolveVariantActionIndex(String requestedAction, int requestedIdentifier) {
        return Microbot.getClientThread().invoke(() -> {
            if (resolveInteractionIdentifier(requestedIdentifier) < 0) {
                return -1;
            }
            ObjectComposition variant = Microbot.getClient().getObjectDefinition(requestedIdentifier);
            if (variant == null) {
                return -1;
            }
            return requestedAction == null || requestedAction.isBlank() ? 0
                    : findActionIndex(variant.getActions(), requestedAction);
        });
    }

    private static int findActionIndex(String[] actions, String requestedAction) {
        if (actions == null) {
            return -1;
        }
        for (int i = 0; i < Math.min(actions.length, 5); i++) {
            String candidate = actions[i];
            if (candidate != null
                    && requestedAction.equalsIgnoreCase(Rs2UiHelper.stripColTags(candidate))) {
                return i;
            }
        }
        return -1;
    }

    private static MenuAction resolveMenuAction(int index) {
        switch (index) {
            case 0:
                return MenuAction.GAME_OBJECT_FIRST_OPTION;
            case 1:
                return MenuAction.GAME_OBJECT_SECOND_OPTION;
            case 2:
                return MenuAction.GAME_OBJECT_THIRD_OPTION;
            case 3:
                return MenuAction.GAME_OBJECT_FOURTH_OPTION;
            case 4:
                return MenuAction.GAME_OBJECT_FIFTH_OPTION;
            default:
                return MenuAction.WALK;
        }
    }

    private static final class InteractionSnapshot {
        private final int param0;
        private final int param1;
        private final int worldViewId;
        private final boolean widgetSelected;

        private InteractionSnapshot(int param0, int param1,
                                    int worldViewId, boolean widgetSelected) {
            this.param0 = param0;
            this.param1 = param1;
            this.worldViewId = worldViewId;
            this.widgetSelected = widgetSelected;
        }
    }

}
