package net.runelite.client.plugins.microbot.questhelper.logic;

import java.util.Objects;
import java.util.function.Predicate;

/** Pure scene-coordinate planning and dispatch state for the Misthalin mirror showdown. */
final class MisthalinMirrorPlanner
{
    private MisthalinMirrorPlanner()
    {
    }

    static PushPlan nextPush(SceneTile mirror, SceneTile wardrobe, SceneTile arenaCenter,
                             Predicate<SceneTile> canOccupy)
    {
        if (mirror == null || wardrobe == null || arenaCenter == null || canOccupy == null
                || mirror.equals(wardrobe))
        {
            return null;
        }

        Direction inward = directionToward(wardrobe, arenaCenter);
        if (inward == null)
        {
            return null;
        }

        SceneTile target = wardrobe.translate(
                inward.getDeltaX() * 4, inward.getDeltaY() * 4);
        SceneTile staging = target.translate(inward.getDeltaX(), inward.getDeltaY());
        if (mirror.equals(staging))
        {
            return validPlan(mirror, inward.opposite(), true, canOccupy);
        }

        return planToward(mirror, staging, canOccupy);
    }

    private static PushPlan planToward(SceneTile mirror, SceneTile target,
                                       Predicate<SceneTile> canOccupy)
    {
        int deltaX = target.getX() - mirror.getX();
        int deltaY = target.getY() - mirror.getY();
        if (deltaX == 0 && deltaY == 0)
        {
            return null;
        }
        if (deltaX == 0)
        {
            return validPlan(mirror, Direction.vertical(deltaY), false, canOccupy);
        }
        if (deltaY == 0)
        {
            return validPlan(mirror, Direction.horizontal(deltaX), false, canOccupy);
        }

        Direction first;
        Direction second;
        if (Math.abs(deltaY) < Math.abs(deltaX))
        {
            first = Direction.vertical(deltaY);
            second = Direction.horizontal(deltaX);
        }
        else
        {
            first = Direction.horizontal(deltaX);
            second = Direction.vertical(deltaY);
        }

        PushPlan plan = validPlan(mirror, first, false, canOccupy);
        return plan != null ? plan : validPlan(mirror, second, false, canOccupy);
    }

    private static Direction directionToward(SceneTile from, SceneTile to)
    {
        int deltaX = to.getX() - from.getX();
        int deltaY = to.getY() - from.getY();
        if (deltaX == 0 && deltaY == 0)
        {
            return null;
        }
        return Math.abs(deltaX) > Math.abs(deltaY)
                ? Direction.horizontal(deltaX)
                : Direction.vertical(deltaY);
    }

    private static PushPlan validPlan(SceneTile mirror, Direction direction, boolean finalAim,
                                      Predicate<SceneTile> canOccupy)
    {
        SceneTile stand = mirror.translate(-direction.getDeltaX(), -direction.getDeltaY());
        SceneTile expected = mirror.translate(direction.getDeltaX(), direction.getDeltaY());
        return canOccupy.test(stand) && canOccupy.test(expected)
                ? new PushPlan(direction, stand, expected, finalAim)
                : null;
    }

    enum Direction
    {
        NORTH(0, 1),
        EAST(1, 0),
        SOUTH(0, -1),
        WEST(-1, 0);

        private final int deltaX;
        private final int deltaY;

        Direction(int deltaX, int deltaY)
        {
            this.deltaX = deltaX;
            this.deltaY = deltaY;
        }

        int getDeltaX()
        {
            return deltaX;
        }

        int getDeltaY()
        {
            return deltaY;
        }

        static Direction horizontal(int delta)
        {
            return delta > 0 ? EAST : WEST;
        }

        static Direction vertical(int delta)
        {
            return delta > 0 ? NORTH : SOUTH;
        }

        Direction opposite()
        {
            switch (this)
            {
                case NORTH:
                    return SOUTH;
                case EAST:
                    return WEST;
                case SOUTH:
                    return NORTH;
                case WEST:
                    return EAST;
                default:
                    throw new IllegalStateException("Unknown direction " + this);
            }
        }
    }

    static final class SceneTile
    {
        private final int x;
        private final int y;

        SceneTile(int x, int y)
        {
            this.x = x;
            this.y = y;
        }

        int getX()
        {
            return x;
        }

        int getY()
        {
            return y;
        }

        SceneTile translate(int deltaX, int deltaY)
        {
            return new SceneTile(x + deltaX, y + deltaY);
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof SceneTile))
            {
                return false;
            }
            SceneTile tile = (SceneTile) other;
            return x == tile.x && y == tile.y;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(x, y);
        }
    }

    static final class PushPlan
    {
        private final Direction direction;
        private final SceneTile standTile;
        private final SceneTile expectedMirrorTile;
        private final boolean finalAim;

        PushPlan(Direction direction, SceneTile standTile, SceneTile expectedMirrorTile,
                 boolean finalAim)
        {
            this.direction = direction;
            this.standTile = standTile;
            this.expectedMirrorTile = expectedMirrorTile;
            this.finalAim = finalAim;
        }

        Direction getDirection()
        {
            return direction;
        }

        SceneTile getStandTile()
        {
            return standTile;
        }

        SceneTile getExpectedMirrorTile()
        {
            return expectedMirrorTile;
        }

        boolean isFinalAim()
        {
            return finalAim;
        }
    }

    static final class AttackState
    {
        private SceneTile activeWardrobe;
        private long activeCycle = Long.MIN_VALUE;
        private long inferredCycle;
        private SceneTile pendingFrom;
        private SceneTile pendingExpected;
        private boolean pendingFinalAim;
        private long pendingDeadline;
        private boolean aimedForCurrentAttack;
        private boolean failedForCurrentAttack;

        boolean observe(SceneTile mirror, SceneTile wardrobe, long now)
        {
            if (wardrobe == null)
            {
                return observe(mirror, null, Long.MIN_VALUE, now);
            }
            if (activeWardrobe == null || !wardrobe.equals(activeWardrobe))
            {
                inferredCycle++;
            }
            return observe(mirror, wardrobe, inferredCycle, now);
        }

        boolean observe(SceneTile mirror, SceneTile wardrobe, long cycle, long now)
        {
            if (wardrobe == null)
            {
                reset();
                return false;
            }
            if (cycle != activeCycle || !wardrobe.equals(activeWardrobe))
            {
                activeWardrobe = wardrobe;
                activeCycle = cycle;
                clearPending();
                aimedForCurrentAttack = false;
                failedForCurrentAttack = false;
            }
            if (pendingExpected == null || mirror == null)
            {
                return false;
            }
            if (mirror.equals(pendingExpected))
            {
                aimedForCurrentAttack = pendingFinalAim;
                clearPending();
                return true;
            }
            if (!mirror.equals(pendingFrom))
            {
                clearPending();
                return true;
            }
            if (now - pendingDeadline >= 0)
            {
                failedForCurrentAttack = true;
                clearPending();
            }
            return false;
        }

        boolean canDispatch(long now)
        {
            if (aimedForCurrentAttack || failedForCurrentAttack)
            {
                return false;
            }
            if (pendingExpected != null && now - pendingDeadline >= 0)
            {
                failedForCurrentAttack = true;
                clearPending();
                return false;
            }
            return pendingExpected == null;
        }

        void recordDispatch(SceneTile mirror, PushPlan plan, long now, long timeout)
        {
            pendingFrom = mirror;
            pendingExpected = plan.getExpectedMirrorTile();
            pendingFinalAim = plan.isFinalAim();
            pendingDeadline = now + timeout;
        }

        void reset()
        {
            activeWardrobe = null;
            activeCycle = Long.MIN_VALUE;
            aimedForCurrentAttack = false;
            failedForCurrentAttack = false;
            clearPending();
        }

        private void clearPending()
        {
            pendingFrom = null;
            pendingExpected = null;
            pendingFinalAim = false;
            pendingDeadline = 0;
        }
    }

    static final class CueState
    {
        private long nextCycle;
        private WardrobeCue cue;

        synchronized boolean record(int graphicId, SceneTile tile, int worldViewId)
        {
            if (graphicId != 483 || tile == null)
            {
                return false;
            }
            cue = new WardrobeCue(tile, worldViewId, ++nextCycle);
            return true;
        }

        synchronized WardrobeCue snapshot()
        {
            return cue;
        }

        synchronized void reset()
        {
            cue = null;
        }
    }

    static final class WardrobeCue
    {
        private final SceneTile tile;
        private final int worldViewId;
        private final long cycle;

        private WardrobeCue(SceneTile tile, int worldViewId, long cycle)
        {
            this.tile = tile;
            this.worldViewId = worldViewId;
            this.cycle = cycle;
        }

        SceneTile getTile()
        {
            return tile;
        }

        int getWorldViewId()
        {
            return worldViewId;
        }

        long getCycle()
        {
            return cycle;
        }
    }
}
