/*
 Copyright (c) 2013, DarkStorm (darkstorm@evilminecraft.net)
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package nl.tudelft.opencraft.yardstick.bot.ai.task;

import java.util.concurrent.*;
import java.util.logging.Logger;
import nl.tudelft.opencraft.yardstick.bot.Bot;
import nl.tudelft.opencraft.yardstick.bot.ai.pathfinding.PathNode;
import nl.tudelft.opencraft.yardstick.bot.entity.BotPlayer;
import nl.tudelft.opencraft.yardstick.bot.world.Block;
import nl.tudelft.opencraft.yardstick.bot.world.ChunkNotLoadedException;
import nl.tudelft.opencraft.yardstick.bot.world.Material;
import nl.tudelft.opencraft.yardstick.util.Vector3d;
import nl.tudelft.opencraft.yardstick.util.Vector3i;

public class WalkTask implements Task {

    private static double speed = 0.15, jumpFactor = 3, fallFactor = 4, liquidFactor = 0.5;
    private static int defaultTimeout = 6000;

    private final String shortName;
    private final Bot bot;
    private final Logger logger;
    private final ExecutorService service = Executors.newSingleThreadExecutor();
    private final Vector3i target;

    private final long startTime;

    private Future<PathNode> pathFuture;
    private PathNode nextStep;
    private int ticksSinceStepChange = 0;
    private int timeout = defaultTimeout;

    private TaskStatus status = TaskStatus.forInProgress();

    private Callable<PathNode> task = new Callable<PathNode>() {
        @Override
        public PathNode call() throws Exception {
            BotPlayer player = bot.getPlayer();
            PathNode start = bot.getPathFinder().search(player.getLocation().intVector(), target);
            return start;
        }
    };

    public WalkTask(final Bot bot, final Vector3i target) {
        this.bot = bot;
        this.target = target;
        this.shortName = "WalkTask[" + target.toString() + "]";
        this.logger = bot.getLogger().newSubLogger(shortName);

        pathFuture = service.submit(task);
        startTime = System.currentTimeMillis();
    }

    @Override
    public String getShortName() {
        return shortName;
    }

    @Override
    public TaskStatus tick() {

        if (pathFuture != null && !pathFuture.isDone()) {
            // If we're still calculating the path:
            // Check timeout
            if (timeout > 0 && System.currentTimeMillis() - startTime > timeout) {
                pathFuture.cancel(true);
                nextStep = null;
                return status = TaskStatus.forFailure("Path search timed out (" + timeout + " ms)");
            } else {
                return status = TaskStatus.forInProgress();
            }

        } else if (pathFuture != null && pathFuture.isDone() && !pathFuture.isCancelled()) {
            // If we've found a path successfully
            try {
                nextStep = pathFuture.get();
                ticksSinceStepChange = 0;
            } catch (InterruptedException e) {
                return status = TaskStatus.forFailure(e.getMessage(), e);
            } catch (ExecutionException e) {
                /*
                if (e.getCause() instanceof ChunkNotLoadedException) {
                    return status = TaskStatus.forInProgress();
                }*/
                return status = TaskStatus.forFailure(e.getMessage(), e.getCause());
            } finally {
                pathFuture = null;
            }
        }

        // If we have no more steps to do, we're done
        if (nextStep == null) {
            return status = TaskStatus.forSuccess();
        }

        BotPlayer player = bot.getPlayer();

        // Skip the step if the next step is close by
        if (nextStep.getNext() != null && player.getLocation().distanceSquared(nextStep.getNext().getLocation().doubleVector()) < 0.05) {
            nextStep = nextStep.getNext();
            ticksSinceStepChange = 0;
        }

        // If the player is too far away from the next step
        // Abort for now
        if (player.getLocation().distanceSquared(nextStep.getLocation().doubleVector()) > 5.0) {
            logger.info(String.format("Strayed from path. %s -> %s", player.getLocation(), nextStep.getLocation()));
            // TODO: Fix later
            //TaskStatus status = TaskStatus.forInProgress();
            //pathFuture = service.submit(task);
            return TaskStatus.forFailure("Strayed from path. %s -> %s");
        }

        // Keep track of how many ticks a step takes
        // If a step takes too many ticks, abort
        ticksSinceStepChange++;
        if (ticksSinceStepChange > 80) {
            nextStep = null;
            return status = TaskStatus.forFailure("Too many ticks since step change");
        }

        // Get locations
        Vector3d moveLoc = player.getLocation();
        Vector3i blockLoc = moveLoc.intVector().add(new Vector3i(0, -1, 0));
        Block thisBlock;

        try {
            thisBlock = bot.getWorld().getBlockAt(blockLoc);
        } catch (ChunkNotLoadedException e) {
            // TODO: Fix: Wait until chunk is loaded.
            logger.warning(String.format("Block under player: %s", blockLoc));
            logger.warning(String.format("Player at %s", moveLoc));
            return status = TaskStatus.forFailure(e.getMessage());
        }

        // Step
        Vector3i stepTargetBlock = nextStep.getLocation();
        if (stepTargetBlock == null) {
            return status = TaskStatus.forFailure("No next step");
        }
        Vector3d stepTarget = stepTargetBlock.doubleVector();

        // Stand on the center of a block
        stepTarget = stepTarget.add(0.5, 0, 0.5);

        // Calculate speed
        double moveSpeed = this.speed;
        boolean inLiquid = false; // TODO: player.isInLiquid();
        if (Material.getById(thisBlock.getTypeId()) == Material.SOUL_SAND) {
            if (Material.getById(thisBlock.getTypeId()) == Material.SOUL_SAND) {
                // Soulsand makes us shorter 8D
                stepTarget = stepTarget.add(0, -0.12, 0);
            }
            moveSpeed *= liquidFactor;
        } else if (inLiquid) {
            moveSpeed *= liquidFactor;
        }

        double stepX = stepTarget.getX(), stepY = stepTarget.getY(), stepZ = stepTarget.getZ();

        // See if we're climbing, or jumping
        if (moveLoc.getY() != stepY) {
            boolean canClimbBlock = false;
            try {
                canClimbBlock = bot.getPathFinder().getWorldPhysics().canClimb(moveLoc.intVector());
            } catch (ChunkNotLoadedException e) {
                return status = TaskStatus.forInProgress();
            }
            if (!inLiquid && !canClimbBlock) {
                if (moveLoc.getY() < stepY) {
                    moveSpeed *= jumpFactor;
                } else {
                    moveSpeed *= fallFactor;
                }
            }

            // Set new Y-coord
            double offsetY = moveLoc.getY() < stepY ? Math.min(moveSpeed, stepY - moveLoc.getY()) : Math.max(-moveSpeed, stepY - moveLoc.getY());
            moveLoc = moveLoc.add(new Vector3d(0, offsetY, 0));
        }

        if (moveLoc.getX() != stepX) {
            double offsetX = moveLoc.getX() < stepX ? Math.min(moveSpeed, stepX - moveLoc.getX()) : Math.max(-moveSpeed, stepX - moveLoc.getX());
            moveLoc = moveLoc.add(new Vector3d(offsetX, 0, 0));
        }

        if (moveLoc.getZ() != stepZ) {
            double offsetZ = moveLoc.getZ() < stepZ ? Math.min(moveSpeed, stepZ - moveLoc.getZ()) : Math.max(-moveSpeed, stepZ - moveLoc.getZ());
            moveLoc = moveLoc.add(new Vector3d(0, 0, offsetZ));
        }

        // Send new player location to server
        bot.getController().updateLocation(moveLoc);

        if (moveLoc.equals(stepTarget)) {
            nextStep = nextStep.getNext();
            ticksSinceStepChange = 0;
        }

        return status = TaskStatus.forInProgress();
    }

    @Override
    public void stop() {
        if (pathFuture != null && !pathFuture.isDone()) {
            pathFuture.cancel(true);
        }
        nextStep = null;
        this.service.shutdown();
    }

    @Override
    public TaskStatus getStatus() {
        return status;
    }

    public Vector3i getTarget() {
        return target;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * Walk speed, in blocks/tick. Default is 0.15.
     */
    public double getSpeed() {
        return speed;
    }

    /**
     * Set walk speed.
     *
     * @param speed Walk speed, in blocks/tick. Default is 0.15.
     */
    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double getJumpFactor() {
        return jumpFactor;
    }

    public void setJumpFactor(double jumpFactor) {
        this.jumpFactor = jumpFactor;
    }

    public double getFallFactor() {
        return fallFactor;
    }

    public void setFallFactor(double fallFactor) {
        this.fallFactor = fallFactor;
    }

    public double getLiquidFactor() {
        return liquidFactor;
    }

    public void setLiquidFactor(double liquidFactor) {
        this.liquidFactor = liquidFactor;
    }

    public boolean isMoving() {
        return nextStep != null;
    }

    public static double getDefaultSpeed() {
        return speed;
    }

    public static void setDefaultSpeed(double defaultSpeed) {
        WalkTask.speed = defaultSpeed;
    }

    public static double getDefaultJumpFactor() {
        return jumpFactor;
    }

    public static void setDefaultJumpFactor(double defaultJumpFactor) {
        WalkTask.jumpFactor = defaultJumpFactor;
    }

    public static double getDefaultFallFactor() {
        return fallFactor;
    }

    public static void setDefaultFallFactor(double defaultFallFactor) {
        WalkTask.fallFactor = defaultFallFactor;
    }

    public static double getDefaultLiquidFactor() {
        return liquidFactor;
    }

    public static void setDefaultLiquidFactor(double defaultLiquidFactor) {
        WalkTask.liquidFactor = defaultLiquidFactor;
    }

    public static int getDefaultTimeout() {
        return defaultTimeout;
    }

    public static void setDefaultTimeout(int defaultTimeout) {
        WalkTask.defaultTimeout = defaultTimeout;
    }
}
