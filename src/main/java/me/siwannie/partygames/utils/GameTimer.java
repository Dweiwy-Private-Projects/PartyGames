package me.siwannie.partygames.utils;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.function.IntConsumer;

public class GameTimer {

    private final int durationSeconds;
    private final JavaPlugin plugin;
    private final IntConsumer onTick;
    private final Runnable onEnd;
    private BukkitRunnable task;
    private int secondsLeft;
    private boolean paused = false;

    /**
     * @param durationSeconds Duration of timer in seconds
     * @param plugin          Plugin instance for scheduling tasks
     * @param onTick          Called every second with seconds left
     * @param onEnd           Called when timer reaches zero
     */
    public GameTimer(int durationSeconds, JavaPlugin plugin, IntConsumer onTick, Runnable onEnd) {
        this.durationSeconds = durationSeconds;
        this.plugin = plugin;
        this.onTick = onTick;
        this.onEnd = onEnd;
        this.secondsLeft = durationSeconds;
    }

    public void start() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (paused) {
                    return;
                }
                if (secondsLeft <= 0) {
                    cancel();
                    if (onEnd != null) {
                        onEnd.run();
                    }
                    return;
                }

                if (onTick != null) {
                    onTick.accept(secondsLeft);
                }
                secondsLeft--;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
        }
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getSecondsLeft() {
        return secondsLeft;
    }
}
