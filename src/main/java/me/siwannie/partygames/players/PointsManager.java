package me.siwannie.partygames.players;

import me.siwannie.partygames.PartyGames;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PointsManager {

    private final File pointsFile;
    private FileConfiguration pointsConfig;

    private final Map<UUID, PlayerData> playerDataMap = new HashMap<>();

    public PointsManager() {
        pointsFile = new File(PartyGames.getInstance().getDataFolder(), "points.yml");
        if (!pointsFile.exists()) {
            PartyGames.getInstance().saveResource("points.yml", false);
        }
        pointsConfig = YamlConfiguration.loadConfiguration(pointsFile);
        loadPoints();
        updateRanks();
    }

    public void loadPoints() {
        playerDataMap.clear();

        ConfigurationSection playersSection = pointsConfig.getConfigurationSection("players");
        if (playersSection == null) return;

        for (String uuidStr : playersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection playerSection = playersSection.getConfigurationSection(uuidStr);
                if (playerSection == null) continue;

                Map<String, Object> playerMap = new HashMap<>();
                for (String key : playerSection.getKeys(false)) {
                    playerMap.put(key, playerSection.get(key));
                }

                PlayerData pd = PlayerData.fromMap(uuid, playerMap);
                playerDataMap.put(uuid, pd);

            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().warning("Invalid UUID in points.yml: " + uuidStr);
            }
        }

        updateRanks();
    }

    public void savePoints() {
        updateRanks();

        pointsConfig.set("players", null);

        for (Map.Entry<UUID, PlayerData> entry : playerDataMap.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerData pd = entry.getValue();

            pointsConfig.createSection("players." + uuid.toString(), pd.toMap());
        }

        try {
            pointsConfig.save(pointsFile);
        } catch (IOException e) {
            Bukkit.getLogger().severe("Could not save points.yml: " + e.getMessage());
        }
    }

    public PlayerData getPlayerData(UUID uuid) {
        PlayerData pd = playerDataMap.get(uuid);
        if (pd == null) {
            String name = "Unknown";
            if (Bukkit.getOfflinePlayer(uuid) != null && Bukkit.getOfflinePlayer(uuid).getName() != null) {
                name = Bukkit.getOfflinePlayer(uuid).getName();
            }
            pd = new PlayerData(uuid, name, 0, 0, new HashMap<>());
            playerDataMap.put(uuid, pd);
        }
        return pd;
    }

    public PlayerData getPlayerData(org.bukkit.entity.Player player) {
        return getPlayerData(player.getUniqueId());
    }

    public void addPoints(UUID uuid, String gameName, int points) {
        PlayerData pd = getPlayerData(uuid);
        pd.addPointsForGame(gameName, points);
        savePoints();
        updateRanks();
    }

    public void addPoints(org.bukkit.entity.Player player, String gameName, int points) {
        addPoints(player.getUniqueId(), gameName, points);
    }

    public List<PlayerData> getLeaderboard() {
        List<PlayerData> sorted = new ArrayList<>(playerDataMap.values());
        sorted.sort((a, b) -> Integer.compare(b.getTotalPoints(), a.getTotalPoints()));
        return sorted;
    }

    private void updateRanks() {
        List<PlayerData> sorted = getLeaderboard();
        int rank = 1;
        for (PlayerData pd : sorted) {
            pd.setRank(rank++);
        }
    }

    public void resetAllPoints() {
        playerDataMap.clear();
        pointsConfig.set("players", null);
        try {
            pointsConfig.save(pointsFile);
            Bukkit.getLogger().info("All PartyGames player points have been reset.");
        } catch (IOException e) {
            Bukkit.getLogger().severe("Could not reset points.yml: " + e.getMessage());
        }
    }
}