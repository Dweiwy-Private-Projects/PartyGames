package me.siwannie.partygames;

import me.siwannie.partygames.admin.AdminGUI;
import me.siwannie.partygames.games.*;
import me.siwannie.partygames.players.PlayerData;
import me.siwannie.partygames.players.PointsManager;
import me.siwannie.partygames.utils.GameTimer;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

public class GameManager {

    private final PointsManager pointsManager;
    private static GameManager instance;
    private final JavaPlugin plugin;
    private final List<Class<? extends Game>> gameQueue = new ArrayList<>();
    private YamlConfiguration messagesConfig;
    private Game currentGame;
    private int currentGameIndex;
    private int transitionBufferSeconds;
    private GameTimer gameTimer;
    private BukkitRunnable bufferCountdownTask;
    private boolean paused = false;
    private final List<UUID> lobbyQueue = new ArrayList<>();
    private boolean gameStarted = false;
    private int currentBufferTimeLeft = 0;
    private int currentActualGameTimeLeft = 0;
    private String currentNextGameName = "Loading...";
    private boolean inBuffer = false;

    public static void init(JavaPlugin plugin) {
        if (instance == null) {
            instance = new GameManager(plugin);
        }
    }

    public static GameManager getInstance() {
        return instance;
    }

    private GameManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.currentGameIndex = 0;
        this.pointsManager = new PointsManager();
        this.transitionBufferSeconds = plugin.getConfig().getInt("settings.transition_buffer_seconds", 35);
        loadMessages();
        loadGameQueueFromConfig();
    }

    private void loadGameQueueFromConfig() {
        gameQueue.clear();
        FileConfiguration config = plugin.getConfig();
        List<String> configuredGameIds = config.getStringList("settings.game_queue");
        List<String> disabledGames = config.getStringList("disabled-games");

        Map<String, Class<? extends Game>> availableGames = new HashMap<>();
        availableGames.put("shearmadness", ShearMadness.class);
        availableGames.put("colorchaos", ColorChaos.class);
        availableGames.put("bombdefusal", BombDefusal.class);
        availableGames.put("targetterror", TargetTerror.class);
        availableGames.put("pillarsoffortune", PillarsOfFortune.class);
        availableGames.put("elytragauntlet", ElytraGauntlet.class);
        availableGames.put("echoesofthedeep", EchoesOfTheDeep.class);
        availableGames.put("zombieoutbreak", ZombieOutbreak.class);

        for (String gameIdFromQueue : configuredGameIds) {
            String gameId = gameIdFromQueue.toLowerCase();
            if (disabledGames.stream().anyMatch(dg -> dg.equalsIgnoreCase(gameId))) {
                plugin.getLogger().info("[GameManager] Game '" + gameId + "' is in queue but disabled. Skipping.");
                continue;
            }
            Class<? extends Game> gameClass = availableGames.get(gameId);
            if (gameClass != null) {
                if (!gameQueue.contains(gameClass)) {
                    gameQueue.add(gameClass);
                }
            } else {
                plugin.getLogger().warning("[GameManager] Unknown game ID '" + gameIdFromQueue + "' in game_queue. Skipping.");
            }
        }
        if (gameQueue.isEmpty() && !configuredGameIds.isEmpty()) {
            plugin.getLogger().severe("[GameManager] No ENABLED games found in 'settings.game_queue'.");
        } else if (configuredGameIds.isEmpty()){
            plugin.getLogger().severe("[GameManager] 'settings.game_queue' in config.yml is empty.");
        }
    }

    public void reloadGameQueueFromConfig() {
        plugin.getLogger().info("[GameManager] Reloading game queue from configuration...");
        loadGameQueueFromConfig();
    }

    public void reloadAllConfigurableSettings() {
        plugin.reloadConfig();
        this.transitionBufferSeconds = plugin.getConfig().getInt("settings.transition_buffer_seconds", 35);
        plugin.getLogger().info("[GameManager] Transition buffer seconds reloaded: " + this.transitionBufferSeconds);
        reloadGameQueueFromConfig();
        PartyGames mainPlugin = (PartyGames) plugin;
        if (mainPlugin.getGameListenersInstance() != null) {
            mainPlugin.getGameListenersInstance().reloadConfigSettings();
        }
        AdminGUI.loadConfigurableSettings(mainPlugin);
        plugin.getLogger().info("[GameManager] Core settings reloaded.");
    }

    private void loadMessages() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void startNextGameOrBuffer() {
        if (isPaused()) {
            broadcast("§6§lParty§e§lGames §7» §eGames are paused. Use /pg pause to resume.");
            return;
        }
        if (inBuffer) {
            plugin.getLogger().warning("Already in buffer. Ignoring startNextGameOrBuffer call.");
            broadcast("§6§lParty§e§lGames §7» §eThe next game is already starting!");
            return;
        }

        if (!gameStarted && currentGameIndex == 0) {
            loadGameQueueFromConfig();
        }

        if (gameStarted && currentGameIndex >= gameQueue.size()) {
            broadcast("§6§lParty§e§lGames §7» §6All games completed! Displaying final scores:");
            showLeaderboard();
            resetPostGameCycle();
            return;
        }

        boolean isAttemptingFreshSeriesStart = (!gameStarted && currentGameIndex == 0);
        if (isAttemptingFreshSeriesStart) {
            if (gameQueue.isEmpty()) {
                broadcast("§6§lParty§e§lGames §7» §cCannot start PartyGames: No enabled games in the game queue. Check config.");
                return;
            }
            if (lobbyQueue.isEmpty()) {
                broadcast("§6§lParty§e§lGames §7» §cCannot start PartyGames: The player queue is empty. Players must use §e/pg join§c.");
                return;
            }
        }

        Class<? extends Game> nextGameClass = null;
        if (currentGameIndex < gameQueue.size()) {
            nextGameClass = gameQueue.get(currentGameIndex);
        }

        if (nextGameClass == null) {
            broadcast("§6§lParty§e§lGames §7» §cCould not determine the next game. Ending series.");
            showLeaderboard();
            resetPostGameCycle();
            return;
        }

        final Class<? extends Game> finalNextGameClass = nextGameClass;
        inBuffer = true;
        if (!gameStarted) gameStarted = true;

        this.currentBufferTimeLeft = transitionBufferSeconds;
        this.currentNextGameName = "Loading...";
        broadcast("§6§lParty§e§lGames §7» §ePreparing the next game...");

        bufferCountdownTask = new BukkitRunnable() {
            int timeLeft = transitionBufferSeconds;
            String runnableLocalNextGameName = "Loading...";
            List<String> explanationLinesToSend = Collections.singletonList("Loading explanation...");

            @Override
            public void run() {
                if (isPaused()) return;
                GameManager.this.currentBufferTimeLeft = timeLeft;
                if (timeLeft <= 0) {
                    this.cancel();
                    inBuffer = false;
                    GameManager.this.currentBufferTimeLeft = 0;
                    startGameInternal(finalNextGameClass);
                    return;
                }
                if (timeLeft == transitionBufferSeconds) {
                    try {
                        Game tempGame = finalNextGameClass.getConstructor(JavaPlugin.class).newInstance(plugin);
                        runnableLocalNextGameName = tempGame.getName();
                        explanationLinesToSend = tempGame.getFormattedExplanationLines();
                        GameManager.this.currentNextGameName = runnableLocalNextGameName;
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error getting next game info for buffer: " + e.getMessage());
                        e.printStackTrace();
                        runnableLocalNextGameName = "Error Loading Game";
                        explanationLinesToSend = Arrays.asList("§cError loading game explanation.", "§cPlease check console & contact an admin.");
                        GameManager.this.currentNextGameName = "Error";
                    }
                    String lineBreak = ChatColor.GRAY + "§m                                                  ";
                    String header = ChatColor.GOLD + "" + ChatColor.BOLD + "        » PartyGames «";
                    String footer = lineBreak;

                    Set<Player> playersToMessage = new HashSet<>();
                    for(UUID uuid : lobbyQueue) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) playersToMessage.add(p);
                    }
                    if (GameManager.this.currentGame != null && GameManager.this.currentGame.getParticipants() != null) {
                        playersToMessage.addAll(GameManager.this.currentGame.getParticipants());
                    }

                    for (Player p : playersToMessage) {
                        if (p != null && p.isOnline()) {
                            p.sendMessage(lineBreak);
                            p.sendMessage(header);
                            p.sendMessage("");
                            p.sendMessage(ChatColor.GOLD + "Next Game: " + ChatColor.AQUA + runnableLocalNextGameName);
                            p.sendMessage("");
                            for (String line : explanationLinesToSend) {
                                p.sendMessage(line);
                            }
                            p.sendMessage("");
                            p.sendMessage(footer);
                        }
                    }
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    String bufferMsg = ChatColor.GOLD + "» " + ChatColor.YELLOW + "Next Game Starts In: " + ChatColor.WHITE + timeLeft + "s" + ChatColor.GOLD + " «";
                    p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(bufferMsg));
                }
                if (timeLeft <= 3) {
                    sendTitleToAll("§7Next Game:", "§e" + runnableLocalNextGameName + " §fin " + ChatColor.YELLOW + timeLeft + "s...", 0, 25, 5);
                }
                timeLeft--;
            }
        };
        bufferCountdownTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void startGameInternal(Class<? extends Game> gameClass) {
        Game gameInstanceToStart = null;
        try {
            gameInstanceToStart = gameClass.getConstructor(JavaPlugin.class).newInstance(plugin);
            this.currentGame = gameInstanceToStart;

            teleportPlayersToGame(this.currentGame.getId());

            if (this.currentGame.getParticipants().isEmpty()) {
                broadcast("§6§lParty§e§lGames §7» §cNo players joined §e" + this.currentGame.getName() + "§c from the queue. Skipping game.");
                endCurrentGame();
                return;
            }

            String startingMessage = "§6§lParty§e§lGames §7» §aStarting: §e" + this.currentGame.getName();
            new ArrayList<>(this.currentGame.getParticipants()).forEach(participant -> {
                if (participant != null && participant.isOnline()) {
                    participant.sendMessage(ChatColor.translateAlternateColorCodes('&', startingMessage));
                }
            });

            final Game gameBeingActivelyStarted = this.currentGame;
            gameBeingActivelyStarted.startGame();

            if (this.currentGame == null || this.currentGame != gameBeingActivelyStarted) {
                plugin.getLogger().info("[GameManager] Game " + gameClass.getSimpleName() +
                        " appears to have ended or changed during its own startup. GameManager timer will not proceed for this instance.");
                return;
            }

            this.currentActualGameTimeLeft = this.currentGame.getDuration();
            final int totalGameDuration = this.currentGame.getDuration();

            gameTimer = new GameTimer(
                    totalGameDuration,
                    plugin,
                    timeLeft -> {
                        if (!isPaused()) {
                            GameManager.this.currentActualGameTimeLeft = timeLeft;
                            if (timeLeft == totalGameDuration / 2 || timeLeft == 60 || timeLeft == 30) {
                                sendTitleToCurrentGameParticipants("§6Ending in", "§e" + timeLeft + " seconds", 10, 40, 10);
                            }
                            if (timeLeft <= 10 && timeLeft > 0) {
                                sendTitleToCurrentGameParticipants("§6Ending in", "§c" + timeLeft + "...", 0, 25, 5);
                            }
                        }
                    },
                    this::endCurrentGame
            );
            gameTimer.start();

        } catch (Exception e) {
            e.printStackTrace();
            String gameNameToLog = (gameInstanceToStart != null && gameInstanceToStart.getName() != null) ? gameInstanceToStart.getName() : gameClass.getSimpleName();
            broadcast("§6§lParty§e§lGames §7» §cFailed to start game: " + gameNameToLog + ". Advancing to next.");

            if (this.currentGame == gameInstanceToStart && this.currentGame != null) {
                if (gameTimer != null) {
                    gameTimer.cancel();
                    gameTimer = null;
                }
                this.currentGame = null;
            } else if (this.currentGame != null) {
                plugin.getLogger().warning("[GameManager] Exception during " + gameClass.getSimpleName() +
                        " startup, but currentGame was " + this.currentGame.getName() + ". Ending the latter.");
                endCurrentGame();
            } else {
                this.currentGame = null;
            }

            this.currentActualGameTimeLeft = 0;
            this.inBuffer = false;
            this.currentGameIndex++;

            if (this.currentGameIndex >= gameQueue.size()) {
                broadcast("§6§lParty§e§lGames §7» §6All games attempted after failure! Displaying final scores:");
                showLeaderboard();
                resetPostGameCycle();
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, this::startNextGameOrBuffer, 20L * 1);
            }
        }
    }


    public void endCurrentGame() {
        Game gameThatEnded = this.currentGame;
        String endedGameName = "Unknown Game";

        if (gameTimer != null) {
            gameTimer.cancel();
            gameTimer = null;
        }

        this.currentGame = null;

        if (gameThatEnded != null) {
            endedGameName = gameThatEnded.getName();
            gameThatEnded.endGame();
            this.pointsManager.savePoints();
        }

        this.currentActualGameTimeLeft = 0;
        this.inBuffer = false;
        this.paused = false;
        this.currentGameIndex++;

        if (this.currentGameIndex >= gameQueue.size()) {
            broadcast("§6§lParty§e§lGames §7» §6All games completed! Displaying final scores:");
            showLeaderboard();
            resetPostGameCycle();
            return;
        }

        if (gameThatEnded != null) {
            broadcast("§6§lParty§e§lGames §7» §6Game Over: §e" + endedGameName + "§6! Preparing for next game...");
        } else {
            broadcast("§6§lParty§e§lGames §7» §cPrevious game attempt failed. Preparing for next game...");
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::startNextGameOrBuffer, 20L * 3);
    }

    private void resetPostGameCycle() {
        gameStarted = false;
        currentGameIndex = 0;
        lobbyQueue.clear();
        plugin.getLogger().info("PartyGames cycle finished. Ready for a new start or manual initiation.");
    }

    public void reset() {
        broadcast("§6§lParty§e§lGames §7» §cATTENTION! PartyGames is being reset by an Admin!");
        if (currentGame != null) {
            currentGame.endGame();
            currentGame = null;
        }
        if (gameTimer != null) {
            gameTimer.cancel();
            gameTimer = null;
        }
        if (bufferCountdownTask != null) {
            bufferCountdownTask.cancel();
            bufferCountdownTask = null;
        }
        for(UUID uuid : lobbyQueue) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage("§6§lParty§e§lGames §7» §eYou have been removed from the queue due to a game reset.");
        }
        lobbyQueue.clear();
        pointsManager.savePoints();
        pointsManager.resetAllPoints();
        currentGameIndex = 0;
        gameStarted = false;
        inBuffer = false;
        paused = false;
        currentActualGameTimeLeft = 0;
        currentBufferTimeLeft = 0;
        currentNextGameName = "Loading...";
        broadcast("§6§lParty§e§lGames §7» §aPartyGames has been reset. Join the queue to play again!");
    }

    public void pauseCurrentGame() {
        if (isPaused()) {
            broadcast("§6§lParty§e§lGames §7» §eGame is already paused.");
            return;
        }
        if (inBuffer && bufferCountdownTask != null && !bufferCountdownTask.isCancelled()) {
            paused = true;
            broadcast("§6§lParty§e§lGames §7» §eGame starting sequence paused. Use /pg pause to resume.");
        } else if (currentGame != null && gameTimer != null) {
            gameTimer.pause();
            paused = true;
            broadcast("§6§lParty§e§lGames §7» §eGame paused. Use /pg pause to resume.");
        } else {
            broadcast("§6§lParty§e§lGames §7» §cNothing active to pause.");
        }
    }

    public void resumeCurrentGame() {
        if (!isPaused()) {
            broadcast("§6§lParty§e§lGames §7» §eGame is not currently paused.");
            return;
        }
        paused = false;
        if (inBuffer && bufferCountdownTask != null && !bufferCountdownTask.isCancelled()) {
            broadcast("§6§lParty§e§lGames §7» §aResuming game sequence... Buffer will continue.");
        } else if (currentGame != null && gameTimer != null) {
            gameTimer.resume();
            broadcast("§6§lParty§e§lGames §7» §aGame resumed.");
        } else {
            paused = true;
            broadcast("§6§lParty§e§lGames §7» §cNothing to resume, or game/buffer ended while paused.");
        }
    }

    public Game getCurrentGame() { return currentGame; }
    public JavaPlugin getPlugin() { return plugin; }
    public boolean isPaused() { return paused; }
    public boolean isGameRunning() { return currentGame != null && gameStarted && !inBuffer && !paused; }
    public String getCurrentGameName() { return currentGame != null ? currentGame.getName() : "None"; }
    public String getCurrentGameNameLegacy() {
        if (currentGame != null && currentGame.getName() != null) {
            return ChatColor.translateAlternateColorCodes('&', currentGame.getName());
        }
        return "None";
    }
    public boolean isGameStarted() { return gameStarted; }
    public boolean isInBuffer() { return inBuffer; }
    public int getBufferCountdownSeconds() { return this.inBuffer && !this.isPaused() ? this.currentBufferTimeLeft : 0; }
    public int getCurrentActualGameTimeLeft() { return isGameRunning() ? this.currentActualGameTimeLeft : 0; }
    public String getNextGameNameForListener() { return this.inBuffer ? this.currentNextGameName : "N/A"; }
    public int getInternalGameIndex() { return this.currentGameIndex; }
    public List<Class<? extends Game>> getGameQueue() { return Collections.unmodifiableList(this.gameQueue); }
    public String getGameExplanation(String gameId) {
        String path = "game_explanations." + gameId.toLowerCase();
        if (messagesConfig == null) {
            loadMessages();
            if (messagesConfig == null) {
                plugin.getLogger().severe("messages.yml could not be loaded for game explanations!");
                return "§cError: Explanations unavailable.";
            }
        }
        return messagesConfig.getString(path, "§7No explanation found for " + gameId + ".");
    }
    public void setTransitionBufferSeconds(int seconds) {
        if (seconds >= 0) {
            this.transitionBufferSeconds = seconds;
            plugin.getLogger().info("[GameManager] Transition Buffer Seconds updated to: " + seconds);
        } else {
            plugin.getLogger().warning("[GameManager] Invalid transition buffer seconds: " + seconds);
        }
    }

    public PointsManager getPointsManager() { return pointsManager; }
    public PlayerData getPlayerData(Player player) { return pointsManager.getPlayerData(player); }
    public void addPoints(Player player, int points) {
        PlayerData pd = pointsManager.getPlayerData(player);
        pd.addPoints(points);
        pointsManager.savePoints();
    }
    public void addPointsForGame(Player player, String gameId, int points) {
        PlayerData pd = pointsManager.getPlayerData(player);
        pd.addPointsForGame(gameId, points);
        pointsManager.savePoints();
    }

    public void addParticipant(Player player) {
        if (currentGame != null && !currentGame.getParticipants().contains(player)) {
            currentGame.getParticipants().add(player);
            player.sendMessage("§6§lParty§e§lGames §7» §aYou have joined the current game: §e" + currentGame.getName());
        } else if (currentGame == null && inBuffer) {
            player.sendMessage("§6§lParty§e§lGames §7» §eYou will join the next game starting soon!");
        }
    }
    public void removeParticipant(Player player) {
        if (currentGame != null) {
            boolean removed = currentGame.getParticipants().removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
            if (removed) {
                player.sendMessage("§6§lParty§e§lGames §7» §cYou have left the current game.");
            }
        }
    }

    public void addPlayerToLobbyQueue(Player player) {
        UUID playerUUID = player.getUniqueId();

        if (currentGame != null && currentGame.getParticipants().stream().anyMatch(p -> p.getUniqueId().equals(playerUUID))) {
            player.sendMessage("§6§lParty§e§lGames §7» §cYou are already in the current game!");
            return;
        }

        if (isGameRunning() && currentGame != null) {
            Location target = getGameTeleportLocation(currentGame.getId());
            if (target != null) {
                currentGame.getParticipants().add(player);
                player.teleport(target);
                player.sendMessage("§6§lParty§e§lGames §7» §aYou have rejoined the current game: §e" + currentGame.getName());
                currentGame.handlePlayerJoinMidGame(player);
            } else {
                player.sendMessage("§6§lParty§e§lGames §7» §cCould not rejoin: Game location is not set.");
            }
            return;
        }

        if (lobbyQueue.contains(playerUUID)) {
            player.sendMessage("§6§lParty§e§lGames §7» §cYou're already in the queue!");
            return;
        }

        lobbyQueue.add(playerUUID);
        broadcast("§6§lParty§e§lGames §7» §a" + player.getName() + " §ejoined the queue. §7(#" + lobbyQueue.size() + ")");
        if (inBuffer) {
            player.sendMessage("§6§lParty§e§lGames §7» §eThe next game is starting soon! You're in the queue.");
        } else {
            player.sendMessage("§6§lParty§e§lGames §7» §aYou’ve joined the queue. Waiting for the game to start...");
        }
    }

    public void removePlayerFromLobbyQueue(Player player) {
        UUID playerUUID = player.getUniqueId();
        boolean removedFromLobby = lobbyQueue.remove(playerUUID);
        boolean removedFromGame = false;

        if (currentGame != null) {
            removedFromGame = currentGame.getParticipants().removeIf(p -> p.getUniqueId().equals(playerUUID));
            if(removedFromGame) {
                player.sendMessage("§6§lParty§e§lGames §7» §cYou have left the current game.");
            }
        }

        if (removedFromLobby) {
            if(!removedFromGame) player.sendMessage("§6§lParty§e§lGames §7» §cYou have left the queue.");
            broadcast("§6§lParty§e§lGames §7» §c" + player.getName() + " §eleft the queue. §7(Remaining: " + lobbyQueue.size() + ")");
        } else if (!removedFromGame) {
            player.sendMessage("§6§lParty§e§lGames §7» §cYou were not in the queue or current game.");
        }
    }

    public List<UUID> getLobbyQueue() { return Collections.unmodifiableList(new ArrayList<>(lobbyQueue)); }

    public void showLeaderboard() {
        var leaderboard = pointsManager.getLeaderboard();
        broadcast("§6§lParty§e§lGames §7» §6§l--- Final Leaderboard ---");
        if (leaderboard.isEmpty()) {
            broadcast("§6§lParty§e§lGames §7» §7No points recorded yet!");
            return;
        }
        int rank = 1;
        for (PlayerData pd : leaderboard) {
            Player player = Bukkit.getPlayer(pd.getUuid());
            String playerName = player != null ? player.getName() : "Unknown Player (ID: " + pd.getUuid().toString().substring(0,8) + ")";
            String rankColor = (rank == 1) ? "§6" : (rank == 2) ? "§7" : (rank == 3) ? "§c" : "§e";
            broadcast("§6§lParty§e§lGames §7» " + rankColor + "#" + rank + " §f" + playerName + ": §b" + pd.getTotalPoints() + " points");
            rank++;
            if (rank > 10) break;
        }
    }

    public void removeGameById(String gameId) {
        gameQueue.removeIf(gameClass -> {
            try {
                return gameClass.getDeclaredConstructor(JavaPlugin.class).newInstance(plugin).getId().equalsIgnoreCase(gameId);
            } catch (Exception e) { e.printStackTrace(); return false; }
        });
    }
    private Location getGameTeleportLocation(String gameId) {
        FileConfiguration config = plugin.getConfig();
        String basePath = "games." + gameId + ".teleport";
        if (!config.contains(basePath + ".world")) return null;
        World world = Bukkit.getWorld(config.getString(basePath + ".world"));
        if (world == null) return null;
        return new Location(world,
                config.getDouble(basePath + ".x"), config.getDouble(basePath + ".y"), config.getDouble(basePath + ".z"),
                (float) config.getDouble(basePath + ".yaw", 0), (float) config.getDouble(basePath + ".pitch", 0));
    }

    public void teleportPlayersToGame(String gameId) {
        Location target = getGameTeleportLocation(gameId);
        if (target == null) {
            plugin.getLogger().warning("No valid teleport location for game ID: " + gameId + ". Players not teleported.");
            broadcast("§6§lParty§e§lGames §7» §cGame " + gameId + " has a location error. Admin check needed.");
            return;
        }
        if (this.currentGame == null) {
            plugin.getLogger().warning("teleportPlayersToGame called but currentGame is null for " + gameId);
            return;
        }

        if (this.currentGame.getParticipants() != null) {
            this.currentGame.getParticipants().clear();
        } else {
            plugin.getLogger().warning("teleportPlayersToGame: currentGame.getParticipants() was null for " + gameId + ". This should not happen.");
            return;
        }

        List<UUID> playersToRemoveFromLobby = new ArrayList<>();
        for(UUID uuid : lobbyQueue){
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.teleport(target);
                if (this.currentGame.getParticipants() != null) {
                    this.currentGame.getParticipants().add(p);
                }
                playersToRemoveFromLobby.add(uuid);
            }
        }
        lobbyQueue.removeAll(playersToRemoveFromLobby);
    }

    public void broadcast(String message) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    public void sendTitleToCurrentGameParticipants(String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (this.currentGame == null || this.currentGame.getParticipants() == null || this.currentGame.getParticipants().isEmpty()) return;
        String finalTitle = ChatColor.translateAlternateColorCodes('&', title);
        String finalSubtitle = ChatColor.translateAlternateColorCodes('&', subtitle);
        new ArrayList<>(this.currentGame.getParticipants()).forEach(p -> {
            if (p != null && p.isOnline()) {
                p.sendTitle(finalTitle, finalSubtitle, fadeInTicks, stayTicks, fadeOutTicks);
            }
        });
    }

    private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        String finalTitle = ChatColor.translateAlternateColorCodes('&', title);
        String finalSubtitle = ChatColor.translateAlternateColorCodes('&', subtitle);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) {
                p.sendTitle(finalTitle, finalSubtitle, fadeIn, stay, fadeOut);
            }
        }
    }
}