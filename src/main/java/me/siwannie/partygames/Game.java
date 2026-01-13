package me.siwannie.partygames;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class Game {
    protected final JavaPlugin plugin;
    protected final Set<Player> participants = new HashSet<>();
    protected int duration;

    public Game(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public abstract void startGame();

    public abstract void endGame();

    public abstract List<String> getFormattedExplanationLines();

    public void handlePlayerJoinMidGame(Player player) {}

    public int getDuration() {
        return duration;
    }

    public void setDuration(int seconds) {
        this.duration = seconds;
    }

    public abstract String getId();

    public abstract String getName();

    protected void broadcast(String message) {
        Bukkit.broadcastMessage("§e[" + getName() + "] §r" + message);
    }

    protected void registerListener(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    protected void unregisterListener(Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    public Set<Player> getParticipants() {
        return participants;
    }
}
