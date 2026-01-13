package me.siwannie.partygames;

import me.siwannie.partygames.admin.AdminGUI;
import me.siwannie.partygames.admin.AdminListeners;
import me.siwannie.partygames.admin.AdminSession;
import me.siwannie.partygames.commands.PartyGamesCommandCenter;
import me.siwannie.partygames.events.GameListeners;
import me.siwannie.partygames.utils.ConfigManager;
import me.siwannie.partygames.utils.PartyGamesPlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;


public final class PartyGames extends JavaPlugin implements Listener {
    private static PartyGames instance;
    private AdminSession adminSession;
    private GameManager gameManager;
    private GameListeners gameListenersInstance;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        ConfigManager.load(this);
        AdminGUI.loadConfigurableSettings(this);

        GameManager.init(this);
        this.gameManager = GameManager.getInstance();

        this.adminSession = new AdminSession(this);
        if (this.gameManager != null) {
            this.gameListenersInstance = new GameListeners(this, this.gameManager);
        } else {
            getLogger().severe("GameManager failed to initialize! GameListeners might not work correctly.");
        }

        registerListeners();

        PartyGamesCommandCenter commandCenter = new PartyGamesCommandCenter(this);
        if (getCommand("partygames") != null) getCommand("partygames").setExecutor(commandCenter);
        if (getCommand("pg") != null) getCommand("pg").setExecutor(commandCenter);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PartyGamesPlaceholderExpansion(this).register();
            getLogger().info("PartyGames PlaceholderAPI Expansion registered successfully!");
        } else {
            getLogger().warning("PlaceholderAPI not found, PartyGames placeholders will not be available.");
        }
        getLogger().info("PartyGames has been enabled.");
    }

    @Override
    public void onDisable() {
        if (this.gameManager != null && this.gameManager.getPointsManager() != null) {
            this.gameManager.getPointsManager().savePoints();
        }
        getLogger().info("PartyGames has been disabled.");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new AdminListeners(), this);
        getServer().getPluginManager().registerEvents(adminSession, this);
        if (this.gameListenersInstance != null) {
            getServer().getPluginManager().registerEvents(this.gameListenersInstance, this);
        } else {
            getLogger().warning("GameListenersInstance is null, not registering its events. This might be due to GameManager initialization failure.");
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    public static PartyGames getInstance() { return instance; }
    public AdminSession getAdminSession() { return adminSession; }
    public GameManager getGameManager() {

        if (this.gameManager == null) {
            getLogger().warning("GameManager was accessed while null. Attempting to re-initialize.");
            GameManager.init(this);
            this.gameManager = GameManager.getInstance();
        }
        return gameManager;
    }
    public GameListeners getGameListenersInstance() { return gameListenersInstance; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String rawCommand = event.getMessage();
        String commandLowerCase = rawCommand.toLowerCase();

        String prefixToCheck = AdminSession.INTERNAL_COMMAND_PREFIX.toLowerCase();

        if (commandLowerCase.startsWith(prefixToCheck + " ")) {

            event.setCancelled(true);

            String action = rawCommand.substring(prefixToCheck.length() + 1).trim();

            if (adminSession != null && !action.isEmpty()) {
                final String finalAction = action;
                Bukkit.getScheduler().runTask(this, () -> {
                    adminSession.processAdminAction(player, finalAction.toLowerCase());
                });
            }
        }
    }
}