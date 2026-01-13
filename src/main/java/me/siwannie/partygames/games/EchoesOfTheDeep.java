package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

public class EchoesOfTheDeep extends Game {

    private static class TreasureType {
        String displayName;
        Material itemMaterial;
        int points;
        public TreasureType(String displayName, Material itemMaterial, int points) {
            this.displayName = ChatColor.translateAlternateColorCodes('&', displayName);
            this.itemMaterial = itemMaterial;
            this.points = points;
        }
    }

    private static class ActiveDisplayItem {
        Location location; String internalType; Object typeObject; ArmorStand displayEntity; String collectionMessage;
        public ActiveDisplayItem(Location location, TreasureType type, ArmorStand displayEntity) {
            this.location = location; this.internalType = "TREASURE"; this.typeObject = type; this.displayEntity = displayEntity; this.collectionMessage = null;
        }
        public ActiveDisplayItem(Location location, PowerUpType type, ArmorStand displayEntity, String message) {
            this.location = location; this.internalType = type.configName; this.typeObject = type; this.displayEntity = displayEntity; this.collectionMessage = ChatColor.translateAlternateColorCodes('&', message);
        }
        public boolean isTreasure() { return "TREASURE".equals(internalType); }
        public boolean isPowerUp() { return !isTreasure(); }
        public TreasureType getTreasureType() { return isTreasure() ? (TreasureType) typeObject : null; }
        public PowerUpType getPowerUpType() { return isPowerUp() ? (PowerUpType) typeObject : null; }
    }

    private static class PowerUpType {
        String configName; String displayName; Material itemMaterial; String collectionMessage;
        int effectDurationSeconds; int effectAmplifier; PotionEffectType potionEffect;
        public PowerUpType(String configName, String displayName, Material material, String message, int duration, int amplifier, PotionEffectType potion) {
            this.configName = configName; this.displayName = ChatColor.translateAlternateColorCodes('&', displayName); this.itemMaterial = material; this.collectionMessage = ChatColor.translateAlternateColorCodes('&', message);
            this.effectDurationSeconds = duration; this.effectAmplifier = amplifier; this.potionEffect = potion;
        }
    }

    private final Random random = new Random();
    private Listener gameListener;
    private Location gameSpawnLocation;

    private boolean visionReductionEnabled;
    private PotionEffectType visionEffectType;
    private int visionEffectAmplifier;
    private boolean applyVisionUnderwaterOnly;
    private BukkitTask visionCheckTask;

    private final Map<Location, ActiveDisplayItem> activeTreasuresAndPowerUps = new HashMap<>();
    private final List<TreasureType> predefinedTreasureTypes = new ArrayList<>();
    private int effectiveMaxActiveTreasures;
    private int treasuresBasePerPlayer;
    private int minTotalActiveTreasures;
    private int maxTotalActiveTreasuresCap;
    private double minRespawnDistanceFromPlayerSquared;
    private double treasureCollectionRadiusSquared;
    private double minDistanceBetweenItemsSquared;
    private Location treasureSpawnCorner1, treasureSpawnCorner2;

    private final List<PowerUpType> predefinedPowerUpTypes = new ArrayList<>();
    private boolean powerUpsEnabled;
    private int maxActivePowerUps;
    private double powerUpCollectionRadiusSquared;
    private double minPowerUpSpawnDistFromCenter, maxPowerUpSpawnDistFromCenter;
    private double minDistanceBetweenPowerUpsSquared;

    private int pointsDamagePenalty, pointsCheckpointBonus, pointsDrowningRespawnPenalty;

    private final Map<UUID, Integer> oxygenLevels = new HashMap<>();
    private BukkitTask oxygenTask;
    private int oxygenDepletionAmount, oxygenRecoveryAmount, oxygenIntervalTicks;

    private boolean hostileMobsEnabled;
    private List<EntityType> hostileMobTypesToSpawn = new ArrayList<>();
    private int hostileMobIntervalTicks, hostileMobMaxActive;
    private BukkitTask mobSpawningTask;
    private final List<LivingEntity> spawnedGameMobs = new ArrayList<>();

    private final Set<Location> definedCheckpoints = new HashSet<>();
    private final Map<UUID, Location> playerLastCheckpoint = new HashMap<>();
    private final Set<UUID> damagedRecently = new HashSet<>();

    public EchoesOfTheDeep(JavaPlugin plugin) {
        super(plugin);
        loadConfigSettings();
        initializeTreasureTypesFromConfig();
        initializePowerUpTypesFromConfig();
    }

    private void loadConfigSettings() {
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.echoesofthedeep.";

        this.duration = config.getInt(basePath + "duration", 420);
        setDuration(this.duration);

        String teleportWorldName = config.getString(basePath + "teleport.world", "world");
        World mainGameWorld = Bukkit.getWorld(teleportWorldName);
        if (mainGameWorld == null) plugin.getLogger().severe("[EchoesOfTheDeep] Main game world '" + teleportWorldName + "' not found!");
        else if (config.contains(basePath + "teleport.x")) gameSpawnLocation = new Location(mainGameWorld, config.getDouble(basePath + "teleport.x"), config.getDouble(basePath + "teleport.y"), config.getDouble(basePath + "teleport.z"), (float) config.getDouble(basePath + "teleport.yaw", 0), (float) config.getDouble(basePath + "teleport.pitch", 0));
        else plugin.getLogger().severe("[EchoesOfTheDeep] Main game teleport coordinates not configured!");

        this.visionReductionEnabled = config.getBoolean(basePath + "vision_reduction.enabled", true);
        String effectTypeName = config.getString(basePath + "vision_reduction.effect_type", "DARKNESS").toUpperCase();
        try { this.visionEffectType = PotionEffectType.getByName(effectTypeName);
            if (this.visionEffectType == null) { this.visionEffectType = PotionEffectType.DARKNESS; plugin.getLogger().warning("[EchoesOfTheDeep] Invalid vision_reduction.effect_type: " + effectTypeName + ". Defaulting to DARKNESS."); }}
        catch (IllegalArgumentException e) { this.visionEffectType = PotionEffectType.DARKNESS; plugin.getLogger().warning("[EchoesOfTheDeep] Error parsing vision_reduction.effect_type. Defaulting to DARKNESS.");}
        this.visionEffectAmplifier = config.getInt(basePath + "vision_reduction.amplifier", 0);
        this.applyVisionUnderwaterOnly = config.getBoolean(basePath + "vision_reduction.apply_underwater_only", true);

        this.treasuresBasePerPlayer = config.getInt(basePath + "treasures.max_active_per_player", 2);
        this.minTotalActiveTreasures = config.getInt(basePath + "treasures.min_total_active", 5);
        this.maxTotalActiveTreasuresCap = config.getInt(basePath + "treasures.max_total_active_cap", 30);
        double minPlayerDist = config.getDouble(basePath + "treasures.min_spawn_distance_from_player", 8.0);
        this.minRespawnDistanceFromPlayerSquared = minPlayerDist * minPlayerDist;
        double collectionRadius = config.getDouble(basePath + "treasures.collection_radius", 2.0);
        this.treasureCollectionRadiusSquared = collectionRadius * collectionRadius;
        double minItemDist = config.getDouble(basePath + "treasures.min_distance_between_treasures", 10.0);
        this.minDistanceBetweenItemsSquared = minItemDist * minItemDist;

        ConfigurationSection treasureSpawnAreaSection = config.getConfigurationSection(basePath + "treasures.spawn_area");
        if (treasureSpawnAreaSection != null && mainGameWorld != null) {
            World worldForTreasures = mainGameWorld;
            String specificTreasureWorldName = treasureSpawnAreaSection.getString("world");
            if (specificTreasureWorldName != null && !specificTreasureWorldName.isEmpty()) {
                World tempTreasureWorld = Bukkit.getWorld(specificTreasureWorldName);
                if (tempTreasureWorld != null) worldForTreasures = tempTreasureWorld;
                else plugin.getLogger().warning("[EchoesOfTheDeep] Specified world '" + specificTreasureWorldName + "' for 'treasures.spawn_area' not found! Defaulting to main game world.");
            }
            if (treasureSpawnAreaSection.contains("corner1.x") && treasureSpawnAreaSection.contains("corner2.x")) {
                treasureSpawnCorner1 = new Location(worldForTreasures, treasureSpawnAreaSection.getDouble("corner1.x"), treasureSpawnAreaSection.getDouble("corner1.y"), treasureSpawnAreaSection.getDouble("corner1.z"));
                treasureSpawnCorner2 = new Location(worldForTreasures, treasureSpawnAreaSection.getDouble("corner2.x"), treasureSpawnAreaSection.getDouble("corner2.y"), treasureSpawnAreaSection.getDouble("corner2.z"));
            } else plugin.getLogger().warning("[EchoesOfTheDeep] Treasure 'spawn_area.corners' not fully configured.");
        } else plugin.getLogger().warning("[EchoesOfTheDeep] 'treasures.spawn_area' section not configured or main game world invalid.");

        this.powerUpsEnabled = config.getBoolean(basePath + "power_ups.enabled", true);
        this.maxActivePowerUps = config.getInt(basePath + "power_ups.max_active", 2);
        this.minPowerUpSpawnDistFromCenter = config.getDouble(basePath + "power_ups.min_spawn_distance_from_game_spawn", 60.0);
        this.maxPowerUpSpawnDistFromCenter = config.getDouble(basePath + "power_ups.max_spawn_distance_from_game_spawn", 120.0);
        double powerUpCollectionRadius = config.getDouble(basePath + "power_ups.collection_radius", 2.5);
        this.powerUpCollectionRadiusSquared = powerUpCollectionRadius * powerUpCollectionRadius;
        double minDistBetweenPUs = config.getDouble(basePath + "power_ups.min_distance_between_powerups", 20.0);
        this.minDistanceBetweenPowerUpsSquared = minDistBetweenPUs * minDistBetweenPUs;

        this.pointsDamagePenalty = config.getInt(basePath + "points.damage_penalty", -3);
        this.pointsCheckpointBonus = config.getInt(basePath + "points.checkpoint_bonus", 20);
        this.pointsDrowningRespawnPenalty = config.getInt(basePath + "points.drowning_respawn_penalty", -5);

        this.oxygenDepletionAmount = config.getInt(basePath + "oxygen.depletion_amount", 2);
        this.oxygenRecoveryAmount = config.getInt(basePath + "oxygen.recovery_amount", 5);
        this.oxygenIntervalTicks = config.getInt(basePath + "oxygen.interval_ticks", 20);

        this.hostileMobsEnabled = config.getBoolean(basePath + "spawn_mobs.enabled", true);
        List<String> mobTypeNames = config.getStringList(basePath + "spawn_mobs.types");
        this.hostileMobTypesToSpawn.clear();
        for (String name : mobTypeNames) { try { EntityType type = EntityType.valueOf(name.toUpperCase()); if (type.isAlive()) this.hostileMobTypesToSpawn.add(type); } catch (IllegalArgumentException e) { plugin.getLogger().warning("[EchoesOfTheDeep] Invalid mob type in spawn_mobs.types: " + name); }}
        this.hostileMobIntervalTicks = config.getInt(basePath + "spawn_mobs.interval_ticks", 200);
        this.hostileMobMaxActive = config.getInt(basePath + "spawn_mobs.max_active", 10);
    }

    private void initializeTreasureTypesFromConfig() {
        predefinedTreasureTypes.clear();
        FileConfiguration config = plugin.getConfig();
        List<Map<?, ?>> treasureList = config.getMapList("games.echoesofthedeep.treasures.types");
        if (treasureList == null || treasureList.isEmpty()) { predefinedTreasureTypes.add(new TreasureType("§eFallback Pearl", Material.ENDER_PEARL, 3)); return; }
        for (Map<?, ?> map : treasureList) {
            String name = (String) map.get("display_name");
            String matName = (String) map.get("material");
            Object ptsObj = map.get("points"); int pts = (ptsObj instanceof Number) ? ((Number) ptsObj).intValue() : 0;
            if (name == null || matName == null) continue;
            try { predefinedTreasureTypes.add(new TreasureType(name, Material.valueOf(matName.toUpperCase()), pts)); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("[EchoesOfTheDeep] Invalid material for treasure '" + name + "': " + matName); }
        }
        if (predefinedTreasureTypes.isEmpty()) { predefinedTreasureTypes.add(new TreasureType("§eFallback Pearl", Material.ENDER_PEARL, 3)); }
    }

    private void initializePowerUpTypesFromConfig() {
        predefinedPowerUpTypes.clear();
        if (!powerUpsEnabled) return;
        FileConfiguration config = plugin.getConfig();
        List<Map<?, ?>> powerUpList = config.getMapList("games.echoesofthedeep.power_ups.types");
        if (powerUpList == null || powerUpList.isEmpty()) return;

        for (Map<?, ?> map : powerUpList) {
            String typeName = (String) map.get("type");
            String displayName = (String) map.get("display_name");
            String matName = (String) map.get("material");
            String message = (String) map.get("message");

            Object durationObj = map.get("effect_duration_seconds");
            int duration = (durationObj instanceof Number) ? ((Number) durationObj).intValue() : 0;

            Object amplifierObj = map.get("effect_amplifier");
            int amplifier = (amplifierObj instanceof Number) ? ((Number) amplifierObj).intValue() : 0;

            if (typeName == null || displayName == null || matName == null || message == null) continue;
            Material material;
            try { material = Material.valueOf(matName.toUpperCase()); }
            catch (IllegalArgumentException e) { continue; }

            PotionEffectType potEffectType = null;
            if ("ILLUMINATION".equalsIgnoreCase(typeName)) potEffectType = PotionEffectType.NIGHT_VISION;
            else if ("SPEED_BOOST".equalsIgnoreCase(typeName)) potEffectType = PotionEffectType.DOLPHINS_GRACE;

            predefinedPowerUpTypes.add(new PowerUpType(typeName, displayName, material, message, duration, amplifier, potEffectType));
        }
    }

    @Override
    public void startGame() {
        if (gameSpawnLocation == null || gameSpawnLocation.getWorld() == null) { GameManager.getInstance().endCurrentGame(); return; }
        if (predefinedTreasureTypes.isEmpty() && (!powerUpsEnabled || predefinedPowerUpTypes.isEmpty())) { GameManager.getInstance().endCurrentGame(); return; }
        if (this.participants.isEmpty()) { GameManager.getInstance().endCurrentGame(); return; }

        this.effectiveMaxActiveTreasures = Math.max(this.minTotalActiveTreasures, this.participants.size() * this.treasuresBasePerPlayer);
        this.effectiveMaxActiveTreasures = Math.min(this.effectiveMaxActiveTreasures, this.maxTotalActiveTreasuresCap);

        playerLastCheckpoint.clear(); damagedRecently.clear(); oxygenLevels.clear(); activeTreasuresAndPowerUps.clear(); definedCheckpoints.clear(); spawnedGameMobs.clear();

        PotionEffect waterBreathing = new PotionEffect(PotionEffectType.WATER_BREATHING, Integer.MAX_VALUE, 0, false, false, false);
        PotionEffect visionEff = null;
        if (visionReductionEnabled && visionEffectType != null && !applyVisionUnderwaterOnly) {
            visionEff = new PotionEffect(visionEffectType, Integer.MAX_VALUE, visionEffectAmplifier, false, false, false);
        }

        for (Player p : participants) {
            p.teleport(gameSpawnLocation);
            p.addPotionEffect(waterBreathing);
            if (visionEff != null) p.addPotionEffect(visionEff);
            oxygenLevels.put(p.getUniqueId(), 100);
            playerLastCheckpoint.put(p.getUniqueId(), gameSpawnLocation.getBlock().getLocation());
        }

        setupInitialCheckpointsInGameArea();
        setupInitialCollectibles();

        sendTitleToAll("§3Echoes of the Deep", "§bDive and Collect!", 10, 70, 20);
        broadcast("§bGame Started! §fDive, collect items, manage oxygen, and find checkpoints!");

        gameListener = new EchoesListener();
        registerListener(gameListener);
        startOxygenSystem();
        startMobSpawningSystem();
        if (visionReductionEnabled && applyVisionUnderwaterOnly && visionEffectType != null) {
            startVisionCheckTask();
        }
    }

    @Override
    public void endGame() {
        broadcast("§3Echoes of the Deep §fhas ended!");
        if (gameListener != null) unregisterListener(gameListener);
        if (oxygenTask != null) oxygenTask.cancel();
        if (mobSpawningTask != null) mobSpawningTask.cancel();
        if (visionCheckTask != null) visionCheckTask.cancel();

        for (ActiveDisplayItem item : activeTreasuresAndPowerUps.values()) {
            if (item.displayEntity != null && !item.displayEntity.isDead()) item.displayEntity.remove();
        }
        activeTreasuresAndPowerUps.clear();
        spawnedGameMobs.forEach(mob -> { if (mob != null && !mob.isDead()) mob.remove(); });
        spawnedGameMobs.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.WATER_BREATHING);
            if (visionEffectType != null) p.removePotionEffect(visionEffectType);
            if(predefinedPowerUpTypes !=null) {
                for(PowerUpType put : predefinedPowerUpTypes) {
                    if(put.potionEffect != null) p.removePotionEffect(put.potionEffect);
                }
            }
        }
        definedCheckpoints.clear(); playerLastCheckpoint.clear(); oxygenLevels.clear(); damagedRecently.clear();
    }

    private void setupInitialCollectibles() {
        activeTreasuresAndPowerUps.clear();
        for (int i = 0; i < effectiveMaxActiveTreasures; i++) {
            spawnOneCollectible(false, true);
        }
        if (powerUpsEnabled) {
            for (int i = 0; i < maxActivePowerUps; i++) {
                spawnOneCollectible(false, false);
            }
        }
    }

    private void setupInitialCheckpointsInGameArea() {
        definedCheckpoints.clear();
        World world = gameSpawnLocation.getWorld();
        if (world == null) return;
        Location cp1Loc = gameSpawnLocation.clone().add(random.nextInt(40) - 20, -15 - random.nextInt(10), random.nextInt(40) - 20).getBlock().getLocation();
        Block blockAtCP = world.getBlockAt(cp1Loc);
        if (blockAtCP.isPassable() || blockAtCP.isLiquid()) {
            definedCheckpoints.add(cp1Loc);
        }
    }

    private void spawnOneCollectible(boolean avoidPlayers, boolean isTreasure) {
        World world;
        Location spawnC1, spawnC2;
        double collectionRadiusSq;
        double relevantMinDistBetweenItemsSq;

        if (isTreasure) {
            if (this.treasureSpawnCorner1 != null && this.treasureSpawnCorner2 != null && this.treasureSpawnCorner1.getWorld() != null) {
                world = this.treasureSpawnCorner1.getWorld();
                spawnC1 = this.treasureSpawnCorner1;
                spawnC2 = this.treasureSpawnCorner2;
            } else if (gameSpawnLocation != null && gameSpawnLocation.getWorld() != null) {
                world = gameSpawnLocation.getWorld();
                int r = 30, dMin = 5, dMax = 25;
                spawnC1 = gameSpawnLocation.clone().add(-r, -dMax, -r);
                spawnC2 = gameSpawnLocation.clone().add(r, -dMin, r);
            } else { return; }
            collectionRadiusSq = this.treasureCollectionRadiusSquared;
            relevantMinDistBetweenItemsSq = this.minDistanceBetweenItemsSquared;
        } else {
            if (!powerUpsEnabled || predefinedPowerUpTypes.isEmpty() || gameSpawnLocation == null || gameSpawnLocation.getWorld() == null) return;
            world = gameSpawnLocation.getWorld();
            collectionRadiusSq = this.powerUpCollectionRadiusSquared;
            relevantMinDistBetweenItemsSq = this.minDistanceBetweenPowerUpsSquared;
            spawnC1 = null;
            spawnC2 = null;
        }

        int attempts = 0;
        final int maxAttempts = 150;

        while (attempts < maxAttempts) {
            attempts++;
            Location potentialBlockLoc;

            if (!isTreasure && spawnC1 == null) {
                double angle = random.nextDouble() * 2 * Math.PI;
                double distance = this.minPowerUpSpawnDistFromCenter + random.nextDouble() * (this.maxPowerUpSpawnDistFromCenter - this.minPowerUpSpawnDistFromCenter);
                if (distance > this.maxPowerUpSpawnDistFromCenter) distance = this.maxPowerUpSpawnDistFromCenter;
                if (distance < this.minPowerUpSpawnDistFromCenter) distance = this.minPowerUpSpawnDistFromCenter;

                double dX = Math.cos(angle) * distance;
                double dZ = Math.sin(angle) * distance;

                int ySearchRange = 20;
                int startY = gameSpawnLocation.getBlockY();
                int foundY = -1;

                for(int yTry = startY; yTry >= Math.max(world.getMinHeight(), startY - ySearchRange) ; yTry--){
                    Block b = world.getBlockAt(gameSpawnLocation.getBlockX() + (int)dX, yTry, gameSpawnLocation.getBlockZ() + (int)dZ);
                    if(b.getType() == Material.WATER || b.getType() == Material.KELP_PLANT || b.getType() == Material.KELP){
                        foundY = yTry;
                        break;
                    }
                }
                if(foundY == -1) continue;
                potentialBlockLoc = new Location(world, gameSpawnLocation.getX() + dX, foundY, gameSpawnLocation.getZ() + dZ);

            } else {
                if (spawnC1 == null || spawnC2 == null) {
                    return;
                }
                double minX = Math.min(spawnC1.getX(), spawnC2.getX());
                double maxX = Math.max(spawnC1.getX(), spawnC2.getX());
                double minY = Math.min(spawnC1.getY(), spawnC2.getY());
                double maxY = Math.max(spawnC1.getY(), spawnC2.getY());
                double minZ = Math.min(spawnC1.getZ(), spawnC2.getZ());
                double maxZ = Math.max(spawnC1.getZ(), spawnC2.getZ());
                potentialBlockLoc = new Location(world, minX + (maxX - minX) * random.nextDouble(), minY + (maxY - minY) * random.nextDouble(), minZ + (maxZ - minZ) * random.nextDouble());
            }

            Material blockMat = potentialBlockLoc.getBlock().getType();
            if (!(blockMat == Material.WATER || blockMat == Material.KELP_PLANT || blockMat == Material.KELP || blockMat == Material.SEAGRASS || blockMat == Material.TALL_SEAGRASS)) {
                continue;
            }

            Location armorStandSpawnLoc = potentialBlockLoc.getBlock().getLocation().add(0.5, blockMat == Material.WATER ? -0.25 : 0, 0.5);
            if (!armorStandSpawnLoc.getBlock().isPassable() && armorStandSpawnLoc.getBlock().getType() != Material.WATER) {
                armorStandSpawnLoc.add(0,1,0);
                if (!armorStandSpawnLoc.getBlock().isPassable() && armorStandSpawnLoc.getBlock().getType() != Material.WATER) {
                    continue;
                }
            }

            boolean tooCloseToExisting = false;
            for (ActiveDisplayItem existingItem : activeTreasuresAndPowerUps.values()) {
                double checkDistSq = (isTreasure == existingItem.isTreasure()) ? relevantMinDistBetweenItemsSq : this.minDistanceBetweenItemsSquared;
                if (armorStandSpawnLoc.distanceSquared(existingItem.location) < checkDistSq) {
                    tooCloseToExisting = true;
                    break;
                }
            }
            if (tooCloseToExisting) continue;

            if (avoidPlayers) {
                boolean tooCloseToPlayer = false;
                for (Player p : participants) {
                    if (p.getWorld().equals(world) && p.getLocation().distanceSquared(armorStandSpawnLoc) < minRespawnDistanceFromPlayerSquared) {
                        tooCloseToPlayer = true;
                        break;
                    }
                }
                if (tooCloseToPlayer) continue;
            }

            Material itemMat; String customNameDisplay; ActiveDisplayItem newItem;
            if (isTreasure) {
                if (predefinedTreasureTypes.isEmpty()) return;
                TreasureType selectedTreasureType = predefinedTreasureTypes.get(random.nextInt(predefinedTreasureTypes.size()));
                itemMat = selectedTreasureType.itemMaterial;
                String pointColor = selectedTreasureType.points >= 0 ? "§a" : "§c";
                customNameDisplay = (selectedTreasureType.points >= 0 ? "§e" : "§c") + selectedTreasureType.displayName + " §7(" + pointColor + (selectedTreasureType.points > 0 ? "+" : "") + selectedTreasureType.points + "§7)";
                newItem = new ActiveDisplayItem(armorStandSpawnLoc, selectedTreasureType, null);
            } else {
                if (predefinedPowerUpTypes.isEmpty()) return;
                PowerUpType selectedPowerUpType = predefinedPowerUpTypes.get(random.nextInt(predefinedPowerUpTypes.size()));
                itemMat = selectedPowerUpType.itemMaterial;
                customNameDisplay = selectedPowerUpType.displayName;
                newItem = new ActiveDisplayItem(armorStandSpawnLoc, selectedPowerUpType, null, selectedPowerUpType.collectionMessage);
            }

            ArmorStand as = (ArmorStand) world.spawnEntity(armorStandSpawnLoc, EntityType.ARMOR_STAND);
            as.setVisible(false); as.setGravity(false); as.setMarker(true); as.setSmall(true);
            if (as.getEquipment() != null) as.getEquipment().setHelmet(new ItemStack(itemMat));
            as.setCustomName(customNameDisplay);
            as.setCustomNameVisible(true);
            newItem.displayEntity = as;
            activeTreasuresAndPowerUps.put(armorStandSpawnLoc, newItem);
            return;
        }
    }

    private void startVisionCheckTask() {
        if (!visionReductionEnabled || visionEffectType == null || !applyVisionUnderwaterOnly) return;
        if (visionCheckTask != null && !visionCheckTask.isCancelled()) visionCheckTask.cancel();
        visionCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!GameManager.getInstance().isGameRunning() || !getId().equals(GameManager.getInstance().getCurrentGame().getId())) { this.cancel(); return; }
                for (Player p : participants) {
                    if (p.isOnline()) {
                        boolean headInWater = p.getEyeLocation().getBlock().isLiquid();
                        if (headInWater) {
                            if (!p.hasPotionEffect(visionEffectType)) p.addPotionEffect(new PotionEffect(visionEffectType, Integer.MAX_VALUE, visionEffectAmplifier, false, false, false));
                        } else {
                            if (p.hasPotionEffect(visionEffectType)) p.removePotionEffect(visionEffectType);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void startOxygenSystem() {
        if (oxygenTask != null && !oxygenTask.isCancelled()) oxygenTask.cancel();
        oxygenTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!GameManager.getInstance().isGameRunning() || !getId().equals(GameManager.getInstance().getCurrentGame().getId())) { this.cancel(); return; }
                for (UUID playerUUID : new ArrayList<>(oxygenLevels.keySet())) {
                    Player p = Bukkit.getPlayer(playerUUID);
                    if (p == null || !p.isOnline() || !participants.contains(p)) { oxygenLevels.remove(playerUUID); continue; }
                    int oxygen = oxygenLevels.getOrDefault(playerUUID, 100);
                    boolean headInWater = p.getEyeLocation().getBlock().isLiquid();
                    if (headInWater) oxygen = Math.max(0, oxygen - oxygenDepletionAmount);
                    else oxygen = Math.min(100, oxygen + oxygenRecoveryAmount);
                    oxygenLevels.put(playerUUID, oxygen);
                    sendOxygenActionBar(p, oxygen);
                    if (oxygen <= 0 && headInWater) p.damage(2.0);
                }
            }
        }.runTaskTimer(plugin, 0L, oxygenIntervalTicks);
    }

    private void sendOxygenActionBar(Player p, int level) {
        int bars = Math.max(0, (int) (level / 2.5));
        StringBuilder bar = new StringBuilder("§fOxygen: ");
        ChatColor color = ChatColor.GREEN;
        if (level <= 0 && p.getEyeLocation().getBlock().isLiquid()) color = ChatColor.DARK_RED;
        else if (level < 30) color = ChatColor.RED;
        else if (level < 60) color = ChatColor.YELLOW;
        bar.append(color); for (int i = 0; i < bars; i++) bar.append("|");
        bar.append(ChatColor.GRAY); for (int i = bars; i < 40; i++) bar.append("|");
        bar.append(" §7(").append(level).append("%)");
        if (level <= 0 && p.getEyeLocation().getBlock().isLiquid()) bar.append(" §c§l DROWNING!");
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(bar.toString()));
    }

    private void startMobSpawningSystem() {
        if (!hostileMobsEnabled || hostileMobTypesToSpawn.isEmpty()) {
            return;
        }
        if (mobSpawningTask != null && !mobSpawningTask.isCancelled()) {
            mobSpawningTask.cancel();
        }
        spawnedGameMobs.clear();

        mobSpawningTask = new BukkitRunnable() {
            final World world = (treasureSpawnCorner1 != null && treasureSpawnCorner1.getWorld() != null) ?
                    treasureSpawnCorner1.getWorld() :
                    (gameSpawnLocation != null ? gameSpawnLocation.getWorld() : null);
            @Override
            public void run() {
                if (world == null || participants.isEmpty() ||
                        !GameManager.getInstance().isGameRunning() ||
                        (GameManager.getInstance().getCurrentGame() != null && !getId().equals(GameManager.getInstance().getCurrentGame().getId()))) {
                    this.cancel();
                    return;
                }

                spawnedGameMobs.removeIf(mob -> mob == null || mob.isDead());

                if (spawnedGameMobs.size() >= hostileMobMaxActive) {
                    return;
                }

                int mobsToAttempt = 1 + (participants.size() / 4);
                for (int i = 0; i < mobsToAttempt; i++) {
                    if (spawnedGameMobs.size() >= hostileMobMaxActive) break;
                    if (participants.isEmpty()) break;

                    List<Player> participantList = new ArrayList<>(participants);
                    Player randomPlayer = participantList.get(random.nextInt(participantList.size()));
                    Location playerLoc = randomPlayer.getLocation();

                    double rX = playerLoc.getX() + random.nextInt(60) - 30;
                    double rZ = playerLoc.getZ() + random.nextInt(60) - 30;
                    double rY = playerLoc.getY() + random.nextInt(20) - 10;

                    if (treasureSpawnCorner1 != null && treasureSpawnCorner2 != null) {
                        double minY = Math.min(treasureSpawnCorner1.getY(), treasureSpawnCorner2.getY());
                        double maxY = Math.max(treasureSpawnCorner1.getY(), treasureSpawnCorner2.getY());
                        rY = Math.max(minY, Math.min(rY, maxY));
                    } else if (gameSpawnLocation != null) {
                        rY = Math.max(world.getMinHeight() + 5.0, Math.min(rY, gameSpawnLocation.getY() + 10.0));
                    } else {
                        rY = Math.max(world.getMinHeight() + 5.0, Math.min(rY, playerLoc.getY()));
                    }

                    Location loc = new Location(world, rX, rY, rZ);
                    Block blockAtLoc = loc.getBlock();

                    if (world.isChunkLoaded(blockAtLoc.getX() >> 4, blockAtLoc.getZ() >> 4) &&
                            (blockAtLoc.isLiquid() || blockAtLoc.getType().toString().contains("KELP"))) {

                        if (hostileMobTypesToSpawn.isEmpty()) continue;

                        EntityType mobType = hostileMobTypesToSpawn.get(random.nextInt(hostileMobTypesToSpawn.size()));
                        Entity spawned = world.spawnEntity(loc, mobType);
                        if (spawned instanceof LivingEntity) {
                            LivingEntity livingSpawned = (LivingEntity) spawned;
                            livingSpawned.setRemoveWhenFarAway(true);
                            spawnedGameMobs.add(livingSpawned);
                        } else if (spawned != null) {
                            spawned.remove();
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, hostileMobIntervalTicks);
    }

    private class EchoesListener implements Listener {
        @EventHandler
        public void onMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            if (!participants.contains(player) || event.getTo() == null) return;
            Location playerToLocation = event.getTo();
            Iterator<Map.Entry<Location, ActiveDisplayItem>> itemIterator = activeTreasuresAndPowerUps.entrySet().iterator();
            while (itemIterator.hasNext()) {
                Map.Entry<Location, ActiveDisplayItem> entry = itemIterator.next();
                Location itemEntityLocation = entry.getKey();
                ActiveDisplayItem activeItem = entry.getValue();
                double collectionRadiusSqToCheck = activeItem.isTreasure() ? treasureCollectionRadiusSquared : powerUpCollectionRadiusSquared;
                if (player.getWorld().equals(itemEntityLocation.getWorld()) && playerToLocation.distanceSquared(itemEntityLocation) < collectionRadiusSqToCheck) {
                    if (activeItem.displayEntity != null && !activeItem.displayEntity.isDead()) activeItem.displayEntity.remove();
                    itemIterator.remove();
                    if (activeItem.isTreasure()) {
                        TreasureType treasureType = activeItem.getTreasureType();
                        GameManager.getInstance().addPoints(player, treasureType.points);
                        showTreasureCollectedMessage(player, treasureType);
                        spawnOneCollectible(true, true);
                    } else {
                        PowerUpType powerUpType = activeItem.getPowerUpType();
                        applyPowerUpEffect(player, powerUpType);
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(powerUpType.collectionMessage));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                        spawnOneCollectible(true, false);
                    }
                    return;
                }
            }
            Location playerFeetBlockLoc = playerToLocation.getBlock().getLocation();
            for (Location cpBlockLoc : definedCheckpoints) {
                if (cpBlockLoc.equals(playerFeetBlockLoc)) {
                    Location lastCp = playerLastCheckpoint.get(player.getUniqueId());
                    if (lastCp == null || !lastCp.equals(cpBlockLoc)) {
                        playerLastCheckpoint.put(player.getUniqueId(), cpBlockLoc);
                        GameManager.getInstance().addPoints(player, pointsCheckpointBonus);
                        showCheckpointReachedMessage(player, pointsCheckpointBonus);
                        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                        int currentOxy = oxygenLevels.getOrDefault(player.getUniqueId(), 0);
                        oxygenLevels.put(player.getUniqueId(), Math.min(100, currentOxy + 50));
                        sendOxygenActionBar(player, oxygenLevels.get(player.getUniqueId()));
                        return;
                    }
                }
            }
        }

        private void applyPowerUpEffect(Player player, PowerUpType type) {
            if ("OXYGEN_BOOST".equalsIgnoreCase(type.configName)) {
                oxygenLevels.put(player.getUniqueId(), 100);
                sendOxygenActionBar(player, 100);
            } else if (type.potionEffect != null) {
                player.addPotionEffect(new PotionEffect(type.potionEffect, type.effectDurationSeconds * 20, type.effectAmplifier, true, true, true));
            }
        }

        private void showTreasureCollectedMessage(Player p, TreasureType treasure) {
            String pointColor = treasure.points >= 0 ? "§a+" : "§c";
            String pointString = String.valueOf(treasure.points);
            String msg = "§eCollected " + treasure.displayName + "! " + pointColor + pointString + " Points";
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
            if (treasure.points >= 0) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.5f);
            else p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
        }

        private void showCheckpointReachedMessage(Player p, int bonus) {
            p.sendTitle("§bCheckpoint Reached!", "§a+" + bonus + " Points & Oxygen Refill!", 10, 40, 20);
        }

        @EventHandler
        public void onDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player)) return;
            if (!participants.contains(player)) return;
            if (player.getHealth() - event.getFinalDamage() <= 0) {
                event.setCancelled(true);
                player.setHealth(player.getMaxHealth());
                oxygenLevels.put(player.getUniqueId(), 70);
                Location respawnLoc = playerLastCheckpoint.getOrDefault(player.getUniqueId(), gameSpawnLocation);
                player.teleport(respawnLoc);
                GameManager.getInstance().addPoints(player, pointsDrowningRespawnPenalty);
                player.sendMessage("§cYou nearly met a watery grave! Respawned with a penalty.");
                showPointChangeOnActionBar(player, pointsDrowningRespawnPenalty, "§cRespawn Penalty!");
                return;
            }
            if (event.getCause() != EntityDamageEvent.DamageCause.DROWNING && !damagedRecently.contains(player.getUniqueId()) && event.getDamage() > 0) {
                GameManager.getInstance().addPoints(player, pointsDamagePenalty);
                showPointChangeOnActionBar(player, pointsDamagePenalty, "§cDamaged!");
                damagedRecently.add(player.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () -> damagedRecently.remove(player.getUniqueId()), 40L);
            }
        }

        private void showPointChangeOnActionBar(Player p, int amount, String reason) {
            String color = amount >= 0 ? "§a" : "§c";
            String sign = "";
            if (amount > 0 && !reason.toLowerCase().contains("bonus") && !reason.toLowerCase().contains("points")) sign = "+";
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(reason + " " + color + sign + amount + (Math.abs(amount) == 1 ? " Point" : " Points")));
            if (amount < 0) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 0.8f);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            oxygenLevels.remove(player.getUniqueId());
            playerLastCheckpoint.remove(player.getUniqueId());
            damagedRecently.remove(player.getUniqueId());
        }
    }

    private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player p : participants) {
            if (p != null && p.isOnline()) {
                p.sendTitle(ChatColor.translateAlternateColorCodes('&', title),
                        ChatColor.translateAlternateColorCodes('&', subtitle),
                        fadeIn, stay, fadeOut);
            }
        }
    }

    @Override public String getName() { return "Echoes of the Deep"; }
    @Override public String getId() { return "echoesofthedeep"; }
    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.echoesofthedeep.";

        if (rawExplanation.startsWith("§7No explanation found for") || rawExplanation.startsWith("§cError: Explanations unavailable.")) {
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        String formatted = rawExplanation
                .replace("%duration%", String.valueOf(this.duration))
                .replace("%vision_reduction_status%", this.visionReductionEnabled ? "Enabled" : "Disabled")
                .replace("%treasure_collection_radius%", String.valueOf(config.getDouble(basePath + "treasures.collection_radius", 2.0)))
                .replace("%power_up_collection_radius%", String.valueOf(config.getDouble(basePath + "power_ups.collection_radius", 2.5)))
                .replace("%points.damage_penalty%", String.valueOf(this.pointsDamagePenalty))
                .replace("%points.checkpoint_bonus%", String.valueOf(this.pointsCheckpointBonus))
                .replace("%points.drowning_respawn_penalty%", String.valueOf(this.pointsDrowningRespawnPenalty))
                .replace("%oxygen.depletion_amount%", String.valueOf(this.oxygenDepletionAmount))
                .replace("%oxygen.recovery_amount%", String.valueOf(this.oxygenRecoveryAmount));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());    }
}