package me.siwannie.partygames.events;

import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GameListeners implements Listener {

    private final JavaPlugin plugin;
    private final GameManager gameManager;
    private final Map<UUID, Long> playerChatCooldowns = new HashMap<>();

    private String msgJoinGameRunning;
    private String msgJoinGameInBuffer;
    private String msgJoinNoGameActive;

    private boolean cmdBlockerEnabled;
    private List<String> blockedCommandPrefixes;
    private String cmdBypassPermission;
    private String msgCommandBlocked;

    private boolean chatFormatEnabled;
    private String chatFormatGame;
    private String chatFormatBuffer;

    private boolean chatCooldownEnabled;
    private long chatCooldownMillis;
    private String chatCooldownBypassPerm;
    private String msgChatCooldownActionbar;

    public GameListeners(JavaPlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        loadConfigSettings();
    }

    public void reloadConfigSettings() {
        plugin.getLogger().info("[GameListeners] Reloading configuration settings...");
        loadConfigSettings();
        plugin.getLogger().info("[GameListeners] Settings reloaded.");
    }

    private void loadConfigSettings() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        String basePath = "listeners_settings.";

        msgJoinGameRunning = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "player_join.message_game_running", "&eA game, &6%game_name%&e, is in progress!"));
        msgJoinGameInBuffer = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "player_join.message_game_in_buffer", "&bNext game, &3%next_game_name%&b, starting soon!"));
        msgJoinNoGameActive = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "player_join.message_no_game_active", "&aWelcome! Type &e/pg join&a!"));

        cmdBlockerEnabled = config.getBoolean(basePath + "command_blocker.enabled", true);
        blockedCommandPrefixes = config.getStringList(basePath + "command_blocker.blocked_command_prefixes")
                .stream().map(String::toLowerCase).collect(Collectors.toList());
        if (blockedCommandPrefixes.isEmpty()) {
            blockedCommandPrefixes.addAll(Arrays.asList("/spawn", "/home", "/tpa", "/warp", "/lobby", "/hub", "/fly"));
        }
        cmdBypassPermission = config.getString(basePath + "command_blocker.bypass_permission", "partygames.admin.bypasscommands");
        msgCommandBlocked = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "command_blocker.message_command_blocked", "&cCannot use &e%command%&c during a game!"));

        chatFormatEnabled = config.getBoolean(basePath + "chat_tweaks.format_enabled", true);
        chatFormatGame = config.getString(basePath + "chat_tweaks.format_string_game", "§8[§e%game_name%§8] §f%1$s§7: §f%2$s");
        chatFormatBuffer = config.getString(basePath + "chat_tweaks.format_string_buffer", "§8[§bNext: %next_game_name%§8] §f%1$s§7: §f%2$s");

        chatCooldownEnabled = config.getBoolean(basePath + "chat_tweaks.cooldown_enabled", true);
        chatCooldownMillis = TimeUnit.SECONDS.toMillis(config.getInt(basePath + "chat_tweaks.cooldown_seconds", 3));
        chatCooldownBypassPerm = config.getString(basePath + "chat_tweaks.cooldown_bypass_permission", "partygames.chat.bypasscooldown");
        msgChatCooldownActionbar = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "chat_tweaks.message_cooldown_actionbar", "&cChat cooldown: %time_left%s"));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }

                if (gameManager.isGameRunning()) {
                    String gameName = gameManager.getCurrentGameName() != null ? gameManager.getCurrentGameName() : "a game";
                    player.sendMessage(msgJoinGameRunning.replace("%game_name%", ChatColor.YELLOW + gameName + ChatColor.getLastColors(msgJoinGameRunning)));
                    player.sendMessage(ChatColor.YELLOW + "PartyGames is current ongoing, if you were playing, use " + ChatColor.GOLD + "/pg join" + ChatColor.YELLOW + " to rejoin or simply join the game.\n");
                } else if (gameManager.isInBuffer()) {
                    String nextGameName = gameManager.getNextGameNameForListener() != null ? gameManager.getNextGameNameForListener() : "Next Game";
                    player.sendMessage(msgJoinGameInBuffer.replace("%next_game_name%", ChatColor.GOLD + nextGameName + ChatColor.getLastColors(msgJoinGameInBuffer)));
                    player.sendMessage(ChatColor.AQUA + "Type /pg join to ensure you're in the queue!");
                } else {
                    player.sendMessage(msgJoinNoGameActive);
                }
            }
        }.runTaskLater(plugin, 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        gameManager.removePlayerFromLobbyQueue(player);
        if (gameManager.isGameRunning() && gameManager.getCurrentGame() != null && gameManager.getCurrentGame().getParticipants().contains(player)) {
            gameManager.removeParticipant(player);
        }
        playerChatCooldowns.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!cmdBlockerEnabled) return;
        Player player = event.getPlayer();
        if (gameManager.isGameRunning()) {
            if (player.isOp() || player.hasPermission(cmdBypassPermission)) {
                return;
            }
            String message = event.getMessage().toLowerCase().trim();
            for (String prefix : blockedCommandPrefixes) {
                if (message.startsWith(prefix)) {
                    event.setCancelled(true);
                    String commandUsed = event.getMessage().split(" ")[0];
                    player.sendMessage(msgCommandBlocked.replace("%command%", commandUsed));
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!chatFormatEnabled && !chatCooldownEnabled) return;
        Player player = event.getPlayer();
        boolean inGameOrBuffer = gameManager.isGameRunning() || gameManager.isInBuffer();

        if (chatFormatEnabled && inGameOrBuffer) {
            String formatToUse = null;
            if (gameManager.isInBuffer()) {
                String nextGameNameRaw = gameManager.getNextGameNameForListener();
                String nextGameNameDisplay = (nextGameNameRaw != null && !nextGameNameRaw.equals("N/A") && !nextGameNameRaw.equalsIgnoreCase("Loading...")) ?
                        ChatColor.translateAlternateColorCodes('&', nextGameNameRaw) :
                        "Next Game";
                formatToUse = chatFormatBuffer.replace("%next_game_name%", nextGameNameDisplay);
            } else if (gameManager.isGameRunning()) {
                String gameNameDisplay = gameManager.getCurrentGameNameLegacy();
                formatToUse = chatFormatGame.replace("%game_name%", gameNameDisplay);
            }
            if (formatToUse != null) {
                event.setFormat(formatToUse);
            }
        }

        if (chatCooldownEnabled && gameManager.isGameRunning()) {
            if (!player.isOp() && !player.hasPermission(chatCooldownBypassPerm)) {
                long currentTime = System.currentTimeMillis();
                long lastChatTime = playerChatCooldowns.getOrDefault(player.getUniqueId(), 0L);
                if (currentTime - lastChatTime < chatCooldownMillis) {
                    long timeLeftMillis = chatCooldownMillis - (currentTime - lastChatTime);
                    double timeLeftSeconds = Math.max(0.1, timeLeftMillis / 1000.0);
                    String cooldownMsg = msgChatCooldownActionbar.replace("%time_left%", String.format("%.1f", timeLeftSeconds));
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(cooldownMsg));
                    event.setCancelled(true);
                } else {
                    playerChatCooldowns.put(player.getUniqueId(), currentTime);
                }
            }
        }
    }
}