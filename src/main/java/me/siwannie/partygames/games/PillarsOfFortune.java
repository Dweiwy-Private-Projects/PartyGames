package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class PillarsOfFortune extends Game {

    private Listener gameListener;
    private final Map<UUID, Location> playerPillars = new HashMap<>();
    private final Map<UUID, Integer> playerMaxY = new HashMap<>();
    private final Map<UUID, UUID> lastDamagerMap = new HashMap<>();
    private final Map<UUID, Long> lastDamageTimeMap = new HashMap<>();
    private final Set<UUID> alivePlayers = new HashSet<>();
    private final List<Material> randomItemsPool = new ArrayList<>();
    private final Random random = new Random();
    private final Set<Location> playerPlacedBlocks = new HashSet<>();

    private int itemIntervalSeconds;
    private int phase1DurationSeconds;
    private int pointsPerY;
    private int pointsPerSecond;
    private int pointsPerKill;
    private int lmsBonusPoints;
    private int pointsPenaltyPerDrop;
    private boolean isPhase1;
    private boolean useAllItems;
    private final long damageCreditTime = 10000L;

    private BukkitTask itemGiverTask;
    private BukkitTask phaseSwitcherTask;
    private BukkitTask survivalPointsTask;
    private BukkitTask countdownTask;

    private Location corner1;
    private Location corner2;
    private World gameWorld;
    private Material startingBlockMaterial;
    private Location spectatorSpawn;

    public PillarsOfFortune(JavaPlugin plugin) {
        super(plugin);
        loadConfigSettings();
    }

    private void loadConfigSettings() {
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.pillarsoffortune.";
        String pointsPath = basePath + "points.";

        this.phase1DurationSeconds = config.getInt(basePath + "phase1_duration", 120);
        int phase2Duration = config.getInt(basePath + "phase2_duration", 180);
        this.duration = phase1DurationSeconds + phase2Duration;
        setDuration(this.duration);

        this.itemIntervalSeconds = config.getInt(basePath + "item_interval", 3);
        this.useAllItems = config.getBoolean(basePath + "all_items", false);

        this.pointsPerY = config.getInt(pointsPath + "points_per_y_increase", 5);
        this.pointsPerSecond = config.getInt(pointsPath + "points_per_second", 1);
        this.pointsPerKill = config.getInt(pointsPath + "points_per_kill", 50);
        this.lmsBonusPoints = config.getInt(pointsPath + "last_man_standing_bonus_points", 100);
        this.pointsPenaltyPerDrop = config.getInt(pointsPath + "points_penalty_per_drop", 1);

        try {
            String startingBlockName = config.getString(basePath + "starting_block", "BEDROCK").toUpperCase();
            this.startingBlockMaterial = Material.valueOf(startingBlockName);
            if (!this.startingBlockMaterial.isBlock()) {
                plugin.getLogger().warning("[PillarsOfFortune] Invalid starting_block material: " + startingBlockName + ". Defaulting to BEDROCK.");
                this.startingBlockMaterial = Material.BEDROCK;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[PillarsOfFortune] Could not parse starting_block. Defaulting to BEDROCK. Error: " + e.getMessage());
            this.startingBlockMaterial = Material.BEDROCK;
        }

        loadItems(config, basePath);
        loadArena(config, basePath);
    }

    private void loadItems(FileConfiguration config, String basePath) {
        randomItemsPool.clear();
        if (useAllItems) {
            Set<Material> excluded = Set.of(
                    Material.AIR, Material.VOID_AIR, Material.CAVE_AIR, Material.COMMAND_BLOCK,
                    Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.COMMAND_BLOCK_MINECART,
                    Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.BARRIER, Material.LIGHT,
                    Material.BEDROCK, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.NETHER_PORTAL,
                    Material.SPAWNER, Material.JIGSAW, Material.DEBUG_STICK, Material.KNOWLEDGE_BOOK,
                    Material.PLAYER_HEAD, Material.BUDDING_AMETHYST, Material.DRAGON_EGG, Material.ELYTRA,
                    Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.EXPERIENCE_BOTTLE,
                    Material.TRIDENT
            );
            for (Material mat : Material.values()) {
                if (mat.isItem() && !excluded.contains(mat) && !mat.name().contains("LEGACY_")) {
                    randomItemsPool.add(mat);
                }
            }
        } else {
            List<String> itemNames = config.getStringList(basePath + "random_items");
            if (itemNames.isEmpty()) {
                randomItemsPool.addAll(Arrays.asList(Material.OAK_LOG, Material.DIRT, Material.COBBLESTONE, Material.LADDER, Material.SNOWBALL));
            } else {
                for (String name : itemNames) {
                    try {
                        randomItemsPool.add(Material.valueOf(name.toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("[PillarsOfFortune] Invalid Material '" + name + "' in config. Skipping.");
                    }
                }
            }
        }
        if (randomItemsPool.isEmpty()) {
            randomItemsPool.add(Material.DIRT);
        }
    }

    private void loadArena(FileConfiguration config, String basePath) {
        String worldName = config.getString(basePath + "arena.world");
        if (worldName == null) { plugin.getLogger().severe("[PillarsOfFortune] Arena world not configured!"); return; }
        this.gameWorld = Bukkit.getWorld(worldName);
        if (this.gameWorld == null) { plugin.getLogger().severe("[PillarsOfFortune] Arena world '" + worldName + "' not found!"); return; }

        if (config.contains(basePath + "arena.corner1.x") && config.contains(basePath + "arena.corner2.x")) {
            corner1 = new Location(gameWorld, config.getDouble(basePath + "arena.corner1.x"), config.getDouble(basePath + "arena.corner1.y"), config.getDouble(basePath + "arena.corner1.z"));
            corner2 = new Location(gameWorld, config.getDouble(basePath + "arena.corner2.x"), config.getDouble(basePath + "arena.corner2.y"), config.getDouble(basePath + "arena.corner2.z"));
        } else { plugin.getLogger().severe("[PillarsOfFortune] Arena corners not configured!"); }

        String specWorldName = config.getString("games.pillarsoffortune.teleport.world", worldName);
        World sWorld = Bukkit.getWorld(specWorldName);
        if (sWorld != null && config.contains("games.pillarsoffortune.teleport.x")) {
            spectatorSpawn = new Location(sWorld,
                    config.getDouble("games.pillarsoffortune.teleport.x"),
                    config.getDouble("games.pillarsoffortune.teleport.y"),
                    config.getDouble("games.pillarsoffortune.teleport.z"),
                    (float) config.getDouble("games.pillarsoffortune.teleport.yaw", 0),
                    (float) config.getDouble("games.pillarsoffortune.teleport.pitch", 30.0));
        } else {
            spectatorSpawn = (corner1 != null && corner2 != null) ? corner1.clone().add(corner2).multiply(0.5).add(0, 20, 0) : new Location(gameWorld, 0, 100, 0);
        }
    }

    @Override
    public void startGame() {
        if (gameWorld == null || corner1 == null || corner2 == null || spectatorSpawn == null) {
            broadcast("§c[PillarsOfFortune] Error: Game arena/spawn not properly configured."); GameManager.getInstance().endCurrentGame(); return;
        }
        if (participants.isEmpty()) {
            broadcast("§c[PillarsOfFortune] No participants."); GameManager.getInstance().endCurrentGame(); return;
        }

        playerPillars.clear();
        alivePlayers.clear();
        playerMaxY.clear();
        lastDamagerMap.clear();
        lastDamageTimeMap.clear();
        playerPlacedBlocks.clear();
        participants.forEach(p -> alivePlayers.add(p.getUniqueId()));
        isPhase1 = true;

        broadcast("§fGame Started! §6Build your pillar UPWARDS!");
        sendTitleToAll("§6Pillars of Fortune", "§aPhase 1: Build UP!", 10, 70, 20);

        gameListener = new PillarsOfFortuneListener();
        registerListener(gameListener);

        assignPillarsAndTeleport();
        startItemGiver();
        startPhaseSwitcher();
        startSurvivalPointsTask();
        startCountdownTask();
    }

    @Override
    public void endGame() {
        broadcast("§fPillars of Fortune Over!");
        if (gameListener != null) { unregisterListener(gameListener); gameListener = null; }

        if (itemGiverTask != null) itemGiverTask.cancel();
        if (phaseSwitcherTask != null) phaseSwitcherTask.cancel();
        if (countdownTask != null) countdownTask.cancel();
        if (survivalPointsTask != null) survivalPointsTask.cancel();

        clearArena();

        Set<Player> allInvolvedPlayers = new HashSet<>(participants);
        for (Player player : allInvolvedPlayers) {
            if (player != null && player.isOnline()) {
                player.getInventory().clear();
                player.setGameMode(GameMode.ADVENTURE);
                try {
                    player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
                } catch (NoSuchFieldError e) {
                    player.setHealth(player.getAttribute(Attribute.valueOf("MAX_HEALTH")).getValue());
                }
                player.setFoodLevel(20);
                player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
                if (spectatorSpawn != null) player.teleport(spectatorSpawn);
            }
        }
        playerPillars.clear(); alivePlayers.clear(); playerMaxY.clear(); lastDamagerMap.clear(); lastDamageTimeMap.clear();
        playerPlacedBlocks.clear();
    }

    @Override
    public void handlePlayerJoinMidGame(Player player) {
        player.sendMessage("§ePillars of Fortune is in progress. You are now spectating.");
        player.setGameMode(GameMode.SPECTATOR);
        if (spectatorSpawn != null) { player.teleport(spectatorSpawn); }
        participants.remove(player); alivePlayers.remove(player.getUniqueId());
    }

    private void assignPillarsAndTeleport() {
        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        Set<Location> usedStartBlocks = new HashSet<>();
        int minDistanceSq = plugin.getConfig().getInt("games.pillarsoffortune.min_pillar_distance_sq", 16);

        List<Player> playersToAssign = new ArrayList<>(participants);
        for (Player player : playersToAssign) {
            if (!player.isOnline()) {
                alivePlayers.remove(player.getUniqueId());
                continue;
            }

            Location pillarStart = null;
            int attempts = 0;
            int maxAttempts = plugin.getConfig().getInt("games.pillarsoffortune.pillar_find_attempts", 1000);

            while (pillarStart == null && attempts < maxAttempts) {
                int randX = minX + random.nextInt(maxX - minX + 1);
                int randZ = minZ + random.nextInt(maxZ - minZ + 1);
                Location potentialStart = findValidStartingBlock(new Location(gameWorld, randX, 0, randZ));

                if (potentialStart != null) {
                    boolean tooClose = false;
                    for (Location used : usedStartBlocks) {
                        if (used.getBlockX() == potentialStart.getBlockX() && used.getBlockZ() == potentialStart.getBlockZ()) {
                            tooClose = true; break;
                        }
                        if (new Location(gameWorld, used.getX(),0,used.getZ()).distanceSquared(new Location(gameWorld, potentialStart.getX(),0,potentialStart.getZ())) < minDistanceSq) {
                            tooClose = true; break;
                        }
                    }
                    if (!tooClose) {
                        pillarStart = potentialStart;
                        usedStartBlocks.add(pillarStart);
                    }
                }
                attempts++;
            }

            if (pillarStart == null) {
                player.sendMessage("§c§lERROR: Could not find a valid starting pillar! Spectating.");
                plugin.getLogger().severe("[PillarsOfFortune] Could not find starting spot for " + player.getName() + ". Check config/arena.");
                handlePlayerJoinMidGame(player);
                continue;
            }

            playerPillars.put(player.getUniqueId(), pillarStart);
            playerMaxY.put(player.getUniqueId(), pillarStart.getBlockY());

            player.teleport(pillarStart.clone().add(0.5, 1.0, 0.5));
            player.getInventory().clear();
            player.setGameMode(GameMode.SURVIVAL);
            try {
                player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            } catch (NoSuchFieldError e) {
                player.setHealth(player.getAttribute(Attribute.valueOf("MAX_HEALTH")).getValue());
            }
            player.setFoodLevel(20);
            player.setFallDistance(0f);
        }
    }

    private Location findValidStartingBlock(Location approxXZ) {
        int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
        int maxY = Math.max(corner1.getBlockY(), corner2.getBlockY());

        Location currentScanLoc = approxXZ.clone();
        currentScanLoc.setY(minY);

        while (currentScanLoc.getY() <= maxY) {
            Block blockAtScan = currentScanLoc.getBlock();
            if (blockAtScan.getType() == startingBlockMaterial) {
                if (blockAtScan.getRelative(BlockFace.UP).getType().isAir() &&
                        blockAtScan.getRelative(BlockFace.UP, 2).getType().isAir()) {
                    return currentScanLoc;
                }
            }
            currentScanLoc.add(0, 1, 0);
        }
        return null;
    }

    private void startItemGiver() {
        itemGiverTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!GameManager.getInstance().isGameRunning() || GameManager.getInstance().getCurrentGame() != PillarsOfFortune.this) {
                    this.cancel(); return;
                }
                alivePlayers.forEach(uuid -> { Player p = Bukkit.getPlayer(uuid); if (p != null && p.isOnline()) { giveRandomItem(p); } });
            }
        }.runTaskTimer(plugin, 20L * 3, itemIntervalSeconds * 20L);
    }

    private void giveRandomItem(Player player) {
        if (randomItemsPool.isEmpty()) return;
        Material itemType = randomItemsPool.get(random.nextInt(randomItemsPool.size()));
        ItemStack item = new ItemStack(itemType, itemType == Material.ARROW ? (random.nextInt(3) + 2) : 1);

        if (useAllItems) {
            if (!itemType.isBlock() &&
                    itemType != Material.LADDER && itemType != Material.WATER_BUCKET &&
                    itemType != Material.LAVA_BUCKET && itemType != Material.SNOWBALL &&
                    itemType != Material.BOW && itemType != Material.ARROW &&
                    itemType != Material.TNT && itemType != Material.FLINT_AND_STEEL &&
                    itemType != Material.FISHING_ROD && itemType != Material.ENDER_PEARL) {
                itemType = randomItemsPool.get(random.nextInt(randomItemsPool.size()));
                item = new ItemStack(itemType, itemType == Material.ARROW ? (random.nextInt(3) + 2) : 1);
            }
        }

        HashMap<Integer, ItemStack> didNotFit = player.getInventory().addItem(item);
        if (!didNotFit.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), didNotFit.values().iterator().next());
            player.sendTitle("", "§cInv Full! §b+" + item.getType().name().replace("_", " "), 0, 15, 5);
        } else {
            player.sendTitle("", "§b+" + item.getType().name().replace("_", " "), 0, 15, 5);
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
    }

    private void startPhaseSwitcher() {
        phaseSwitcherTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!GameManager.getInstance().isGameRunning() || GameManager.getInstance().getCurrentGame() != PillarsOfFortune.this) {
                    this.cancel(); return;
                }
                isPhase1 = false;
                sendTitleToAll("§aPhase 2!", "§eBuild Anywhere & Fight!", 10, 60, 10);
                broadcast("§6Phase 2 has begun! PvP is enabled, build freely!");
                if (survivalPointsTask != null && survivalPointsTask.isCancelled()) {
                    startSurvivalPointsTask();
                }
            }
        }.runTaskLater(plugin, phase1DurationSeconds * 20L);
    }

    private void startSurvivalPointsTask() {
        survivalPointsTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!GameManager.getInstance().isGameRunning() || GameManager.getInstance().getCurrentGame() != PillarsOfFortune.this) {
                    this.cancel(); return;
                }
                if (isPhase1) return;

                alivePlayers.forEach(uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        GameManager.getInstance().addPointsForGame(p, getId(), pointsPerSecond);
                    }
                });
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void eliminatePlayer(Player eliminated, Player killer) {
        UUID eliminatedUUID = eliminated.getUniqueId();
        if (!alivePlayers.contains(eliminatedUUID)) return;

        alivePlayers.remove(eliminatedUUID);
        eliminated.getInventory().clear();
        eliminated.setGameMode(GameMode.SPECTATOR);
        if (spectatorSpawn != null) eliminated.teleport(spectatorSpawn);

        GameManager.getInstance().addPointsForGame(eliminated, getId(), -pointsPerKill);
        eliminated.sendMessage("§cYou were eliminated! §e-" + pointsPerKill + " points.");

        String cause = " was eliminated!";
        if (killer != null && alivePlayers.contains(killer.getUniqueId())) {
            GameManager.getInstance().addPointsForGame(killer, getId(), pointsPerKill);
            cause = " was eliminated by " + killer.getName() + "!";
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            killer.sendMessage("§aYou eliminated " + eliminated.getName() + "! §6+" + pointsPerKill + " points!");
        }
        lastDamagerMap.remove(eliminatedUUID);
        lastDamageTimeMap.remove(eliminatedUUID);

        broadcast("§e" + eliminated.getName() + cause + " §7(" + alivePlayers.size() + " left)");

        if (alivePlayers.size() <= 1 && participants.size() > 0) {
            Player winner = alivePlayers.isEmpty() ? null : Bukkit.getPlayer(alivePlayers.iterator().next());
            if (winner != null) {
                broadcast("§6§l" + winner.getName() + " is the last one standing and wins PillarsOfFortune!");
                GameManager.getInstance().addPointsForGame(winner, getId(), lmsBonusPoints);
                winner.sendMessage("§bYou earned a Last Man Standing bonus of §e" + lmsBonusPoints + " points!");
            } else if (participants.size() > 1){
                broadcast("§eEveryone has been eliminated! No winner.");
            } else if (participants.size() == 1 && alivePlayers.isEmpty()){
                broadcast("§eYou were eliminated!");
            }
            GameManager.getInstance().endCurrentGame();
        }
    }

    private void clearArena() {
        if (gameWorld == null || corner1 == null || corner2 == null) return;
        plugin.getLogger().info("[PillarsOfFortune] Starting arena clear (Player blocks & Items)...");

        int minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        int maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        int minY = Math.min(corner1.getBlockY(), corner2.getBlockY());
        int maxY = gameWorld.getMaxHeight() - 1;
        int minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        int maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());

        gameWorld.getEntitiesByClass(org.bukkit.entity.Item.class).forEach(itemEntity -> {
            Location loc = itemEntity.getLocation();
            if (loc.getWorld().equals(gameWorld) &&
                    loc.getX() >= minX && loc.getX() <= maxX &&
                    loc.getY() >= minY && loc.getY() <= maxY &&
                    loc.getZ() >= minZ && loc.getZ() <= maxZ) {
                itemEntity.remove();
            }
        });

        int clearedCount = 0;
        for (Location loc : playerPlacedBlocks) {
            if (loc.getWorld().equals(gameWorld) && loc.getBlock().getType() != Material.AIR) {
                loc.getBlock().setType(Material.AIR, false);
                clearedCount++;
            }
        }
        playerPlacedBlocks.clear();

        plugin.getLogger().info("[PillarsOfFortune] Arena clear finished. Removed " + clearedCount + " player blocks and items.");
    }

    private void startCountdownTask() {
        countdownTask = new BukkitRunnable() {
            int timeLeft = duration;
            @Override
            public void run() {
                if (!GameManager.getInstance().isGameRunning() || GameManager.getInstance().getCurrentGame() != PillarsOfFortune.this || timeLeft <= 0) {
                    this.cancel(); return;
                }
                if (timeLeft == 60 || timeLeft == 30 || (timeLeft <= 10 && timeLeft > 0)) {
                    sendTitleToAll("§6Ending in", "§c" + timeLeft + "s...", 0, 25, 5);
                }
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        participants.forEach(p -> { if (p.isOnline()) p.sendTitle(title, subtitle, fadeIn, stay, fadeOut); });
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getGameMode() == GameMode.SPECTATOR && p.getWorld().equals(gameWorld))
                .forEach(p -> p.sendTitle(title, subtitle, fadeIn, stay, fadeOut));
    }

    @Override public String getName() { return "Pillars of Fortune"; }
    @Override public String getId() { return "pillarsoffortune"; }
    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());
        FileConfiguration config = plugin.getConfig();

        if (rawExplanation.startsWith("§7No explanation found for") || rawExplanation.startsWith("§cError: Explanations unavailable.")) {
            plugin.getLogger().warning("Explanation string not found for: " + getId() + ". Using default/error message.");
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        int phase2Duration = this.duration - this.phase1DurationSeconds;

        String formatted = rawExplanation
                .replace("%duration%", String.valueOf(this.duration))
                .replace("%phase1_duration_seconds%", String.valueOf(this.phase1DurationSeconds))
                .replace("%phase2_duration_seconds%", String.valueOf(phase2Duration))
                .replace("%item_interval_seconds%", String.valueOf(this.itemIntervalSeconds))
                .replace("%points.per_y_increase%", String.valueOf(this.pointsPerY))
                .replace("%points.per_second_phase2%", String.valueOf(this.pointsPerSecond))
                .replace("%points.per_kill%", String.valueOf(this.pointsPerKill))
                .replace("%points.last_man_standing_bonus%", String.valueOf(this.lmsBonusPoints))
                .replace("%points.penalty_per_drop%", String.valueOf(this.pointsPenaltyPerDrop));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());    }

    private class PillarsOfFortuneListener implements Listener {

        @EventHandler
        public void onBlockPlace(BlockPlaceEvent event) {
            Player player = event.getPlayer();
            if (!alivePlayers.contains(player.getUniqueId())) { event.setCancelled(true); return; }
            Location pillarStartLoc = playerPillars.get(player.getUniqueId());
            if (pillarStartLoc == null) { event.setCancelled(true); return; }

            Block placedBlock = event.getBlockPlaced();

            if (isPhase1) {
                if (placedBlock.getX() != pillarStartLoc.getBlockX() || placedBlock.getZ() != pillarStartLoc.getBlockZ()) {
                    event.setCancelled(true);
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§cPhase 1: You can only build UP on your pillar!"));
                    return;
                } else {
                    int currentMaxPillarY = playerMaxY.getOrDefault(player.getUniqueId(), pillarStartLoc.getBlockY());
                    int newPlacedY = placedBlock.getY();
                    if (newPlacedY > currentMaxPillarY) {
                        int diff = newPlacedY - currentMaxPillarY;
                        int pointsAwarded = diff * pointsPerY;
                        GameManager.getInstance().addPointsForGame(player, getId(), pointsAwarded);
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§a+" + pointsAwarded + " points! §e(New Height: " + newPlacedY + ")"));
                        playerMaxY.put(player.getUniqueId(), newPlacedY);
                    }
                }
            }
            playerPlacedBlocks.add(placedBlock.getLocation());
        }

        @EventHandler
        public void onPlayerMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            if (!alivePlayers.contains(player.getUniqueId())) return;

            Location pillarStartLoc = playerPillars.get(player.getUniqueId());
            if (pillarStartLoc == null) return;

            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null || from == null) return;

            if (isPhase1) {
                if (to.getBlockX() != pillarStartLoc.getBlockX() || to.getBlockZ() != pillarStartLoc.getBlockZ()) {
                    Location newTo = player.getLocation();
                    newTo.setX(pillarStartLoc.getX() + 0.5);
                    newTo.setZ(pillarStartLoc.getZ() + 0.5);
                    player.teleport(newTo);
                }
            }

            if (player.isOnGround() && to.getBlockY() <= pillarStartLoc.getBlockY() && from.getY() > to.getY()) {
                plugin.getLogger().info("[PillarsOfFortune] Eliminating " + player.getName() + " for landing at Y=" + to.getBlockY() + " (Start Y=" + pillarStartLoc.getBlockY() + ")");

                long now = System.currentTimeMillis();
                UUID killerUUID = lastDamagerMap.get(player.getUniqueId());
                Player killer = null;
                if (killerUUID != null && (now - lastDamageTimeMap.getOrDefault(player.getUniqueId(), 0L)) < damageCreditTime) {
                    killer = Bukkit.getPlayer(killerUUID);
                }
                eliminatePlayer(player, killer);
                return;
            }

            int voidY = Math.min(corner1.getBlockY(), corner2.getBlockY()) - 10;
            if (to.getY() < voidY) {
                eliminatePlayer(player, null);
            }
        }

        @EventHandler
        public void onPlayerDrop(PlayerDropItemEvent event) {
            Player player = event.getPlayer();
            if (alivePlayers.contains(player.getUniqueId())) {
                ItemStack droppedItemStack = event.getItemDrop().getItemStack();
                String itemName = droppedItemStack.getType().name().toLowerCase().replace("_", " ");

                event.getItemDrop().remove();

                if (pointsPenaltyPerDrop > 0) {
                    GameManager.getInstance().addPointsForGame(player, getId(), -pointsPenaltyPerDrop);
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§e" + itemName + " dropped! §c-" + pointsPenaltyPerDrop + " points."));
                } else {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§e" + itemName + " dropped and disappeared."));
                }
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.7f, 1.0f);
            } else {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onEntityRegainHealth(EntityRegainHealthEvent event) {
            if (event.getEntity() instanceof Player player && alivePlayers.contains(player.getUniqueId())) {
                if (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED ||
                        event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
                    event.setCancelled(true);
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onEntityDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            if (!alivePlayers.contains(player.getUniqueId())) { event.setCancelled(true); return; }

            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                event.setDamage(player.getHealth() * 2);
            }

            if ((player.getHealth() - event.getFinalDamage()) <= 0) {
                event.setCancelled(true);
                Player killer = null;
                EntityDamageEvent lastDamageCause = player.getLastDamageCause();
                if (lastDamageCause instanceof EntityDamageByEntityEvent edbe) {
                    Entity damager = edbe.getDamager();
                    if (damager instanceof Player p) { killer = p; }
                    else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) { killer = p; }
                }
                if(killer == null){
                    long now = System.currentTimeMillis();
                    UUID killerUUID = lastDamagerMap.get(player.getUniqueId());
                    if (killerUUID != null && (now - lastDamageTimeMap.getOrDefault(player.getUniqueId(), 0L)) < damageCreditTime) {
                        killer = Bukkit.getPlayer(killerUUID);
                    }
                }
                eliminatePlayer(player, killer);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof Player victim)) return;
            if (!alivePlayers.contains(victim.getUniqueId())) { event.setCancelled(true); return; }

            Player attacker = null;
            Entity damager = event.getDamager();
            if (damager instanceof Player p) { attacker = p; }
            else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) { attacker = p; }

            if (attacker == null || !alivePlayers.contains(attacker.getUniqueId())) {
                if(!(damager instanceof Projectile)) { event.setCancelled(true); }
                return;
            }

            if (attacker.equals(victim)) { event.setCancelled(true); return; }

            lastDamagerMap.put(victim.getUniqueId(), attacker.getUniqueId());
            lastDamageTimeMap.put(victim.getUniqueId(), System.currentTimeMillis());

            if (isPhase1) {
                if (!(damager instanceof Projectile)) {
                    event.setCancelled(true);
                    attacker.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§cMelee PvP is disabled in Phase 1!"));
                }
            }
        }

        @EventHandler
        public void onBlockBreak(BlockBreakEvent event) {
            Player player = event.getPlayer();
            if (!alivePlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (event.getBlock().getType() == startingBlockMaterial) {
                event.setCancelled(true);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§cYou cannot break the starting blocks!"));
            } else if (playerPlacedBlocks.contains(event.getBlock().getLocation())) {
                playerPlacedBlocks.remove(event.getBlock().getLocation());
                event.setCancelled(false);
            } else {
                event.setCancelled(true);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§cYou can only break blocks placed during the game!"));
            }
        }
        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();

            if (GameManager.getInstance().isGameRunning() &&
                    GameManager.getInstance().getCurrentGame() == PillarsOfFortune.this &&
                    alivePlayers.contains(playerUUID)) {

                plugin.getLogger().info("[PillarsOfFortune] Player " + player.getName() + " quit mid-game. Treating as eliminated.");
                eliminatePlayer(player, null);
            }
        }
    }
}