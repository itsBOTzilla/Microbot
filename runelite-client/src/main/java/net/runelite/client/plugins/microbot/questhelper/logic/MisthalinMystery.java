package net.runelite.client.plugins.microbot.questhelper.logic;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.QuestHelperPlugin;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.questhelper.steps.ObjectStep;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

/** Quest-specific route sequencing validated for the Misthalin Mystery instance. */
public class MisthalinMystery extends BaseQuest
{
    private static final long DAMAGED_WALL_CANVAS_RETRY_NANOS = 1_500_000_000L;
    private static final WorldPoint PAINTING_OBJECT = new WorldPoint(1632, 4833, 0);
    private static final WorldPoint PAINTING_CANVAS_ENTRY = new WorldPoint(1633, 4830, 0);
    private static final List<WorldPoint> PAINTING_ROUTE = List.of(
            PAINTING_CANVAS_ENTRY,
            new WorldPoint(1629, 4832, 0));

    private static final WorldPoint DAMAGED_WALL_OBJECT = new WorldPoint(1648, 4829, 0);
    private static final String CLIMB_DAMAGED_WALL = "Climb over the damaged wall.";
    private static final WorldPoint DAMAGED_WALL_WEB_APPROACH = new WorldPoint(1646, 4829, 0);
    private static final WorldPoint DAMAGED_WALL_CANVAS_TARGET = new WorldPoint(1650, 4830, 0);
    private static final List<WorldPoint> DAMAGED_WALL_OUTBOUND_ROUTE = List.of(
            DAMAGED_WALL_WEB_APPROACH);

    private static final WorldPoint FIREPLACE_OBJECT = new WorldPoint(1647, 4836, 0);
    private static final List<WorldPoint> FIREPLACE_ROUTE = List.of(
            new WorldPoint(1633, 4837, 0),
            new WorldPoint(1641, 4828, 0),
            new WorldPoint(1646, 4836, 0));

    private final QuestApproachSequence approachSequence = new QuestApproachSequence();
    private volatile long nextDamagedWallCanvasAt;

    @Override
    public boolean executeCustomLogic()
    {
        QuestHelperPlugin plugin = getQuestHelperPlugin();
        if (plugin == null || plugin.getSelectedQuest() == null
                || plugin.getSelectedQuest().getCurrentStep() == null)
        {
            approachSequence.reset();
            return true;
        }

        QuestStep step = plugin.getSelectedQuest().getCurrentStep().getActiveStep();
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
                    () -> Rs2Walker.walkWithStateUntil(DAMAGED_WALL_WEB_APPROACH, 1,
                            () -> shouldStopRoute(
                                    Thread.currentThread().isInterrupted(),
                                    InputArbiter.isHuman(),
                                    Microbot.pauseAllScripts.get(),
                                    Microbot.isLoggedIn(),
                                    plugin.getConfig() != null && plugin.getConfig().startStopQuestHelper(),
                                    isActiveStep(plugin, step))));
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
        nextDamagedWallCanvasAt = 0;
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

    boolean handleDamagedWallApproach(WorldPoint player, boolean moving,
                                      Runnable canvasMove, Runnable webWalk)
    {
        if (player == null || player.getPlane() != DAMAGED_WALL_OBJECT.getPlane())
        {
            return false;
        }
        if (player.distanceTo2D(DAMAGED_WALL_OBJECT) <= 1)
        {
            nextDamagedWallCanvasAt = 0;
            return true;
        }
        if (moving)
        {
            return false;
        }
        if (player.distanceTo2D(DAMAGED_WALL_WEB_APPROACH) > 1)
        {
            nextDamagedWallCanvasAt = 0;
            webWalk.run();
        }
        else
        {
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

    static boolean useCanvas(WorldPoint waypoint)
    {
        return PAINTING_CANVAS_ENTRY.equals(waypoint);
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
