package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class ShearMadness extends Game {

    private Listener gameListener;
    private final Map<UUID, Long> lastShearTime = new HashMap<>();
    private final Map<UUID, Integer> playerShearStreaks = new HashMap<>();
    private final List<Sheep> activeSheep = new ArrayList<>();
    private final Random random = new Random();

    private int pointsPerShear;
    private int streakBonusPoints;
    private int streakThresholdForBonus;
    private long fastShearStreakResetCooldownMs;
    private long streakTimeoutMs;
    private int maxActiveSheep;

    private Location sheepSpawnCorner1;
    private Location sheepSpawnCorner2;
    private World gameWorld;

    private BukkitTask titleCountdownTask;
    private final String SHEARS_NAME = "§aParty Shears";

    public ShearMadness(JavaPlugin plugin) {
        super(plugin);
        loadConfigSettings();
    }

    private void loadConfigSettings() {
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.shearmadness.";

        this.duration = config.getInt(basePath + "duration", 300);
        setDuration(this.duration);
        this.pointsPerShear = config.getInt(basePath + "points.per_shear", 1);
        this.streakBonusPoints = config.getInt(basePath + "points.streak_bonus", 5);
        this.streakThresholdForBonus = config.getInt(basePath + "points.streak_length", 10);

        this.fastShearStreakResetCooldownMs = config.getLong(basePath + "streak_rules.fast_shear_streak_reset_cooldown_ms", 1000L);
        this.streakTimeoutMs = config.getLong(basePath + "streak_rules.streak_timeout_ms", 7000L);
        this.maxActiveSheep = config.getInt(basePath + "max_active_sheep", 75);

        String worldName = config.getString(basePath + "spawn_area.world");
        if (worldName != null && !worldName.isEmpty()) {
            this.gameWorld = Bukkit.getWorld(worldName);
            if (this.gameWorld == null) {
                plugin.getLogger().severe("[ShearMadness] World '" + worldName + "' for sheep spawn_area not found!");
            }
        } else {
            plugin.getLogger().severe("[ShearMadness] No world specified for sheep spawn_area in config!");
        }

        if (this.gameWorld != null && config.contains(basePath + "spawn_area.corner1.x") && config.contains(basePath + "spawn_area.corner2.x")) {
            sheepSpawnCorner1 = new Location(gameWorld, config.getDouble(basePath + "spawn_area.corner1.x"), config.getDouble(basePath + "spawn_area.corner1.y"), config.getDouble(basePath + "spawn_area.corner1.z"));
            sheepSpawnCorner2 = new Location(gameWorld, config.getDouble(basePath + "spawn_area.corner2.x"), config.getDouble(basePath + "spawn_area.corner2.y"), config.getDouble(basePath + "spawn_area.corner2.z"));
        } else {
            if (this.gameWorld != null) {
                plugin.getLogger().severe("[ShearMadness] Sheep spawn_area corners not fully configured!");
            }
        }
    }

    @Override
    public void startGame() {
        if (gameWorld == null || sheepSpawnCorner1 == null || sheepSpawnCorner2 == null) {
            broadcast("§c[ShearMadness] Error: Game arena not configured. Cannot start.");
            GameManager.getInstance().endCurrentGame();
            return;
        }
        if (this.participants.isEmpty()) {
            broadcast("§c[ShearMadness] No participants. Game cannot start.");
            GameManager.getInstance().endCurrentGame();
            return;
        }

        lastShearTime.clear();
        playerShearStreaks.clear();
        clearExistingGameSheep();

        broadcast("§fGame Started! §aShear as many sheep as you can!");

        gameListener = new ShearMadnessListener();
        registerListener(gameListener);
        givePlayersShears();
        spawnInitialSheep();

        for (Player p : participants) {
            p.sendTitle("§6Shear Madness", "§aSTARTED!", 10, 70, 20);
        }
        startTitleCountdownTask();
    }

    @Override
    public void endGame() {
        broadcast("§fGame Over!");
        if (gameListener != null) {
            unregisterListener(gameListener);
        }
        lastShearTime.clear();
        playerShearStreaks.clear();
        clearExistingGameSheep();

        if (titleCountdownTask != null && !titleCountdownTask.isCancelled()) {
            titleCountdownTask.cancel();
        }

        for (Player player : participants) {
            if (player != null && player.isOnline()) {
                player.getInventory().clear();
            }
        }
    }

    private void spawnInitialSheep() {
        activeSheep.clear();
        for (int i = 0; i < maxActiveSheep; i++) {
            spawnNewWoolySheep();
        }
    }

    private void spawnNewWoolySheep() {
        if (gameWorld == null || sheepSpawnCorner1 == null || sheepSpawnCorner2 == null) return;
        int attempts = 0;
        while (attempts < 20) {
            double minX = Math.min(sheepSpawnCorner1.getX(), sheepSpawnCorner2.getX());
            double maxX = Math.max(sheepSpawnCorner1.getX(), sheepSpawnCorner2.getX());
            double y = Math.max(sheepSpawnCorner1.getY(), sheepSpawnCorner2.getY());
            double minZ = Math.min(sheepSpawnCorner1.getZ(), sheepSpawnCorner2.getZ());
            double maxZ = Math.max(sheepSpawnCorner1.getZ(), sheepSpawnCorner2.getZ());
            double spawnX = minX + random.nextDouble() * (maxX - minX);
            double spawnZ = minZ + random.nextDouble() * (maxZ - minZ);
            Location spawnLoc = new Location(gameWorld, spawnX, y, spawnZ);
            spawnLoc = getSafeSpawnLocation(spawnLoc);
            if (spawnLoc != null) {
                Sheep sheep = (Sheep) gameWorld.spawnEntity(spawnLoc, EntityType.SHEEP);
                sheep.setSheared(false);
                sheep.setAgeLock(true);
                sheep.setRemoveWhenFarAway(false);
                activeSheep.add(sheep);
                return;
            }
            attempts++;
        }
        plugin.getLogger().warning("[ShearMadness] Failed to find safe spawn for sheep after " + attempts + " attempts.");
    }

    private Location getSafeSpawnLocation(Location centerApprox) {
        World world = centerApprox.getWorld();
        if (world == null) return null;
        for (int yIter = centerApprox.getBlockY(); yIter > Math.max(world.getMinHeight(), centerApprox.getBlockY() - 5) ; yIter--) {
            Location groundLoc = new Location(world, centerApprox.getX(), yIter - 1, centerApprox.getZ());
            Location sheepBodyLoc = new Location(world, centerApprox.getX(), yIter, centerApprox.getZ());
            Location sheepHeadLoc = new Location(world, centerApprox.getX(), yIter + 1, centerApprox.getZ());
            if (groundLoc.getBlock().getType().isSolid() &&
                    !sheepBodyLoc.getBlock().getType().isSolid() &&
                    !sheepHeadLoc.getBlock().getType().isSolid()) {
                return sheepBodyLoc;
            }
        }
        return null;
    }

    private void replaceShearedSheep(Sheep shearedSheep) {
        if (shearedSheep != null && !shearedSheep.isDead()) {
            activeSheep.remove(shearedSheep);
            shearedSheep.remove();
        }
        Game currentGMGame = GameManager.getInstance().getCurrentGame();
        if (currentGMGame != null && currentGMGame.getId().equals(this.getId()) && GameManager.getInstance().isGameRunning()) {
            if (activeSheep.size() < maxActiveSheep) {
                spawnNewWoolySheep();
            }
        }
    }

    private void clearExistingGameSheep() {
        for (Sheep sheep : new ArrayList<>(activeSheep)) {
            if (sheep != null && !sheep.isDead()) {
                sheep.remove();
            }
        }
        activeSheep.clear();
    }

    private void giveShearsToPlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        player.getInventory().clear();
        ItemStack shears = new ItemStack(Material.SHEARS);
        ItemMeta meta = shears.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SHEARS_NAME);
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS);
            shears.setItemMeta(meta);
        }
        player.getInventory().setItem(0, shears);
    }

    private void givePlayersShears() {
        for (Player player : participants) {
            giveShearsToPlayer(player);
        }
    }

    @Override
    public void handlePlayerJoinMidGame(Player player) {
        givePlayersShears();
    }

    private void startTitleCountdownTask() {
        if (this.titleCountdownTask != null && !this.titleCountdownTask.isCancelled()) {
            this.titleCountdownTask.cancel();
        }
        final int totalDuration = getDuration();
        BukkitRunnable runnable = new BukkitRunnable() {
            int timeLeft = totalDuration;
            @Override
            public void run() {
                Game currentGameRef = GameManager.getInstance().getCurrentGame();
                if (!GameManager.getInstance().isGameRunning() ||
                        (currentGameRef != null && !getId().equals(currentGameRef.getId())) ||
                        timeLeft <= 0 ) {
                    this.cancel();
                    return;
                }
                if (timeLeft == totalDuration / 2 || timeLeft == 60 || timeLeft == 30) {
                    sendTitleToAll("§6Ending in", "§e" + timeLeft + " seconds");
                }
                if (timeLeft <= 10 && timeLeft > 0) {
                    sendTitleToAll("§6Ending in", "§c" + timeLeft + "...");
                }
                timeLeft--;
            }
        };
        this.titleCountdownTask = runnable.runTaskTimer(plugin, 0L, 20L);
    }

    private void sendTitleToAll(String title, String subtitle) {
        for (Player p : participants) {
            if (p != null && p.isOnline()) {
                p.sendTitle(ChatColor.translateAlternateColorCodes('&', title),
                        ChatColor.translateAlternateColorCodes('&', subtitle),
                        10, 40, 10);
            }
        }
    }

    @Override public String getName() { return "Shear Madness"; }
    @Override public String getId() { return "shearmadness"; }
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
                .replace("%points_per_shear%", String.valueOf(this.pointsPerShear))
                .replace("%streak_bonus_points%", String.valueOf(this.streakBonusPoints))
                .replace("%streak_length%", String.valueOf(this.streakThresholdForBonus))
                .replace("%max_active_sheep%", String.valueOf(this.maxActiveSheep))
                .replace("%streak_timeout_seconds%", String.valueOf(this.streakTimeoutMs / 1000));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());    }

    public void setPointsPerShear(int pointsPerShear) { this.pointsPerShear = pointsPerShear; }
    public void setStreakBonusPoints(int streakBonusPoints) { this.streakBonusPoints = streakBonusPoints; }
    public void setStreakThresholdForBonus(int streakThresholdForBonus) { this.streakThresholdForBonus = streakThresholdForBonus; }

    private class ShearMadnessListener implements Listener {
        @EventHandler
        public void onPlayerShear(PlayerShearEntityEvent event) {
            Player player = event.getPlayer();
            if (!participants.contains(player)) return;
            if (!(event.getEntity() instanceof Sheep sheep)) return;

            if (GameManager.getInstance().isPaused()) {
                event.setCancelled(true);
                return;
            }

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            long lastShearAt = lastShearTime.getOrDefault(uuid, 0L);
            int currentKnownStreak = playerShearStreaks.getOrDefault(uuid, 0);
            int newCalculatedStreak;
            String streakMessagePrefix = "";

            if (lastShearAt == 0L) {
                newCalculatedStreak = 1;
            } else if (fastShearStreakResetCooldownMs > 0 && now - lastShearAt < fastShearStreakResetCooldownMs) {
                newCalculatedStreak = 1;
                if (currentKnownStreak > 0) streakMessagePrefix = "§cToo fast! Streak reset. ";
            } else if (streakTimeoutMs > 0 && now - lastShearAt > streakTimeoutMs) {
                newCalculatedStreak = 1;
                if (currentKnownStreak > 0) streakMessagePrefix = "§eStreak timed out. ";
            } else {
                newCalculatedStreak = currentKnownStreak + 1;
            }

            playerShearStreaks.put(uuid, newCalculatedStreak);
            lastShearTime.put(uuid, now);

            GameManager.getInstance().addPointsForGame(player, getId(), pointsPerShear);
            String pointMessage = String.format("§a+%dpt! §e(Streak: %d)", pointsPerShear, newCalculatedStreak);

            if (streakThresholdForBonus > 0 && newCalculatedStreak > 0 && newCalculatedStreak % streakThresholdForBonus == 0) {
                GameManager.getInstance().addPointsForGame(player, getId(), streakBonusPoints);
                pointMessage = String.format("§6§lSTREAK! §a+%dpt, §eBonus: +%dpt! §6(Total: %d)", pointsPerShear, streakBonusPoints, newCalculatedStreak);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }

            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(streakMessagePrefix + pointMessage));
            player.playSound(player.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f);

            replaceShearedSheep(sheep);
            event.setCancelled(true);
        }

        @EventHandler
        public void onItemDrop(PlayerDropItemEvent event) {
            Player player = event.getPlayer();
            if (!participants.contains(player)) return;
            if (isPartyShears(event.getItemDrop().getItemStack())) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You cannot drop the Party Shears during the game!");
            }
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player)) return;
            Player player = (Player) event.getWhoClicked();
            if (!participants.contains(player)) return;

            if (isPartyShears(event.getCurrentItem()) || isPartyShears(event.getCursor())) {
                if (event.getClickedInventory() != null && !event.getClickedInventory().equals(player.getInventory()) && player.getOpenInventory().getTopInventory().equals(event.getClickedInventory())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot move the Party Shears!");
                    return;
                }
                if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && isPartyShears(event.getCurrentItem())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot move the Party Shears!");
                }
            }
        }

        private boolean isPartyShears(ItemStack item) {
            if (item == null || item.getType() != Material.SHEARS || !item.hasItemMeta()) return false;
            ItemMeta meta = item.getItemMeta();
            return meta != null && SHEARS_NAME.equals(meta.getDisplayName());
        }
    }
}