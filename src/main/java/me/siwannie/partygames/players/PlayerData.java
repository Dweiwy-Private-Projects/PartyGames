package me.siwannie.partygames.players;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private String name;
    private int totalPoints;
    private int lives;
    private int rank;
    private final Map<String, Integer> gamePoints;

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.totalPoints = 0;
        this.lives = 0;
        this.rank = 0;
        this.gamePoints = new HashMap<>();
    }

    public PlayerData(UUID uuid, String name, int totalPoints, int lives, Map<String, Integer> gamePoints) {
        this.uuid = uuid;
        this.name = name;
        this.totalPoints = totalPoints;
        this.lives = lives;
        this.rank = 0;
        this.gamePoints = new HashMap<>(gamePoints);
    }

    public UUID getUuid() {
        return uuid;
    }

    public Player getPlayer() {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            this.name = player.getName();
        }
        return player;
    }

    public String getName() {
        return name;
    }

    public int getTotalPoints() {
        return totalPoints;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public void addPoints(int points) {
        this.totalPoints += points;
    }

    public void addPointsForGame(String gameName, int points) {
        gamePoints.put(gameName, gamePoints.getOrDefault(gameName, 0) + points);
        addPoints(points);
    }

    public int getPointsForGame(String gameName) {
        return gamePoints.getOrDefault(gameName, 0);
    }

    public void resetPointsForGame(String gameName) {
        gamePoints.put(gameName, 0);
        recalculateTotalPoints();
    }

    public void resetAllPoints() {
        totalPoints = 0;
        gamePoints.clear();
    }

    private void recalculateTotalPoints() {
        totalPoints = gamePoints.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<String, Integer> getAllGamePoints() {
        return new HashMap<>(gamePoints);
    }

    public int getLives() {
        return lives;
    }

    public void setLives(int lives) {
        this.lives = lives;
    }

    public void loseLife() {
        if (lives > 0) lives--;
    }

    public void gainLife() {
        lives++;
    }

    public boolean isAlive() {
        return lives > 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("total", totalPoints);
        data.put("rank", rank);
        data.put("games", new HashMap<>(gamePoints));
        return data;
    }

    @SuppressWarnings("unchecked")
    public static PlayerData fromMap(UUID uuid, Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "Unknown");
        int total = 0;
        Object totalObj = data.get("total");
        if (totalObj instanceof Number number) {
            total = number.intValue();
        }
        int lives = 0;

        int rank = 0;
        Object rankObj = data.get("rank");
        if (rankObj instanceof Number number) {
            rank = number.intValue();
        }

        Map<String, Integer> games = new HashMap<>();
        Object gameObj = data.get("games");
        if (gameObj instanceof Map<?, ?> gameMap) {
            for (Map.Entry<?, ?> entry : gameMap.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
                    games.put(key, value.intValue());
                }
            }
        }

        PlayerData pd = new PlayerData(uuid, name, total, lives, games);
        pd.setRank(rank);
        return pd;
    }

    @Override
    public String toString() {
        return "PlayerData{" +
                "uuid=" + uuid +
                ", name='" + name + '\'' +
                ", totalPoints=" + totalPoints +
                ", lives=" + lives +
                ", rank=" + rank +
                ", gamePoints=" + gamePoints +
                '}';
    }
}
