package net.runelite.client.plugins.microbot.questhelper.logic;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.runelite.api.NullObjectID;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.QuestHelperPlugin;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.ObjectStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/** Quest-specific route sequencing validated for the Misthalin Mystery instance. */
public class MisthalinMystery extends BaseQuest
{
    private static final long DAMAGED_WALL_CANVAS_RETRY_NANOS = 1_500_000_000L;
    private static final long DAMAGED_WALL_INTERACT_RETRY_NANOS = 1_500_000_000L;
    private static final long MIRROR_MOVE_RETRY_NANOS = 1_200_000_000L;
    private static final long MIRROR_PUSH_RETRY_NANOS = 1_800_000_000L;
    private static final String MIRROR_SHOWDOWN_MARKER = "move the mirror to reflect the knives";
    private static final String LACEY_INTERRUPT_QUESTION = "Interrupt with answer?";
    private static final String LACEY_INTERRUPT_ANSWER = "Count Check";
    private static final WorldPoint PAINTING_OBJECT = new WorldPoint(1632, 4833, 0);
    private static final WorldPoint PAINTING_CANVAS_ENTRY = new WorldPoint(1633, 4830, 0);
    private static final List<WorldPoint> PAINTING_ROUTE = List.of(
            PAINTING_CANVAS_ENTRY,
            new WorldPoint(1629, 4832, 0));

    private static final WorldPoint DAMAGED_WALL_OBJECT = new WorldPoint(1648, 4829, 0);
    private static final String CLIMB_DAMAGED_WALL = "Climb over the damaged wall.";
    private static final WorldPoint DAMAGED_WALL_LOCAL_APPROACH = new WorldPoint(1646, 4829, 0);
    private static final WorldPoint DAMAGED_WALL_CANVAS_TARGET = new WorldPoint(1650, 4830, 0);
    private static final List<WorldPoint> DAMAGED_WALL_OUTBOUND_ROUTE = List.of(
            DAMAGED_WALL_LOCAL_APPROACH);

    private static final WorldPoint FIREPLACE_OBJECT = new WorldPoint(1647, 4836, 0);
    private static final List<WorldPoint> FIREPLACE_ROUTE = List.of(
            new WorldPoint(1633, 4837, 0),
            new WorldPoint(1641, 4828, 0),
            new WorldPoint(1646, 4836, 0));

    private final QuestApproachSequence approachSequence = new QuestApproachSequence();
    private final MisthalinMirrorPlanner.AttackState mirrorAttackState =
            new MisthalinMirrorPlanner.AttackState();
    private volatile long nextDamagedWallLocalAt;
    private volatile long nextDamagedWallCanvasAt;
    private volatile long nextDamagedWallInteractAt;
    private volatile long nextMirrorMoveAt;
    private volatile long nextMirrorPushAt;
    private MisthalinMirrorPlanner.SceneTile mirrorMoveTarget;
    private boolean mirrorShowdownActive;

    @Override
    public boolean executeCustomLogic()
    {
        QuestHelperPlugin plugin = getQuestHelperPlugin();
        if (plugin == null || plugin.getSelectedQuest() == null
                || plugin.getSelectedQuest().getCurrentStep() == null)
        {
            approachSequence.reset();
            resetDamagedWallApproach();
            resetMirrorShowdown();
            return true;
        }

        QuestStep step = plugin.getSelectedQuest().getCurrentStep().getActiveStep();
        if (!handleLaceyInterrupt(
                Rs2Dialogue.getQuestion(),
                Rs2Dialogue.hasDialogueOption(LACEY_INTERRUPT_ANSWER, true),
                () -> {
                    Rs2Walker.clearWalkingRoute("quest-helper:misthalin-lacey-answer");
                    return Rs2Dialogue.keyPressForDialogueOption(LACEY_INTERRUPT_ANSWER, true);
                }))
        {
            return false;
        }
        if (step instanceof DetailedQuestStep
                && isMirrorShowdownText(((DetailedQuestStep) step).getText()))
        {
            return handleMirrorShowdown();
        }
        resetMirrorShowdown();
        if (!(step instanceof ObjectStep))
        {
            approachSequence.reset();
            return true;
        }

        DetailedQuestStep detailedStep = (DetailedQuestStep) step;
        WorldPoint objectLocation = detailedStep.getDefinedPoint() == null
                ? null : detailedStep.getDefinedPoint().getWorldPoint();
        List<WorldPoint> route = approachRoute(objectLocation, detailedStep.getText());
        if (route.isEmpty())
        {
            approachSequence.reset();
            return true;
        }

        if (route == DAMAGED_WALL_OUTBOUND_ROUTE)
        {
            return handleDamagedWallApproach(Rs2Player.getWorldLocation(), Rs2Player.isMoving(),
                    () -> {
                        Rs2Walker.clearWalkingRoute("quest-helper:misthalin-damaged-wall-canvas-approach");
                        Rs2Walker.walkFastCanvas(DAMAGED_WALL_CANVAS_TARGET);
                    },
                    () -> {
                        Rs2Walker.clearWalkingRoute("quest-helper:misthalin-damaged-wall-local-approach");
                        Rs2Walker.walkFastCanvas(DAMAGED_WALL_LOCAL_APPROACH);
                    },
                    () -> {
                        var wall = Rs2GameObject.getWallObject(
                                NullObjectID.NULL_29657,
                                DAMAGED_WALL_OBJECT, 1);
                        if (wall == null)
                        {
                            return false;
                        }
                        Rs2Walker.clearWalkingRoute("quest-helper:misthalin-damaged-wall-climb");
                        return Rs2GameObject.interact(wall, "Climb");
                    });
        }

        WorldPoint waypoint = approachSequence.next(route, route, Rs2Player.getWorldLocation(), 1);
        if (waypoint == null)
        {
            return true;
        }
        if (!Rs2Player.isMoving())
        {
            dispatchWaypoint(waypoint, () -> {
                Rs2Walker.clearWalkingRoute("quest-helper:misthalin-validated-canvas-route");
                Rs2Walker.walkFastCanvas(waypoint);
            }, () -> Rs2Walker.walkWithStateUntil(waypoint, 1,
                    () -> shouldStopRoute(
                            Thread.currentThread().isInterrupted(),
                            InputArbiter.isHuman(),
                            Microbot.pauseAllScripts.get(),
                            Microbot.isLoggedIn(),
                            plugin.getConfig() != null && plugin.getConfig().startStopQuestHelper(),
                            isActiveStep(plugin, step))));
        }
        return false;
    }

    @Override
    public void reset()
    {
        approachSequence.reset();
        resetDamagedWallApproach();
        resetMirrorShowdown();
    }

    static boolean isMirrorShowdownText(List<String> text)
    {
        return text != null && text.stream()
                .filter(line -> line != null)
                .map(line -> line.toLowerCase(Locale.ENGLISH))
                .anyMatch(line -> line.contains(MIRROR_SHOWDOWN_MARKER));
    }

    private boolean handleMirrorShowdown()
    {
        if (!mirrorShowdownActive)
        {
            Rs2Walker.clearWalkingRoute("quest-helper:misthalin-mirror-showdown");
            mirrorShowdownActive = true;
        }

        MirrorSnapshot snapshot = captureMirrorSnapshot();
        long now = System.nanoTime();
        if (snapshot == null || snapshot.mirrorTile == null)
        {
            mirrorAttackState.reset();
            return false;
        }

        mirrorAttackState.observe(snapshot.mirrorTile, snapshot.wardrobeTile, now);
        if (snapshot.wardrobeTile == null || !mirrorAttackState.canDispatch(now))
        {
            return false;
        }

        MisthalinMirrorPlanner.PushPlan plan = MisthalinMirrorPlanner.nextPush(
                snapshot.mirrorTile, snapshot.wardrobeTile,
                tile -> isWalkableSceneTile(tile, snapshot.worldViewId));
        if (plan == null)
        {
            return false;
        }

        if (!plan.getStandTile().equals(snapshot.playerTile))
        {
            nextMirrorPushAt = 0;
            if (Rs2Player.isMoving() || Rs2Player.isAnimating())
            {
                return false;
            }
            if (plan.getStandTile().equals(mirrorMoveTarget)
                    && now - nextMirrorMoveAt < 0)
            {
                return false;
            }

            WorldPoint standPoint = toInstanceWorldPoint(
                    plan.getStandTile(), snapshot.worldViewId);
            if (standPoint == null)
            {
                return false;
            }
            mirrorMoveTarget = plan.getStandTile();
            nextMirrorMoveAt = now + MIRROR_MOVE_RETRY_NANOS;
            Rs2Walker.clearWalkingRoute("quest-helper:misthalin-mirror-position");
            if (!dispatchMirrorMove(standPoint, Rs2Walker::walkFastCanvas))
            {
                mirrorMoveTarget = null;
                nextMirrorMoveAt = 0;
            }
            return false;
        }

        mirrorMoveTarget = null;
        nextMirrorMoveAt = 0;
        if (Rs2Player.isMoving() || Rs2Player.isAnimating()
                || now - nextMirrorPushAt < 0)
        {
            return false;
        }

        nextMirrorPushAt = now + MIRROR_PUSH_RETRY_NANOS;
        Rs2Walker.clearWalkingRoute("quest-helper:misthalin-mirror-push");
        if (snapshot.mirror.click("Push"))
        {
            mirrorAttackState.recordDispatch(
                    snapshot.mirrorTile, plan, now, MIRROR_PUSH_RETRY_NANOS);
        }
        return false;
    }

    private MirrorSnapshot captureMirrorSnapshot()
    {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player player = Microbot.getClient().getLocalPlayer();
            if (player == null || player.getLocalLocation() == null
                    || player.getWorldView() == null)
            {
                return null;
            }
            Rs2NpcModel mirror = Microbot.getRs2NpcCache().query()
                    .fromWorldView()
                    .withId(NpcID.MISTMYST_MIRROR_MOVABLE)
                    .first();
            if (mirror == null || mirror.getLocalLocation() == null)
            {
                return null;
            }
            Rs2TileObjectModel wardrobe = Microbot.getRs2TileObjectCache().query()
                    .fromWorldView()
                    .withId(ObjectID.MISTMYST_BOSS_WARDROBE_OPEN)
                    .first();
            return new MirrorSnapshot(
                    mirror,
                    sceneTile(player.getLocalLocation()),
                    sceneTile(mirror.getLocalLocation()),
                    wardrobe == null ? null : sceneTile(wardrobe.getLocalLocation()),
                    player.getWorldView().getId());
        }).orElse(null);
    }

    private boolean isWalkableSceneTile(MisthalinMirrorPlanner.SceneTile tile, int worldViewId)
    {
        LocalPoint localPoint = toLocalPoint(tile, worldViewId);
        return localPoint != null && localPoint.isInScene() && Rs2Tile.isWalkable(localPoint);
    }

    private LocalPoint toLocalPoint(MisthalinMirrorPlanner.SceneTile tile, int worldViewId)
    {
        if (tile == null)
        {
            return null;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (Microbot.getClient().getWorldView(worldViewId) == null)
            {
                return null;
            }
            return LocalPoint.fromScene(
                    tile.getX(), tile.getY(), Microbot.getClient().getWorldView(worldViewId));
        }).orElse(null);
    }

    private WorldPoint toInstanceWorldPoint(MisthalinMirrorPlanner.SceneTile tile, int worldViewId)
    {
        LocalPoint localPoint = toLocalPoint(tile, worldViewId);
        if (localPoint == null || !localPoint.isInScene())
        {
            return null;
        }
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (Microbot.getClient().getWorldView(worldViewId) == null)
            {
                return null;
            }
            return WorldPoint.fromLocalInstance(
                    Microbot.getClient(), localPoint,
                    Microbot.getClient().getWorldView(worldViewId).getPlane());
        }).orElse(null);
    }

    static boolean dispatchMirrorMove(WorldPoint target, Function<WorldPoint, Boolean> canvasMove)
    {
        return target != null && canvasMove != null && Boolean.TRUE.equals(canvasMove.apply(target));
    }

    private static MisthalinMirrorPlanner.SceneTile sceneTile(LocalPoint point)
    {
        return point == null ? null
                : new MisthalinMirrorPlanner.SceneTile(point.getSceneX(), point.getSceneY());
    }

    private void resetMirrorShowdown()
    {
        mirrorAttackState.reset();
        nextMirrorMoveAt = 0;
        nextMirrorPushAt = 0;
        mirrorMoveTarget = null;
        mirrorShowdownActive = false;
    }

    private static final class MirrorSnapshot
    {
        private final Rs2NpcModel mirror;
        private final MisthalinMirrorPlanner.SceneTile playerTile;
        private final MisthalinMirrorPlanner.SceneTile mirrorTile;
        private final MisthalinMirrorPlanner.SceneTile wardrobeTile;
        private final int worldViewId;

        private MirrorSnapshot(Rs2NpcModel mirror,
                               MisthalinMirrorPlanner.SceneTile playerTile,
                               MisthalinMirrorPlanner.SceneTile mirrorTile,
                               MisthalinMirrorPlanner.SceneTile wardrobeTile,
                               int worldViewId)
        {
            this.mirror = mirror;
            this.playerTile = playerTile;
            this.mirrorTile = mirrorTile;
            this.wardrobeTile = wardrobeTile;
            this.worldViewId = worldViewId;
        }
    }

    static List<WorldPoint> approachRoute(WorldPoint objectLocation, List<String> stepText)
    {
        if (PAINTING_OBJECT.equals(objectLocation))
        {
            return PAINTING_ROUTE;
        }
        if (DAMAGED_WALL_OBJECT.equals(objectLocation)
                && stepText != null && stepText.contains(CLIMB_DAMAGED_WALL))
        {
            return DAMAGED_WALL_OUTBOUND_ROUTE;
        }
        if (FIREPLACE_OBJECT.equals(objectLocation))
        {
            return FIREPLACE_ROUTE;
        }
        return Collections.emptyList();
    }

    static boolean handleLaceyInterrupt(String question, boolean countCheckAvailable,
                                        BooleanSupplier selectCountCheck)
    {
        if (!LACEY_INTERRUPT_QUESTION.equals(question))
        {
            return true;
        }
        if (countCheckAvailable)
        {
            selectCountCheck.getAsBoolean();
        }
        return false;
    }

    boolean handleDamagedWallApproach(WorldPoint player, boolean moving,
                                      Runnable canvasMove, Runnable localMove,
                                      BooleanSupplier climb)
    {
        if (player == null || player.getPlane() != DAMAGED_WALL_OBJECT.getPlane())
        {
            return false;
        }
        if (moving)
        {
            return false;
        }
        if (player.distanceTo2D(DAMAGED_WALL_OBJECT) <= 1)
        {
            nextDamagedWallLocalAt = 0;
            nextDamagedWallCanvasAt = 0;
            long now = System.nanoTime();
            if (nextDamagedWallInteractAt == 0 || now - nextDamagedWallInteractAt >= 0)
            {
                nextDamagedWallInteractAt = now + DAMAGED_WALL_INTERACT_RETRY_NANOS;
                climb.getAsBoolean();
            }
            return false;
        }
        if (player.distanceTo2D(DAMAGED_WALL_LOCAL_APPROACH) > 1)
        {
            nextDamagedWallCanvasAt = 0;
            nextDamagedWallInteractAt = 0;
            long now = System.nanoTime();
            if (nextDamagedWallLocalAt != 0 && now - nextDamagedWallLocalAt < 0)
            {
                return false;
            }
            nextDamagedWallLocalAt = now + DAMAGED_WALL_CANVAS_RETRY_NANOS;
            localMove.run();
        }
        else
        {
            nextDamagedWallLocalAt = 0;
            nextDamagedWallInteractAt = 0;
            long now = System.nanoTime();
            if (nextDamagedWallCanvasAt != 0 && now - nextDamagedWallCanvasAt < 0)
            {
                return false;
            }
            nextDamagedWallCanvasAt = now + DAMAGED_WALL_CANVAS_RETRY_NANOS;
            canvasMove.run();
        }
        return false;
    }

    private void resetDamagedWallApproach()
    {
        nextDamagedWallLocalAt = 0;
        nextDamagedWallCanvasAt = 0;
        nextDamagedWallInteractAt = 0;
    }

    static boolean useCanvas(WorldPoint waypoint)
    {
        return PAINTING_CANVAS_ENTRY.equals(waypoint)
                || DAMAGED_WALL_LOCAL_APPROACH.equals(waypoint)
                || DAMAGED_WALL_CANVAS_TARGET.equals(waypoint);
    }

    static void dispatchWaypoint(WorldPoint waypoint, Runnable canvasMove, Runnable webWalk)
    {
        if (useCanvas(waypoint))
        {
            canvasMove.run();
        }
        else
        {
            webWalk.run();
        }
    }

    private static boolean isActiveStep(QuestHelperPlugin plugin, QuestStep expected)
    {
        return plugin.getSelectedQuest() != null
                && plugin.getSelectedQuest().getCurrentStep() != null
                && plugin.getSelectedQuest().getCurrentStep().getActiveStep() == expected;
    }

    static boolean shouldStopRoute(boolean interrupted, boolean human, boolean paused,
                                   boolean loggedIn, boolean enabled, boolean sameStep)
    {
        return interrupted || human || paused || !loggedIn || !enabled || !sameStep;
    }
}
