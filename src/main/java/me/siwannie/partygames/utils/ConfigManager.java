package me.siwannie.partygames.utils;

import me.siwannie.partygames.PartyGames;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

public class ConfigManager {

    private final PartyGames plugin;
    private File configFile;
    private FileConfiguration config;

    public ConfigManager(PartyGames plugin) {
        this.plugin = plugin;
        setupConfig();
    }

    public static void load(PartyGames partyGames) {
    }

    private void setupConfig() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    /**
     * Returns the loaded configuration.
     */
    public FileConfiguration getConfig() {
        return config;
    }

    /**
     * Reloads the configuration file from disk.
     */
    public void reloadConfig() {
        if (configFile == null) {
            configFile = new File(plugin.getDataFolder(), "config.yml");
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    /**
     * Saves the configuration to disk.
     */
    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save config.yml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the duration (in seconds) for a specific game.
     * @param gameName Name of the game (case insensitive).
     * @param defaultDuration Default value if not specified.
     * @return Duration in seconds.
     */
    public int getGameDuration(String gameName, int defaultDuration) {
        return config.getInt("games." + gameName.toLowerCase() + ".duration", defaultDuration);
    }

    /**
     * Retrieves an integer point value for a specified key in a game.
     * @param gameName Name of the game (case insensitive).
     * @param key Specific point key (e.g. "easy", "fail_penalty").
     * @param defaultPoints Default value if not found.
     * @return Point value.
     */
    public int getPoints(String gameName, String key, int defaultPoints) {
        return config.getInt("games." + gameName.toLowerCase() + ".points." + key, defaultPoints);
    }

    /**
     * Generic method to get boolean config options.
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        return config.getBoolean(path, defaultValue);
    }

    /**
     * Generic method to get string config options.
     */
    public String getString(String path, String defaultValue) {
        return config.getString(path, defaultValue);
    }
}
