package me.siwannie.partygames.utils;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.siwannie.partygames.GameManager;
import me.siwannie.partygames.Game;
import me.siwannie.partygames.players.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

public class PartyGamesPlaceholderExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;

    public PartyGamesPlaceholderExpansion(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean canRegister() {
        return GameManager.getInstance() != null && GameManager.getInstance().getPlugin() != null;
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().isEmpty() ? "Siwannie" : String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getIdentifier() {
        return "pg";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    private String formatTime(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        long hours = TimeUnit.SECONDS.toHours(totalSeconds);
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        GameManager gameManager = GameManager.getInstance();
        if (gameManager == null) {
            return "PartyGames Not Ready";
        }

        if (player != null) {
            if ("player_points".equalsIgnoreCase(identifier)) {
                PlayerData playerData = gameManager.getPointsManager().getPlayerData(player);
                return playerData != null ? String.valueOf(playerData.getTotalPoints()) : "0";
            }
        }

        switch (identifier.toLowerCase()) {
            case "time_left_seconds":
                if (gameManager.isPaused()) return "0";
                if (gameManager.isInBuffer()) {
                    return String.valueOf(gameManager.getBufferCountdownSeconds());
                } else if (gameManager.isGameRunning()) {
                    return String.valueOf(gameManager.getCurrentActualGameTimeLeft());
                }
                return "0";

            case "time_left":
                if (gameManager.isPaused()) return "Paused";
                if (gameManager.isInBuffer()) {
                    return formatTime(gameManager.getBufferCountdownSeconds());
                } else if (gameManager.isGameRunning()) {
                    return formatTime(gameManager.getCurrentActualGameTimeLeft());
                }
                return formatTime(0);

            case "status_display":
                if (gameManager.isPaused()) return "Game Paused";
                if (gameManager.isInBuffer()) return "Next Game Starting...";
                if (gameManager.isGameRunning()) return "Game in Progress";
                return "Waiting for Game";

            case "game_name":
            case "game_display_name":
                String gameName = "Waiting...";
                if (gameManager.isInBuffer()) {
                    gameName = gameManager.getNextGameNameForListener();
                } else if (gameManager.isGameRunning()) {
                    gameName = gameManager.getCurrentGameName();
                } else if (gameManager.getCurrentGameName() != null && !"None".equalsIgnoreCase(gameManager.getCurrentGameName()) && !"Loading...".equalsIgnoreCase(gameManager.getCurrentGameName())) {
                    gameName = gameManager.getCurrentGameName();
                }
                return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', gameName));

            case "game_name_formatted":
                String gameNameFmt = "§7Waiting...";
                if (gameManager.isInBuffer()) {
                    gameNameFmt = gameManager.getNextGameNameForListener();
                } else if (gameManager.isGameRunning()) {
                    gameNameFmt = gameManager.getCurrentGameName();
                } else if (gameManager.getCurrentGameName() != null && !"None".equalsIgnoreCase(gameManager.getCurrentGameName()) && !"Loading...".equalsIgnoreCase(gameManager.getCurrentGameName())) {
                    gameNameFmt = gameManager.getCurrentGameName();
                }
                return ChatColor.translateAlternateColorCodes('&', gameNameFmt);

            case "current_game_name":
                if (gameManager.isGameRunning()) {
                    return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', gameManager.getCurrentGameName()));
                }
                return "N/A";

            case "next_game_name":
                if (gameManager.isInBuffer()) {
                    return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', gameManager.getNextGameNameForListener()));
                }
                return "N/A";

            case "lobby_queue_size":
                return String.valueOf(gameManager.getLobbyQueue().size());

            case "participants_size":
                Game currentGame = gameManager.getCurrentGame();
                if (currentGame != null && gameManager.isGameRunning() && !gameManager.isInBuffer()) {
                    return String.valueOf(currentGame.getParticipants().size());
                }
                return "0";

            case "current_game_index":
                if (gameManager.isGameStarted()) {
                    return String.valueOf(gameManager.getInternalGameIndex() + 1);
                }
                return "0";

            case "version":
                return getVersion();

            default:
                return null;
        }
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player != null && player.isOnline()) {
            return onPlaceholderRequest(player.getPlayer(), params);
        }
        if (params.startsWith("player_")) return "Player Offline";
        return onPlaceholderRequest(null, params);
    }
}