package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class ColorChaos extends Game {

    private List<Material> availableWoolColors = new ArrayList<>();
    private Material currentTargetColor;
    private Listener gameListener;
    private BukkitTask mainColorCycleTask;
    private BukkitTask titleCountdownTask;

    private int pointsCorrect;
    private int pointsWrong;
    private int roundIntervalTicks;
    private int decisionTimeTicks;

    private Location arenaCorner1;
    private Location arenaCorner2;
    private World gameWorld;

    private boolean titlesEnabled;
    private int titleFadeIn, titleStay, titleFadeOut;
    private boolean particlesEnabled;
    private Particle particleType;
    private int particleCountPerBlock;

    private final Random random = new Random();

    public ColorChaos(JavaPlugin plugin) {
        super(plugin);
        loadConfigSettings();
    }

    private void loadConfigSettings() {
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.colorchaos.";

        this.duration = config.getInt(basePath + "duration", 240);
        setDuration(this.duration);

        this.roundIntervalTicks = config.getInt(basePath + "round_interval_seconds", 7) * 20;
        this.decisionTimeTicks = config.getInt(basePath + "decision_time_seconds", 4) * 20;

        if (this.decisionTimeTicks >= this.roundIntervalTicks && this.roundIntervalTicks > 60) {
            this.decisionTimeTicks = this.roundIntervalTicks - 60;
        } else if (this.decisionTimeTicks >= this.roundIntervalTicks) {
            this.decisionTimeTicks = Math.max(20, this.roundIntervalTicks / 2);
        }


        this.pointsCorrect = config.getInt(basePath + "points.correct", 5);
        this.pointsWrong = config.getInt(basePath + "points.wrong", -2);

        List<String> colorNames = config.getStringList(basePath + "wool_colors");
        availableWoolColors.clear();
        if (colorNames.isEmpty()) {
            availableWoolColors.addAll(Arrays.asList(Material.RED_WOOL, Material.BLUE_WOOL, Material.GREEN_WOOL, Material.YELLOW_WOOL));
        } else {
            for (String name : colorNames) {
                try {
                    Material mat = Material.valueOf(name.toUpperCase());
                    if (mat.toString().endsWith("_WOOL")) {
                        availableWoolColors.add(mat);
                    }
                } catch (IllegalArgumentException e) {
                }
            }
            if (availableWoolColors.isEmpty()) {
                availableWoolColors.addAll(Arrays.asList(Material.RED_WOOL, Material.BLUE_WOOL));
            }
        }

        String worldName = config.getString(basePath + "arena.world");
        if (worldName != null) this.gameWorld = Bukkit.getWorld(worldName);

        if (this.gameWorld != null && config.contains(basePath + "arena.corner1.x") && config.contains(basePath + "arena.corner2.x")) {
            arenaCorner1 = new Location(this.gameWorld, config.getDouble(basePath + "arena.corner1.x"), config.getDouble(basePath + "arena.corner1.y"), config.getDouble(basePath + "arena.corner1.z"));
            arenaCorner2 = new Location(this.gameWorld, config.getDouble(basePath + "arena.corner2.x"), config.getDouble(basePath + "arena.corner2.y"), config.getDouble(basePath + "arena.corner2.z"));
        }

        this.titlesEnabled = config.getBoolean(basePath + "effects.color_announce_title.enabled", true);
        this.titleFadeIn = config.getInt(basePath + "effects.color_announce_title.fade_in_ticks", 10);
        this.titleStay = config.getInt(basePath + "effects.color_announce_title.stay_ticks", 50);
        this.titleFadeOut = config.getInt(basePath + "effects.color_announce_title.fade_out_ticks", 10);

        this.particlesEnabled = config.getBoolean(basePath + "effects.highlight_correct_color_particles.enabled", true);
        try {
            this.particleType = Particle.valueOf(config.getString(basePath + "effects.highlight_correct_color_particles.particle_type", "HAPPY_VILLAGER").toUpperCase());
        } catch (IllegalArgumentException e) {
            this.particleType = Particle.HAPPY_VILLAGER;
        }
        this.particleCountPerBlock = config.getInt(basePath + "effects.highlight_correct_color_particles.count_per_block", 5);
    }

    public void setCorrectPoints(int points) { this.pointsCorrect = points; }
    public void setWrongPoints(int points) { this.pointsWrong = points; }

    @Override
    public void startGame() {
        if (gameWorld == null || arenaCorner1 == null || arenaCorner2 == null) {
            broadcast("§c[ColorChaos] Error: Game arena (world/corners) not properly configured.");
            GameManager.getInstance().endCurrentGame();
            return;
        }
        if (availableWoolColors.isEmpty()) {
            broadcast("§c[ColorChaos] Error: No wool colors configured.");
            GameManager.getInstance().endCurrentGame();
            return;
        }
        if (this.participants.isEmpty()) {
            broadcast("§c[ColorChaos] No participants. Game cannot start.");
            GameManager.getInstance().endCurrentGame();
            return;
        }

        broadcast("§fGame Started! §bStand on the correct color before time runs out!");

        gameListener = new ColorChaosListener();
        registerListener(gameListener);
        startColorCycleTask();

        sendTitleToAllGame("§6Color Chaos", "§aSTARTED!", 10, 70, 20);
    }

    @Override
    public void endGame() {
        broadcast("§fColor Chaos Over!");
        if (gameListener != null) {
            unregisterListener(gameListener);
        }
        if (mainColorCycleTask != null) mainColorCycleTask.cancel();
        if (titleCountdownTask != null) titleCountdownTask.cancel();
    }

    private void startColorCycleTask() {
        if (mainColorCycleTask != null && !mainColorCycleTask.isCancelled()) {
            mainColorCycleTask.cancel();
        }
        BukkitRunnable taskRunnable = new BukkitRunnable() {
            @Override
            public void run() {
                GameManager gm = GameManager.getInstance();
                Game activeGame = gm.getCurrentGame();
                if (!gm.isGameRunning() || (activeGame != null && !getId().equals(activeGame.getId()))) {
                    this.cancel();
                    return;
                }
                if (availableWoolColors.isEmpty()) {
                    this.cancel();
                    gm.endCurrentGame();
                    return;
                }

                currentTargetColor = availableWoolColors.get(random.nextInt(availableWoolColors.size()));
                String colorName = getColorFriendlyName(currentTargetColor);
                ChatColor chatColor = getChatColorForWool(currentTargetColor);

                if (titlesEnabled) {
                    sendTitleToAllGame("§eStand on:", chatColor + "" + ChatColor.BOLD + colorName.toUpperCase(), titleFadeIn, titleStay, titleFadeOut);
                } else {
                    broadcast("§eStand on: " + chatColor + colorName.toUpperCase());
                }

                if (particlesEnabled && arenaCorner1 != null && arenaCorner2 != null && gameWorld != null) {
                    highlightCorrectBlocks();
                }

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        GameManager gmInner = GameManager.getInstance();
                        Game activeGameInner = gmInner.getCurrentGame();
                        if (!gmInner.isGameRunning() || (activeGameInner != null && !getId().equals(activeGameInner.getId()))) {
                            return;
                        }
                        evaluatePlayerPositions();
                    }
                }.runTaskLater(plugin, decisionTimeTicks);
            }
        };
        this.mainColorCycleTask = taskRunnable.runTaskTimer(plugin, 60L, roundIntervalTicks);
    }

    private void evaluatePlayerPositions() {
        for (Player player : new ArrayList<>(participants)) {
            if (player == null || !player.isOnline()) continue;

            Block blockUnderPlayer = player.getLocation().subtract(0, 1, 0).getBlock();
            String actionBarMessage;
            Sound soundToPlay;
            float pitch = 1.0f;

            if (blockUnderPlayer.getType() == currentTargetColor) {
                GameManager.getInstance().addPointsForGame(player, getId(), pointsCorrect);
                actionBarMessage = "§a+" + pointsCorrect + " points - Correct color!";
                soundToPlay = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
                pitch = 1.2f;
                if (particlesEnabled && particleType != null) {
                    player.getWorld().spawnParticle(particleType, player.getLocation().add(0,1,0), 5, 0.2,0.2,0.2,0);
                }
            } else {
                GameManager.getInstance().addPointsForGame(player, getId(), pointsWrong);
                actionBarMessage = "§c" + pointsWrong + " points - Wrong color or too slow!";
                soundToPlay = Sound.ENTITY_VILLAGER_NO;
                pitch = 0.8f;
                if (particlesEnabled && particleType != null) {
                    player.getWorld().spawnParticle(Particle.LARGE_SMOKE, player.getLocation().add(0,1,0), 5, 0.2,0.2,0.2,0);
                }
            }
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(actionBarMessage));
            player.playSound(player.getLocation(), soundToPlay, 1.0f, pitch);
        }
    }

    private void highlightCorrectBlocks() {
        if (gameWorld == null || arenaCorner1 == null || arenaCorner2 == null || currentTargetColor == null || particleType == null) return;

        int minX = Math.min(arenaCorner1.getBlockX(), arenaCorner2.getBlockX());
        int maxX = Math.max(arenaCorner1.getBlockX(), arenaCorner2.getBlockX());
        int floorY = arenaCorner1.getBlockY();
        int minZ = Math.min(arenaCorner1.getBlockZ(), arenaCorner2.getBlockZ());
        int maxZ = Math.max(arenaCorner1.getBlockZ(), arenaCorner2.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Block block = gameWorld.getBlockAt(x, floorY, z);
                if (block.getType() == currentTargetColor) {
                    gameWorld.spawnParticle(particleType, block.getLocation().add(0.5, 0.8, 0.5), particleCountPerBlock, 0.3, 0.1, 0.3, 0.05);
                }
            }
        }
    }

    private String getColorFriendlyName(Material woolMaterial) {
        return woolMaterial.name().replace("_WOOL", "").replace("_", " ").toLowerCase();
    }

    private ChatColor getChatColorForWool(Material woolMaterial) {
        return switch (woolMaterial) {
            case RED_WOOL -> ChatColor.RED;
            case BLUE_WOOL -> ChatColor.BLUE;
            case GREEN_WOOL -> ChatColor.GREEN;
            case YELLOW_WOOL -> ChatColor.YELLOW;
            case ORANGE_WOOL -> ChatColor.GOLD;
            case PINK_WOOL, MAGENTA_WOOL -> ChatColor.LIGHT_PURPLE;
            case LIME_WOOL -> ChatColor.GREEN;
            case CYAN_WOOL -> ChatColor.AQUA;
            case PURPLE_WOOL -> ChatColor.DARK_PURPLE;
            case WHITE_WOOL -> ChatColor.WHITE;
            case LIGHT_GRAY_WOOL -> ChatColor.GRAY;
            case GRAY_WOOL -> ChatColor.DARK_GRAY;
            case BLACK_WOOL -> ChatColor.BLACK;
            case BROWN_WOOL -> ChatColor.DARK_RED;
            default -> ChatColor.WHITE;
        };
    }
    private void sendTitleToAllGame(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player p : participants) {
            if (p != null && p.isOnline()) {
                p.sendTitle(ChatColor.translateAlternateColorCodes('&', title),
                        ChatColor.translateAlternateColorCodes('&', subtitle),
                        fadeIn, stay, fadeOut);
            }
        }
    }

    @Override public String getName() { return "Color Chaos"; }
    @Override public String getId() { return "colorchaos"; }
    @Override public int getDuration() { return this.duration; }

    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());

        if (rawExplanation.startsWith("§7No explanation found for") || rawExplanation.startsWith("§cError: Explanations unavailable.")) {
            plugin.getLogger().warning("Explanation string not found for: " + getId() + ". Using default/error message.");
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        String formatted = rawExplanation
                .replace("%duration%", String.valueOf(this.duration))
                .replace("%round_interval_seconds%", String.valueOf(this.roundIntervalTicks / 20))
                .replace("%decision_time_seconds%", String.valueOf(this.decisionTimeTicks / 20))
                .replace("%points.correct%", String.valueOf(this.pointsCorrect))
                .replace("%points.wrong%", String.valueOf(this.pointsWrong));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());    }

    private class ColorChaosListener implements Listener {
    }
}