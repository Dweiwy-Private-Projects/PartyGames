package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class ElytraGauntlet extends Game {

    private final Map<Player, Long> finishTimes = new HashMap<>();
    private final Location startPoint = new Location(Bukkit.getWorlds().get(0), 0, 150, 0);
    private final Location endPoint = new Location(Bukkit.getWorlds().get(0), 0, 100, 100);
    private Listener listener;

    public ElytraGauntlet(JavaPlugin plugin) {
        super(plugin);
        this.duration = plugin.getConfig().getInt("games.elytragauntlet.duration", 420);
        setDuration(duration);
    }
    private BukkitRunnable countdownTask;

    @Override
    public void startGame() {
        broadcast("§bGame Started! Fly through the Elytra course and reach the finish!");
        participants.addAll(Bukkit.getOnlinePlayers());
        listener = new ElytraGauntletListener();
        registerListener(listener);

        for (Player p : participants) {
            p.teleport(startPoint);
            p.getInventory().clear();
            p.setAllowFlight(true);
            p.setFlying(false);
            p.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
            p.setGameMode(GameMode.ADVENTURE);
            p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, 1f, 1f);
        }

        new BukkitRunnable() {
            int countdown = 5;

            @Override
            public void run() {
                if (countdown <= 0) {
                    broadcast("§aGO!");
                    sendTitleToAll("§9Elytra Gauntlet", "§aSTARTED!", 10, 70, 20);
                    startCountdownTask();
                    cancel();
                } else {
                    broadcast("§eStarting in §c" + countdown + "...");
                    countdown--;
                }
            }
        }.runTaskTimer(plugin, 0, 20);
    }


    @Override
    public void endGame() {
        unregisterListener(listener);
        broadcast("§fElytra Gauntlet Over!");

        if (countdownTask != null) countdownTask.cancel();

        List<Map.Entry<Player, Long>> sorted = new ArrayList<>(finishTimes.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        int[] points = {30, 25, 20, 15, 12, 10, 8, 6, 4, 2};

        for (int i = 0; i < sorted.size(); i++) {
            Player player = sorted.get(i).getKey();
            int reward = i < points.length ? points[i] : 5;
            GameManager.getInstance().addPointsForGame(player, getId(), reward);
            player.sendMessage("§aYou placed §e#" + (i + 1) + " §afor §b+" + reward + " points!");
        }

        finishTimes.clear();
        for (Player p : participants) {
            p.setAllowFlight(false);
            p.setFlying(false);
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
        }
    }


    private void startCountdownTask() {
        final int totalDuration = getDuration();
        countdownTask = new BukkitRunnable() {
            int timeLeft = totalDuration;

            @Override
            public void run() {
                if (timeLeft <= 0) {
                    cancel();
                    return;
                }

                if (timeLeft == totalDuration / 2 || timeLeft == 60 || timeLeft == 30) {
                    sendTitleToAll("§6Ending in", "§e" + timeLeft + " seconds", 10, 40, 10);
                }

                if (timeLeft <= 10 && timeLeft > 0) {
                    sendTitleToAll("§6Ending in", "§c" + timeLeft + "...", 10, 40, 10);
                }

                timeLeft--;
            }
        };

        countdownTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player p : participants) {
            p.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }
    }

    @Override
    public int getDuration() {
        return duration;
    }

    @Override
    public String getName() {
        return "Elytra Gauntlet";
    }

    @Override
    public String getId() {
        return "elytragauntlet";
    }

    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());
        FileConfiguration config = plugin.getConfig();
        String basePath = "games." + getId() + ".points.";

        if (rawExplanation.startsWith("§7No explanation found for") || rawExplanation.startsWith("§cError: Explanations unavailable.")) {
            plugin.getLogger().warning("Explanation string not found for: " + getId() + ". Using default/error message.");
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        String formatted = rawExplanation
                .replace("%duration%", String.valueOf(this.duration));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());    }

    private class ElytraGauntletListener implements Listener {

        @EventHandler
        public void onFlightToggle(PlayerToggleFlightEvent event) {
            Player player = event.getPlayer();
            if (!participants.contains(player)) return;
            if (!player.isGliding()) {
                player.setGliding(true);
            }
            event.setCancelled(true);
        }


        @EventHandler
        public void onPlayerMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            if (!participants.contains(player)) return;
            if (finishTimes.containsKey(player)) return;

            Location to = event.getTo();
            if (to == null) return;

            if (to.distance(endPoint) < 5) {
                finishTimes.put(player, System.currentTimeMillis());
                player.sendMessage("§6You finished the course!");
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation(), 50);
            }
        }
    }
}