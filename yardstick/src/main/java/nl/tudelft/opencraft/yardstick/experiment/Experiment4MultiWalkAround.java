/*
 * Yardstick: A Benchmark for Minecraft-like Services
 * Copyright (C) 2020 AtLarge Research
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package nl.tudelft.opencraft.yardstick.experiment;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import nl.tudelft.opencraft.yardstick.bot.Bot;
import nl.tudelft.opencraft.yardstick.bot.ai.task.TaskExecutor;
import nl.tudelft.opencraft.yardstick.bot.ai.task.TaskStatus;
import nl.tudelft.opencraft.yardstick.bot.ai.task.WalkTaskExecutor;
import nl.tudelft.opencraft.yardstick.bot.world.Block;
import nl.tudelft.opencraft.yardstick.bot.world.ChunkNotLoadedException;
import nl.tudelft.opencraft.yardstick.bot.world.ConnectException;
import nl.tudelft.opencraft.yardstick.model.SimpleMovementModel;
import nl.tudelft.opencraft.yardstick.util.Vector3d;
import nl.tudelft.opencraft.yardstick.util.Vector3i;

public class Experiment4MultiWalkAround extends Experiment {

    private final List<Bot> botList = Collections.synchronizedList(new ArrayList<>());
    private final List<Future<Bot>> connectingBots = new ArrayList<>();
    private SimpleMovementModel movement;

    private int botsTotal = 0;
    private long startMillis;
    private int durationInSeconds;
    private int secondsBetweenJoin;
    private int numberOfBotsPerJoin;
    private boolean singleStandStill;
    private boolean first = true;
    private int seed;
    private final Map<Bot, Vector3d> botSpawnLocations = new HashMap<>();
    private final Map<Bot, Integer> botIds = new HashMap<>();
    private Boolean[] loadedIn;
    private int currentBotID = 0;
    private long lastJoin = System.currentTimeMillis();
    private boolean sendPings;
    private int pingInterval;

    ScheduledExecutorService messageThread;

    public Experiment4MultiWalkAround() {
        super(4, "Bots walking around based on a movement model for Second Life.");
    }

    public Experiment4MultiWalkAround(int num, String desc) {
        super(num, desc);
    }

    @Override
    protected void before() {
        this.botsTotal = Integer.parseInt(options.experimentParams.get("bots"));
        this.loadedIn = new Boolean[this.botsTotal];
        Arrays.fill(this.loadedIn, false);
        this.durationInSeconds = Integer.parseInt(options.experimentParams.getOrDefault("duration", "600"));
        this.secondsBetweenJoin = Integer.parseInt(options.experimentParams.getOrDefault("joininterval", "1"));
        this.numberOfBotsPerJoin = Integer.parseInt(options.experimentParams.getOrDefault("numbotsperjoin", "1"));
        this.seed = Integer.parseInt(options.experimentParams.getOrDefault("seed", "42"));
        this.singleStandStill = Boolean.parseBoolean(options.experimentParams.getOrDefault("singleStandStill", "false"));
        this.sendPings = Boolean.parseBoolean(options.experimentParams.getOrDefault("sendPings", "true"));
        this.pingInterval = Integer.parseInt(options.experimentParams.getOrDefault("pingInterval", "1"));
        this.movement = new SimpleMovementModel(
                Integer.parseInt(options.experimentParams.getOrDefault("boxDiameter", "32")),
                Boolean.parseBoolean(options.experimentParams.getOrDefault("spawnAnchor", "false")),
                this.seed
        );
        this.startMillis = System.currentTimeMillis();
    }

    @Override
    protected void tick() {
        synchronized (botList) {
            // Remove all bots that are not connected.
            List<Bot> disconnectedBots = botList.stream()
                    .filter(bot -> !bot.isJoined())
                    .collect(Collectors.toList());
            disconnectedBots.forEach(bot -> bot.disconnect("Bot is not connected"));
            botList.removeAll(disconnectedBots);

            // Add all new bots that are connected.
            List<Future<Bot>> newlyConnectedBots = connectingBots.stream()
                    .filter(Future::isDone)
                    .collect(Collectors.toList());
            newlyConnectedBots.forEach(fb -> {
                connectingBots.remove(fb);
                try {
                    botList.add(fb.get());
                } catch (InterruptedException | ExecutionException e) {
                    // ignore
                }
            });
        }
        if (System.currentTimeMillis() - this.lastJoin > secondsBetweenJoin * 1000
                && botList.size() <= this.botsTotal) {
            int botsToConnect = Math.min(this.numberOfBotsPerJoin, this.botsTotal - currentNumberOfBots());
            if(singleStandStill && first){
                connectFirst();
                botsToConnect-=1;
                first = false;
            }

            for (int i = 0; i < botsToConnect; i++) {
                connectNewBot();
            }
        }
        synchronized (botList) {
            for (Bot bot : botList) {
                botTick(bot);
            }
        }
    }
    void connectFirst(){
        long startTime = System.currentTimeMillis();

        try {
            Bot bot = createBot("still");
            loadedIn[0]=true;
            botIds.put(bot, currentBotID);
            this.currentBotID++;
            botSpawnLocations.put(bot, bot.getPlayer().getLocation());
            botList.add(bot);

            if(this.sendPings){
                messageThread = Executors.newSingleThreadScheduledExecutor();
                messageThread.scheduleAtFixedRate( new Runnable() {
                    @Override
                    public void run(){
                        bot.getController().sendRandomChatMessage(5);
                    }
                }, 0, this.pingInterval, TimeUnit.SECONDS);
            }

        } catch (ConnectException e) {
            logger.warning(String.format("Could not connect bot on %s:%d after %d ms.", options.host, options.port, System.currentTimeMillis() - startTime));
        }

        lastJoin = System.currentTimeMillis();
    }
    void connectNewBot() {
        connectingBots.add(CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                Bot bot;

                bot = createBot();

                botIds.put(bot, currentBotID);
                this.currentBotID++;
                botSpawnLocations.put(bot, bot.getPlayer().getLocation());
                return bot;
            } catch (ConnectException e) {
                logger.warning(String.format("Could not connect bot on %s:%d after %d ms.", options.host, options.port, System.currentTimeMillis() - startTime));
            }
            return null;
        }));
        lastJoin = System.currentTimeMillis();
    }

    private void botTick(Bot bot) {
        if(botIds.get(bot) == 0 && this.singleStandStill){
            // First bot stands still
            return;
        }
        if(!loadedIn[botIds.get(bot)]){
            Vector3d loc = bot.getPlayer().getLocation();
            Vector3i blockLoc = loc.intVector().add(new Vector3i(0, -2, 0));
            Block thisBlock;
            try{
                thisBlock = bot.getWorld().getBlockAt(blockLoc);
                loadedIn[botIds.get(bot)] = true;
            }catch (ChunkNotLoadedException e){
                // Not ready yet,
                return;
            }
        }
        if(Arrays.asList(loadedIn).contains(false)){
            // Not ready yet
            return;
        }

        TaskExecutor t = bot.getTaskExecutor();
        if (t == null || t.getStatus().getType() != TaskStatus.StatusType.IN_PROGRESS) {
            Vector3i newLocation = movement.newTargetLocation(bot);
            //bot.getLogger().info(String.format("Setting task for bot to walk to %s", newLocation));
            /*Vector3i underneath = new Vector3i(newLocation.getX(), newLocation.getY() - 1 , newLocation.getZ());
            try{
                bot.getLogger().info(String.format("Block at %s has state %s", newLocation, bot.getWorld().getBlockAt(underneath).getState() ));
            } catch(ChunkNotLoadedException e){}*/

            bot.setTaskExecutor(new WalkTaskExecutor(bot, newLocation));
        }
    }

    @Override
    protected boolean isDone() {

        boolean timeUp = System.currentTimeMillis() - this.startMillis > this.durationInSeconds * 1_000;
        if (timeUp) {
            return true;
        } else if (botList.size() > 0) {
            boolean allBotsDisconnected;
            synchronized (botList) {
                allBotsDisconnected = botList.stream().noneMatch(Bot::isJoined);
            }
            if (allBotsDisconnected) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void after() {
        messageThread.shutdown();
        for (Bot bot : botList) {
            bot.disconnect("disconnect");
        }
    }

    public long getStartMillis() {
        return startMillis;
    }

    public int getBotsTotal() {
        return botsTotal;
    }

    public int currentNumberOfBots() {
        return botList.size() + connectingBots.size();
    }

    public long getLastJoined() {
        return lastJoin;
    }

    public int joinIntervalInSeconds() {
        return secondsBetweenJoin;
    }

    public int getBotsPerJoin() {
        return numberOfBotsPerJoin;
    }

    public void disconnectBots(int num, String reason) {
        for (int i = 0; i < Math.min(num, botList.size()); i++) {
            botList.get(i).disconnect(reason);
        }
    }
}
