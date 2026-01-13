package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.Particle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ZombieOutbreak extends Game {

    private BukkitTask gameTimerTask;
    private BukkitTask survivalPointsUpdaterTask;
    private BukkitTask powerUpManagerTask;
    private BukkitTask infectionVisualsTask;
    private boolean gameHasEnded = false;
    private Listener gameListener;
    private final Random random = new Random();

    private final Set<UUID> zombies = new HashSet<>();
    private final Set<UUID> humans = new HashSet<>();
    private final Map<UUID, Integer> survivalTicks = new HashMap<>();

    private final Map<UUID, BukkitTask> infectionProgressTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> remainingInfectionTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> visualInfectionCountdownTicks = new ConcurrentHashMap<>();
    private int baseInfectionDurationTicks;
    private int accelerationPerHitTicks;
    private PotionEffect infectingPotionEffect;
    private PotionEffect zombieBasePotionEffect;

    private boolean powerUpsEnabled;
    private int maxActivePowerUps;
    private double powerUpSpawnHeightOffset;
    private int powerUpRespawnDelayTicks;
    private double powerUpPickupRadiusSquared;
    private List<String> spawnerBlockMaterialsConfig;
    private final List<Location> spawnerBlockLocations = new ArrayList<>();
    private final Map<Location, ArmorStand> activePowerUpDisplays = new HashMap<>();
    private final Map<Location, String> activePowerUpTypes = new HashMap<>();
    private final Map<String, Map<String, Object>> powerUpTypeSettings = new HashMap<>();
    private final Map<Location, Long> powerUpCooldowns = new HashMap<>();
    private final List<String> powerUpWeightedKeys = new ArrayList<>();

    private int minPlayers;
    private int initialZombieCount;
    private boolean initialZombieAlphaBuff;
    private Location arenaCorner1;
    private Location arenaCorner2;
    private Location spectatorSpawnLocation;
    private Map<String, Object> zombieSettings;
    private Map<String, Object> initialZombieSettings;
    private List<String> humanStartingKitStrings;
    private List<String> humanPotionEffectsOnStartStrings;
    private int pointsZombieInfection;
    private int pointsHumanSurvivalPer10Sec;
    private int pointsLastHumanStandingBonus;
    private int pointsHumanTimeOutSurvivalBonus;
    private int pointsZombieTeamWinBonus;

    public ZombieOutbreak(JavaPlugin plugin) {
        super(plugin);
    }

    public void reloadGameConfig() {
        if (plugin == null) {
            Bukkit.getLogger().severe("[ZombieOutbreak] Plugin instance is null in reloadGameConfig! Cannot reload.");
            return;
        }
        plugin.reloadConfig();
        loadConfigSettings();
        plugin.getLogger().info("[ZombieOutbreak] Configuration reloaded.");
    }

    private void loadConfigSettings() {
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.zombieoutbreak.";
        String pointsPath = basePath + "points.";
        String infectionPath = basePath + "infection_mechanics.";
        String powerUpBasePath = basePath + "powerups.";

        super.setDuration(config.getInt(basePath + "duration", 300));

        this.minPlayers = config.getInt(basePath + "min_players", 2);
        this.initialZombieCount = config.getInt(basePath + "initial_zombie_count", 1);
        this.initialZombieAlphaBuff = config.getBoolean(basePath + "initial_zombie_alpha_buff", true);

        this.pointsZombieInfection = config.getInt(pointsPath + "zombie_infection_bonus", 15);
        this.pointsHumanSurvivalPer10Sec = config.getInt(pointsPath + "human_survival_per_10_seconds", 1);
        this.pointsLastHumanStandingBonus = config.getInt(pointsPath + "last_human_standing_bonus", 50);
        this.pointsHumanTimeOutSurvivalBonus = config.getInt(pointsPath + "human_time_out_survival_bonus", 25);
        this.pointsZombieTeamWinBonus = config.getInt(pointsPath + "zombie_team_win_bonus", 20);

        this.baseInfectionDurationTicks = config.getInt(infectionPath + "base_infection_duration_seconds", 20) * 20;
        this.accelerationPerHitTicks = config.getInt(infectionPath + "acceleration_per_hit_seconds", 5) * 20;
        this.infectingPotionEffect = parsePotionEffectFromString(config.getString(infectionPath + "infecting_potion_effect", "SLOW,0"), -1);
        this.zombieBasePotionEffect = parsePotionEffectFromString(config.getString(infectionPath + "zombie_base_potion_effect", "NIGHT_VISION,0"), -1);

        ConfigurationSection zombieSettingsSection = config.getConfigurationSection(basePath + "zombie_settings");
        this.zombieSettings = (zombieSettingsSection != null) ? zombieSettingsSection.getValues(true) : new HashMap<>();

        ConfigurationSection initialZombieSettingsSection = config.getConfigurationSection(basePath + "initial_zombie_settings");
        this.initialZombieSettings = (initialZombieSettingsSection != null) ? initialZombieSettingsSection.getValues(true) : new HashMap<>();

        this.humanStartingKitStrings = config.getStringList(basePath + "human_settings.starting_kit");
        this.humanPotionEffectsOnStartStrings = config.getStringList(basePath + "human_settings.potion_effects_on_start");

        powerUpsEnabled = config.getBoolean(powerUpBasePath + "enabled", true);
        maxActivePowerUps = config.getInt(powerUpBasePath + "max_active_simultaneously", 3);
        powerUpSpawnHeightOffset = config.getDouble(powerUpBasePath + "item_spawn_height_offset", 2.5);
        powerUpRespawnDelayTicks = config.getInt(powerUpBasePath + "respawn_delay_seconds", 45) * 20;
        powerUpPickupRadiusSquared = config.getDouble(powerUpBasePath + "pickup_radius_squared", 4.0);
        spawnerBlockMaterialsConfig = config.getStringList(powerUpBasePath + "spawner_block_materials");

        powerUpTypeSettings.clear();
        powerUpWeightedKeys.clear();
        ConfigurationSection powerUpTypesSection = config.getConfigurationSection(powerUpBasePath + "types");
        if (powerUpTypesSection != null) {
            for (String key : powerUpTypesSection.getKeys(false)) {
                if (config.getBoolean(powerUpBasePath + "types." + key + ".enabled", true)) {
                    ConfigurationSection specificTypeSection = powerUpTypesSection.getConfigurationSection(key);
                    if (specificTypeSection != null) {
                        powerUpTypeSettings.put(key, specificTypeSection.getValues(true));
                        int weight = config.getInt(powerUpBasePath + "types." + key + ".spawn_weight", 10);
                        for (int i = 0; i < weight; i++) {
                            powerUpWeightedKeys.add(key);
                        }
                    }
                }
            }
        }

        String worldNameArena = config.getString(basePath + "arena.world");
        World gameWorld = (worldNameArena != null && !worldNameArena.isEmpty()) ? Bukkit.getWorld(worldNameArena) : null;
        if (gameWorld == null && worldNameArena != null && !worldNameArena.isEmpty()) {
            plugin.getLogger().warning("[ZombieOutbreak] Arena world '" + worldNameArena + "' not found!");
        }
        if (gameWorld == null) {
            gameWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }

        arenaCorner1 = loadLocation(config, basePath + "arena.corner1", gameWorld, (gameWorld != null ? new Location(gameWorld, 0, 60, 0) : null));
        arenaCorner2 = loadLocation(config, basePath + "arena.corner2", gameWorld, (gameWorld != null ? new Location(gameWorld, 10, 70, 10) : null));
        spectatorSpawnLocation = loadLocation(config, basePath + "teleport", gameWorld, (gameWorld != null ? gameWorld.getSpawnLocation().add(0, 20, 0) : null));
    }

    private Location loadLocation(FileConfiguration config, String path, World defaultWorld, Location fallback) {
        String worldName = config.getString(path + ".world");
        World world = null;

        if (worldName != null && !worldName.isEmpty()) {
            world = Bukkit.getWorld(worldName);
        }

        if (world == null) {
            world = defaultWorld;
        }

        if (world == null) {
            return fallback;
        }

        if (!config.isConfigurationSection(path) || !config.contains(path + ".x") || !config.contains(path + ".y") || !config.contains(path + ".z")) {
            return fallback;
        }

        return new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw", 0),
                (float) config.getDouble(path + ".pitch", 0)
        );
    }

    @Override
    public void startGame() {
        reloadGameConfig();
        gameHasEnded = false;

        if (arenaCorner1 == null || arenaCorner2 == null || arenaCorner1.getWorld() == null || !arenaCorner1.getWorld().equals(arenaCorner2.getWorld())) {
            broadcast("§c[ZombieOutbreak] Arena corners are not properly configured! Cannot start game.");
            GameManager.getInstance().endCurrentGame();
            return;
        }
        if (super.getParticipants().isEmpty()) {
            broadcast("§c[ZombieOutbreak] No players to start Zombie Outbreak!");
            GameManager.getInstance().endCurrentGame();
            return;
        }
        if (super.getParticipants().size() < minPlayers) {
            broadcast("§cNot enough players (" + super.getParticipants().size() + "/" + minPlayers + ") to start Zombie Outbreak!");
            GameManager.getInstance().endCurrentGame();
            return;
        }

        broadcast("§fZombie Outbreak has begun! §aSurvive or §cinfect!");
        sendTitleToAll("§cZombie Outbreak", "§eRUN!", 10, 70, 20);

        zombies.clear();
        humans.clear();
        survivalTicks.clear();
        infectionProgressTasks.values().forEach(BukkitTask::cancel);
        infectionProgressTasks.clear();
        remainingInfectionTicks.clear();
        visualInfectionCountdownTicks.clear();
        activePowerUpDisplays.values().forEach(ArmorStand::remove);
        activePowerUpDisplays.clear();
        activePowerUpTypes.clear();
        powerUpCooldowns.clear();
        spawnerBlockLocations.clear();

        List<Player> playersToStart = new ArrayList<>(super.getParticipants());
        Collections.shuffle(playersToStart);

        List<Location> availableSpawns = getValidRandomSpawns(arenaCorner1.getWorld(), playersToStart.size());
        if (availableSpawns.size() < playersToStart.size()) {
            broadcast("§cCould not find enough valid spawn points! (" + availableSpawns.size() + "/" + playersToStart.size() + ")");
            endGame(false);
            return;
        }

        int zombiesToMake = Math.min(initialZombieCount, playersToStart.size());
        if (zombiesToMake == 0 && !playersToStart.isEmpty()) zombiesToMake = 1;

        for (int i = 0; i < playersToStart.size(); i++) {
            Player player = playersToStart.get(i);
            resetPlayerState(player, true);
            player.teleport(availableSpawns.get(i));
            if (i < zombiesToMake) {
                tagAsZombie(player, false, true);
            } else {
                tagAsHuman(player);
            }
        }

        if (zombies.isEmpty() && !humans.isEmpty() && !playersToStart.isEmpty()) {
            Player unluckyHuman = Bukkit.getPlayer(humans.iterator().next());
            if (unluckyHuman != null) {
                tagAsZombie(unluckyHuman, false, true);
            }
        }

        if (humans.isEmpty() && !zombies.isEmpty()) {
            broadcast("§cEveryone started as a zombie! The infection wins instantly!");
            endGame(false);
            return;
        }

        checkZombieWinCondition();
        if (gameHasEnded) {
            return;
        }

        identifySpawnerLocations();
        gameListener = new ZombieOutbreakListener();
        registerListener(gameListener);
        startSurvivalPointsUpdater();
        startGameTimer();
        if (powerUpsEnabled && !powerUpTypeSettings.isEmpty() && !spawnerBlockLocations.isEmpty()) {
            startPowerUpManagerTask();
        }
        startInfectionVisualsTask();
    }

    @Override
    public void endGame() {
        endGame(false);
    }

    public void endGame(boolean timeUp) {
        if (gameHasEnded) return;
        gameHasEnded = true;

        broadcast("§fZombie Outbreak is over!");
        if (gameListener != null) {
            unregisterListener(gameListener);
            gameListener = null;
        }

        Optional.ofNullable(gameTimerTask).ifPresent(BukkitTask::cancel);
        Optional.ofNullable(survivalPointsUpdaterTask).ifPresent(BukkitTask::cancel);
        Optional.ofNullable(powerUpManagerTask).ifPresent(BukkitTask::cancel);
        Optional.ofNullable(infectionVisualsTask).ifPresent(BukkitTask::cancel);

        infectionProgressTasks.values().forEach(BukkitTask::cancel);
        infectionProgressTasks.clear();
        activePowerUpDisplays.values().forEach(ArmorStand::remove);

        survivalTicks.entrySet().stream()
                .filter(entry -> humans.contains(entry.getKey()))
                .forEach(entry -> {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) {
                        int points = (entry.getValue() / 200) * pointsHumanSurvivalPer10Sec;
                        if (points > 0) {
                            GameManager.getInstance().addPointsForGame(p, getId(), points);
                            p.sendMessage("§a+" + points + " points for surviving " + entry.getValue() / 20 + " seconds!");
                        }
                    }
                });

        if (timeUp) {
            List<Player> onlineHumans = humans.stream()
                    .map(Bukkit::getPlayer)
                    .filter(p -> p != null && p.isOnline())
                    .toList();

            if (onlineHumans.size() == 1) {
                Player lastSurvivor = onlineHumans.get(0);
                broadcast("§6" + lastSurvivor.getName() + " is the Last Human Standing!");
                GameManager.getInstance().addPointsForGame(lastSurvivor, getId(), pointsLastHumanStandingBonus);
            } else if (onlineHumans.size() > 1) {
                broadcast("§aThe humans survived until the end!");
                onlineHumans.forEach(survivor -> GameManager.getInstance().addPointsForGame(survivor, getId(), pointsHumanTimeOutSurvivalBonus));
            } else {
                broadcast("§cNo humans survived! Zombies win!");
            }
        }

        Location fallbackSpawn = (spectatorSpawnLocation != null) ? spectatorSpawnLocation
                : (arenaCorner1 != null && arenaCorner1.getWorld() != null ? arenaCorner1.getWorld().getSpawnLocation() : null);

        new ArrayList<>(super.getParticipants()).forEach(player -> {
            if (player != null && player.isOnline()) {
                resetPlayerState(player, false);
                if (fallbackSpawn != null) {
                    player.teleport(fallbackSpawn);
                }
            }
        });

        zombies.clear();
        humans.clear();
        survivalTicks.clear();
        remainingInfectionTicks.clear();
        visualInfectionCountdownTicks.clear();
        activePowerUpDisplays.clear();
        activePowerUpTypes.clear();
        powerUpCooldowns.clear();

        if (GameManager.getInstance().isGameRunning() && GameManager.getInstance().getCurrentGame() == this) {
            GameManager.getInstance().endCurrentGame();
        }
    }

    private void checkZombieWinCondition() {
        if (gameHasEnded || !GameManager.getInstance().isGameRunning() || GameManager.getInstance().getCurrentGame() != this) return;

        boolean anyHumansOnline = humans.stream().map(Bukkit::getPlayer).anyMatch(p -> p != null && p.isOnline());

        if (!anyHumansOnline && !zombies.isEmpty() && super.getParticipants().stream().anyMatch(Player::isOnline)) {
            broadcast("§cAll humans have fallen! The ZOMBIES WIN!");
            zombies.stream()
                    .map(Bukkit::getPlayer)
                    .filter(p -> p != null && p.isOnline())
                    .forEach(zombie -> GameManager.getInstance().addPointsForGame(zombie, getId(), pointsZombieTeamWinBonus));
            endGame(false);
        }
    }

    private void resetPlayerState(Player player, boolean gameStarting) {
        if (player == null || !player.isOnline()) return;

        player.setGameMode(GameMode.ADVENTURE);
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(20.0);
            player.setHealth(maxHealthAttr.getBaseValue());
        } else {
            player.setHealth(20.0);
        }
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGlowing(false);
        player.setFallDistance(0f);

        if (!gameStarting) {
            clearInfectionState(player.getUniqueId());
        }
    }

    private void tagAsZombie(Player player, boolean infectedByPlayerGameplay, boolean isInitialSetup) {
        if (player == null || !player.isOnline()) return;
        UUID playerUUID = player.getUniqueId();

        resetPlayerState(player, false);
        clearInfectionState(playerUUID);

        zombies.add(playerUUID);
        humans.remove(playerUUID);
        survivalTicks.remove(playerUUID);

        player.sendTitle("§4INFECTED!", "Spread the infection!", 10, 60, 10);

        applyLoadout(player, zombieSettings, false);
        if (!infectedByPlayerGameplay && isInitialSetup && initialZombieAlphaBuff) {
            applyLoadout(player, initialZombieSettings, true);
        }
        if (zombieBasePotionEffect != null) {
            player.addPotionEffect(zombieBasePotionEffect, true);
        }

        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealthAttribute != null ? maxHealthAttribute.getBaseValue() : 20.0);

        if (infectedByPlayerGameplay) {
            broadcast("§7" + player.getName() + " §cis now a zombie!");
        } else if (isInitialSetup) {
            broadcast("§c" + player.getName() + " is one of the initial zombies!");
        }

        if (!isInitialSetup) {
            checkZombieWinCondition();
        }
    }

    private void tagAsHuman(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID playerUUID = player.getUniqueId();

        resetPlayerState(player, false);
        clearInfectionState(playerUUID);

        humans.add(playerUUID);
        zombies.remove(playerUUID);
        survivalTicks.put(playerUUID, 0);

        player.sendTitle("§aSURVIVOR", "Run and hide!", 10, 40, 10);

        if (humanStartingKitStrings != null) {
            humanStartingKitStrings.stream()
                    .map(s -> parseItemStackFromString(s, false))
                    .filter(Objects::nonNull)
                    .forEach(item -> player.getInventory().addItem(item));
        }
        if (humanPotionEffectsOnStartStrings != null) {
            humanPotionEffectsOnStartStrings.stream()
                    .map(s -> parsePotionEffectFromString(s, 0))
                    .filter(Objects::nonNull)
                    .forEach(player::addPotionEffect);
        }
    }

    private void applyLoadout(Player player, Map<String, Object> loadoutSettings, boolean isAlphaSpecificBuff) {
        if (loadoutSettings == null || player == null || !player.isOnline()) return;

        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (!isAlphaSpecificBuff && maxHealthAttribute != null) {
            maxHealthAttribute.setBaseValue(20.0);
        }

        Map<String, ItemStack> armor = new HashMap<>();
        armor.put("helmet", parseItemStackFromString((String) loadoutSettings.get("helmet"), false));
        armor.put("chestplate", parseItemStackFromString((String) loadoutSettings.get("chestplate"), false));
        armor.put("leggings", parseItemStackFromString((String) loadoutSettings.get("leggings"), false));
        armor.put("boots", parseItemStackFromString((String) loadoutSettings.get("boots"), false));

        armor.forEach((slot, item) -> {
            if (item != null && (loadoutSettings.containsKey(slot) || !isAlphaSpecificBuff)) {
                switch (slot) {
                    case "helmet" -> player.getInventory().setHelmet(item);
                    case "chestplate" -> player.getInventory().setChestplate(item);
                    case "leggings" -> player.getInventory().setLeggings(item);
                    case "boots" -> player.getInventory().setBoots(item);
                }
            }
        });

        ItemStack weapon = parseItemStackFromString((String) loadoutSettings.get("weapon"), false);
        if (weapon != null) player.getInventory().setItemInMainHand(weapon);

        if (isAlphaSpecificBuff && initialZombieSettings != null && initialZombieSettings.containsKey("weapon_enchantments")) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (mainHand != null && mainHand.getType() != Material.AIR && initialZombieSettings.get("weapon_enchantments") instanceof List) {
                ((List<?>) initialZombieSettings.get("weapon_enchantments")).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .forEach(enchantString -> {
                            String[] parts = enchantString.split(",");
                            if (parts.length == 2) {
                                Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(parts[0].toLowerCase()));
                                try {
                                    int level = Integer.parseInt(parts[1]);
                                    if (ench != null) mainHand.addUnsafeEnchantment(ench, level);
                                } catch (NumberFormatException e) {
                                }
                            }
                        });
            }
        }

        if (loadoutSettings.get("potion_effects") instanceof List) {
            ((List<?>) loadoutSettings.get("potion_effects")).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(s -> parsePotionEffectFromString(s, -1))
                    .filter(Objects::nonNull)
                    .forEach(effect -> player.addPotionEffect(effect, true));
        }

        player.setGlowing(Boolean.parseBoolean(String.valueOf(loadoutSettings.getOrDefault("glowing", "false"))));

        if (loadoutSettings.containsKey("max_health_boost") && maxHealthAttribute != null) {
            try {
                double boost = Double.parseDouble(String.valueOf(loadoutSettings.get("max_health_boost")));
                maxHealthAttribute.setBaseValue(maxHealthAttribute.getBaseValue() + boost);
            } catch (NumberFormatException e) {
            }
        }

        if (maxHealthAttribute != null) {
            player.setHealth(maxHealthAttribute.getBaseValue());
        }
    }

    private void clearInfectionState(UUID playerUUID) {
        BukkitTask infectionTask = infectionProgressTasks.remove(playerUUID);
        if (infectionTask != null) infectionTask.cancel();
        remainingInfectionTicks.remove(playerUUID);
        visualInfectionCountdownTicks.remove(playerUUID);

        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null && player.isOnline()) {
            if (infectingPotionEffect != null && player.hasPotionEffect(infectingPotionEffect.getType())) {
                player.removePotionEffect(infectingPotionEffect.getType());
            }
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
        }
    }

    private void startInfectionProcess(Player human, Player infectorZombie) {
        UUID humanUUID = human.getUniqueId();
        if (infectionProgressTasks.containsKey(humanUUID) || zombies.contains(humanUUID)) return;

        remainingInfectionTicks.put(humanUUID, baseInfectionDurationTicks);
        visualInfectionCountdownTicks.put(humanUUID, baseInfectionDurationTicks);

        if (infectingPotionEffect != null) human.addPotionEffect(infectingPotionEffect);
        human.playSound(human.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.7f, 1.2f);
        human.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "YOU ARE INFECTING! " + ChatColor.GOLD + (baseInfectionDurationTicks / 20) + "s remaining!"));

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                Player currentHuman = Bukkit.getPlayer(humanUUID);
                if (currentHuman == null || !currentHuman.isOnline() || !humans.contains(humanUUID)) {
                } else {
                    tagAsZombie(currentHuman, true, false);
                    if (infectorZombie != null && infectorZombie.isOnline()) {
                        GameManager.getInstance().addPointsForGame(infectorZombie, getId(), pointsZombieInfection);
                        infectorZombie.playSound(infectorZombie.getLocation(), Sound.ENTITY_ZOMBIE_INFECT, 1.0f, 1.0f);
                    }
                }
                clearInfectionState(humanUUID);
                infectionProgressTasks.remove(humanUUID);
            }
        }.runTaskLater(plugin, baseInfectionDurationTicks);
        infectionProgressTasks.put(humanUUID, task);
    }

    private void accelerateInfection(Player human, int reductionTicks) {
        UUID humanUUID = human.getUniqueId();
        if (!infectionProgressTasks.containsKey(humanUUID) || !remainingInfectionTicks.containsKey(humanUUID)) return;

        int currentTotalScheduledDelay = remainingInfectionTicks.get(humanUUID);
        int newTotalScheduledDelay = Math.max(20, currentTotalScheduledDelay - reductionTicks);

        remainingInfectionTicks.put(humanUUID, newTotalScheduledDelay);
        visualInfectionCountdownTicks.put(humanUUID, newTotalScheduledDelay);

        BukkitTask oldTask = infectionProgressTasks.remove(humanUUID);
        if (oldTask != null) oldTask.cancel();

        human.playSound(human.getLocation(), Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1f, 0.8f);
        human.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.DARK_RED + "Infection accelerated! Turning in " + ChatColor.GOLD + (newTotalScheduledDelay / 20) + "s!"));

        BukkitTask newTask = new BukkitRunnable() {
            @Override
            public void run() {
                Player currentHuman = Bukkit.getPlayer(humanUUID);
                if (currentHuman == null || !currentHuman.isOnline() || !humans.contains(humanUUID)) {
                } else {
                    tagAsZombie(currentHuman, true, false);
                }
                clearInfectionState(humanUUID);
                infectionProgressTasks.remove(humanUUID);
            }
        }.runTaskLater(plugin, newTotalScheduledDelay);
        infectionProgressTasks.put(humanUUID, newTask);
    }

    private void cureInfection(Player human) {
        clearInfectionState(human.getUniqueId());
        human.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.GREEN + "You have been CURED!"));
        human.playSound(human.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        if (infectingPotionEffect != null) human.removePotionEffect(infectingPotionEffect.getType());
    }

    private void startGameTimer() {
        final int totalDuration = getDuration();
        gameTimerTask = new BukkitRunnable() {
            int timeLeft = totalDuration;

            @Override
            public void run() {
                if (gameHasEnded || GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) {
                    this.cancel();
                    return;
                }
                if (timeLeft <= 0) {
                    endGame(true);
                    this.cancel();
                    return;
                }
                if (timeLeft == totalDuration / 2 || timeLeft == 60 || timeLeft == 30 || (timeLeft <= 10 && timeLeft > 0)) {
                    sendTitleToAll("§6Time left", timeLeft <= 10 ? "§c" + timeLeft + "s..." : "§e" + timeLeft + "s", 0, 25, 5);
                }
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startSurvivalPointsUpdater() {
        survivalPointsUpdaterTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (gameHasEnded || GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) {
                    this.cancel();
                    return;
                }

                new HashSet<>(humans).forEach(uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        int newTime = survivalTicks.compute(uuid, (k, v) -> (v == null ? 0 : v) + 20);
                        if (newTime > 0 && newTime % 200 == 0 && pointsHumanSurvivalPer10Sec > 0) {
                            GameManager.getInstance().addPointsForGame(p, getId(), pointsHumanSurvivalPer10Sec);
                        }
                    } else {
                        handlePlayerLeave(p, uuid);
                    }
                });

                new HashSet<>(visualInfectionCountdownTicks.keySet()).forEach(uuid -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline() && humans.contains(uuid) && visualInfectionCountdownTicks.containsKey(uuid)) {
                        int currentVisualTicks = visualInfectionCountdownTicks.getOrDefault(uuid, 0);

                        if (currentVisualTicks <= 0) {
                        } else if (currentVisualTicks <= 20) {
                            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.DARK_RED + "TURNING..."));
                        } else {
                            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "TURNING IN: " + ChatColor.GOLD + (currentVisualTicks / 20) + "s"));
                        }

                        int newVisualTime = Math.max(0, currentVisualTicks - 20);
                        if (newVisualTime > 0) {
                            visualInfectionCountdownTicks.put(uuid, newVisualTime);
                        } else {
                            visualInfectionCountdownTicks.put(uuid, 0);
                        }

                    } else if (p == null || !p.isOnline()) {
                        handlePlayerLeave(p, uuid);
                    } else if (visualInfectionCountdownTicks.containsKey(uuid)) {
                        clearInfectionState(uuid);
                    }
                });
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startInfectionVisualsTask() {
        infectionVisualsTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (gameHasEnded || GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) {
                    this.cancel();
                    return;
                }
                infectionProgressTasks.keySet().stream()
                        .map(Bukkit::getPlayer)
                        .filter(p -> p != null && p.isOnline() && humans.contains(p.getUniqueId()))
                        .forEach(human -> {
                            human.getWorld().spawnParticle(Particle.CRIT, human.getEyeLocation(), 5, 0.2, 0.2, 0.2, 0.01);
                            if (random.nextInt(10) == 0) {
                                human.playSound(human.getLocation(), Sound.ENTITY_ZOMBIE_HURT, 0.2f, 1.8f);
                            }
                        });
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void identifySpawnerLocations() {
        spawnerBlockLocations.clear();
        FileConfiguration config = plugin.getConfig();
        String basePath = "games.zombieoutbreak.powerups.";
        boolean scanArena = config.getBoolean(basePath + "scan_arena_for_spawner_blocks", true);

        if (scanArena) {
            if (arenaCorner1 == null || arenaCorner2 == null || spawnerBlockMaterialsConfig == null || spawnerBlockMaterialsConfig.isEmpty()) return;
            World world = arenaCorner1.getWorld();
            if (world == null) return;

            int minX = Math.min(arenaCorner1.getBlockX(), arenaCorner2.getBlockX());
            int minY = Math.min(arenaCorner1.getBlockY(), arenaCorner2.getBlockY());
            int minZ = Math.min(arenaCorner1.getBlockZ(), arenaCorner2.getBlockZ());
            int maxX = Math.max(arenaCorner1.getBlockX(), arenaCorner2.getBlockX());
            int maxY = Math.max(arenaCorner1.getBlockY(), arenaCorner2.getBlockY());
            int maxZ = Math.max(arenaCorner1.getBlockZ(), arenaCorner2.getBlockZ());

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (spawnerBlockMaterialsConfig.contains(block.getType().name().toUpperCase())
                                && world.getBlockAt(x, y + 1, z).getType().isAir()
                                && world.getBlockAt(x, y + 2, z).getType().isAir()) {
                            spawnerBlockLocations.add(block.getLocation().add(0.5, 0, 0.5));
                        }
                    }
                }
            }
        } else {
            config.getStringList(basePath + "explicit_spawn_locations").forEach(locStr -> {
                String[] parts = locStr.split(",");
                if (parts.length == 4) {
                    World w = Bukkit.getWorld(parts[0]);
                    if (w != null) {
                        try {
                            spawnerBlockLocations.add(new Location(w, Double.parseDouble(parts[1]) + 0.5, Double.parseDouble(parts[2]), Double.parseDouble(parts[3]) + 0.5));
                        } catch (NumberFormatException e) {
                        }
                    }
                }
            });
        }
        Collections.shuffle(spawnerBlockLocations);
    }

    private void startPowerUpManagerTask() {
        if (powerUpWeightedKeys.isEmpty() || spawnerBlockLocations.isEmpty()) return;

        powerUpManagerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (gameHasEnded || !powerUpsEnabled || GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) {
                    activePowerUpDisplays.values().forEach(ArmorStand::remove);
                    activePowerUpDisplays.clear();
                    activePowerUpTypes.clear();
                    this.cancel();
                    return;
                }
                if (activePowerUpDisplays.size() < maxActivePowerUps) {
                    spawnNewPowerUp();
                }
            }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    private void spawnNewPowerUp() {
        if (spawnerBlockLocations.isEmpty() || powerUpWeightedKeys.isEmpty()) return;

        Optional<Location> potentialLoc = spawnerBlockLocations.stream()
                .filter(loc -> !activePowerUpDisplays.containsKey(loc))
                .filter(loc -> System.currentTimeMillis() >= powerUpCooldowns.getOrDefault(loc, 0L))
                .findAny();

        if (potentialLoc.isEmpty() || potentialLoc.get().getWorld() == null) return;

        Location baseSpawnBlockLocation = potentialLoc.get();
        String powerUpKey = powerUpWeightedKeys.get(random.nextInt(powerUpWeightedKeys.size()));
        Map<String, Object> settings = powerUpTypeSettings.get(powerUpKey);
        if (settings == null) return;

        String materialString = (String) settings.get("item_material");
        String nameString = (String) settings.get("item_name");
        Object loreObj = settings.get("item_lore");
        String loreString = "";
        if (loreObj instanceof List) {
            loreString = ",LORE:" + String.join("|", (List<String>) loreObj);
        } else if (loreObj instanceof String) {
            loreString = ",LORE:" + loreObj;
        }

        ItemStack displayItem = parseItemStackFromString(materialString + (nameString != null ? ",NAME:" + nameString : "") + loreString, true);
        if (displayItem == null) return;

        final Location floatingItemLoc = baseSpawnBlockLocation.clone().add(0, powerUpSpawnHeightOffset, 0);
        final ArmorStand armorStand = (ArmorStand) floatingItemLoc.getWorld().spawnEntity(floatingItemLoc, EntityType.ARMOR_STAND);
        armorStand.setVisible(false);
        armorStand.setGravity(false);
        armorStand.setSmall(true);
        armorStand.setMarker(true);

        if (nameString != null && !nameString.isBlank()) {
            armorStand.setCustomName(ChatColor.translateAlternateColorCodes('&', nameString));
            armorStand.setCustomNameVisible(true);
        }

        if (armorStand.getEquipment() != null) armorStand.getEquipment().setHelmet(displayItem);
        else { armorStand.remove(); return; }
        armorStand.setHeadPose(new EulerAngle(0, Math.toRadians(random.nextInt(360)), 0));

        activePowerUpDisplays.put(baseSpawnBlockLocation, armorStand);
        activePowerUpTypes.put(baseSpawnBlockLocation, powerUpKey);
        powerUpCooldowns.put(baseSpawnBlockLocation, System.currentTimeMillis() + (long) powerUpRespawnDelayTicks * 50L);

        String particleName = (String) settings.get("display_effect_on_stand");
        if (particleName != null && !particleName.isEmpty()) {
            try {
                final Particle particle = Particle.valueOf(particleName.toUpperCase());
                new BukkitRunnable() {
                    int ticks = 0;
                    final int duration = powerUpRespawnDelayTicks > 0 ? powerUpRespawnDelayTicks : 600;

                    @Override
                    public void run() {
                        if (gameHasEnded || ticks++ > duration || !armorStand.isValid() || !activePowerUpDisplays.containsValue(armorStand)) {
                            if (armorStand.isValid() && !activePowerUpDisplays.containsValue(armorStand)) armorStand.remove();
                            this.cancel();
                            return;
                        }
                        if (armorStand.getWorld() != null) {
                            armorStand.getWorld().spawnParticle(particle, armorStand.getEyeLocation(), 5, 0.3, 0.3, 0.3, 0.01);
                        }
                        armorStand.setHeadPose(armorStand.getHeadPose().add(0, Math.toRadians(5), 0));
                    }
                }.runTaskTimer(plugin, 0L, 2L);
            } catch (IllegalArgumentException e) {
            }
        }
    }

    private void handlePowerUpPickup(Player player, Location blockLocationOfSpawner) {
        String powerUpKey = activePowerUpTypes.get(blockLocationOfSpawner);
        ArmorStand armorStand = activePowerUpDisplays.get(blockLocationOfSpawner);

        if (powerUpKey == null || armorStand == null || !armorStand.isValid()) return;

        Map<String, Object> settings = powerUpTypeSettings.get(powerUpKey);
        if (settings == null) return;

        String targetTeamStr = (String) settings.getOrDefault("target_team", "ANY");
        boolean isHuman = humans.contains(player.getUniqueId());
        boolean isZombie = zombies.contains(player.getUniqueId());
        boolean gameParticipant = isHuman || isZombie;

        boolean canPickup = false;
        if (!gameParticipant) {
            canPickup = false;
        } else if ("ANY".equalsIgnoreCase(targetTeamStr)) {
            canPickup = true;
        } else if ("HUMAN".equalsIgnoreCase(targetTeamStr) && isHuman) {
            canPickup = true;
        } else if ("ZOMBIE".equalsIgnoreCase(targetTeamStr) && isZombie) {
            canPickup = true;
        }

        if (!canPickup) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "This power-up is not for your team!"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            return;
        }

        activePowerUpDisplays.remove(blockLocationOfSpawner);
        activePowerUpTypes.remove(blockLocationOfSpawner);
        armorStand.remove();

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(
                ChatColor.translateAlternateColorCodes('&', (String) settings.getOrDefault("item_name", "&aPower-up") + " &fcollected!")
        ));

        if ("cure_human".equals(powerUpKey)) {
            cureInfection(player);
            Optional.ofNullable((String) settings.get("specific_potion_effect_on_pickup"))
                    .map(s -> parsePotionEffectFromString(s, 0))
                    .ifPresent(player::addPotionEffect);
        } else if ("zombie_temporary_speed".equals(powerUpKey)) {
            Optional.ofNullable((String) settings.get("pickup_potion_effect"))
                    .map(s -> parsePotionEffectFromString(s, 0))
                    .ifPresent(player::addPotionEffect);
        } else if ("human_temporary_invisibility".equals(powerUpKey) && isHuman) {
            Optional.ofNullable((String) settings.get("pickup_potion_effect"))
                    .map(s -> parsePotionEffectFromString(s, 0))
                    .ifPresent(player::addPotionEffect);
        }

        powerUpCooldowns.put(blockLocationOfSpawner, System.currentTimeMillis() + (long) powerUpRespawnDelayTicks * 50L);
    }

    private List<Location> getValidRandomSpawns(World world, int count) {
        List<Location> validSpawns = new ArrayList<>();
        if (world == null || arenaCorner1 == null || arenaCorner2 == null || !Objects.equals(arenaCorner1.getWorld(), world) || !Objects.equals(arenaCorner2.getWorld(), world)) {
            return validSpawns;
        }

        int minX = Math.min(arenaCorner1.getBlockX(), arenaCorner2.getBlockX());
        int maxX = Math.max(arenaCorner1.getBlockX(), arenaCorner2.getBlockX());
        int minZ = Math.min(arenaCorner1.getBlockZ(), arenaCorner2.getBlockZ());
        int maxZ = Math.max(arenaCorner1.getBlockZ(), arenaCorner2.getBlockZ());
        int scanMinY = Math.min(arenaCorner1.getBlockY(), arenaCorner2.getBlockY());
        int scanMaxY = Math.max(arenaCorner1.getBlockY(), arenaCorner2.getBlockY());

        int attempts = 0;
        int maxAttemptsTotal = count * 300;
        long startTime = System.currentTimeMillis();

        while (validSpawns.size() < count && attempts < maxAttemptsTotal) {
            attempts++;
            if (maxX - minX < 0 || maxZ - minZ < 0) break;

            int x = (maxX == minX) ? minX : minX + random.nextInt(maxX - minX + 1);
            int z = (maxZ == minZ) ? minZ : minZ + random.nextInt(maxZ - minZ + 1);
            Location potentialSpawn = null;

            for (int y = scanMaxY; y >= scanMinY; y--) {
                Block blockGround = world.getBlockAt(x, y - 1, z);
                Block blockFeet = world.getBlockAt(x, y, z);
                Block blockHead = world.getBlockAt(x, y + 1, z);

                if (blockGround.getType().isSolid() && blockFeet.getType().isAir() && blockHead.getType().isAir()) {
                    potentialSpawn = new Location(world, x + 0.5, y, z + 0.5, random.nextFloat() * 360 - 180, 0);
                    break;
                }
            }

            if (potentialSpawn != null) {
                final Location currentPotentialSpawn = potentialSpawn;
                boolean tooClose = validSpawns.stream()
                        .anyMatch(spawn -> Objects.equals(spawn.getWorld(), currentPotentialSpawn.getWorld())
                                && spawn.distanceSquared(currentPotentialSpawn) < 16);
                if (!tooClose) {
                    validSpawns.add(currentPotentialSpawn);
                }
            }

            if (System.currentTimeMillis() - startTime > 4000) {
                break;
            }
        }
        return validSpawns;
    }

    private ItemStack parseItemStackFromString(String itemString, boolean forDisplayStand) {
        if (itemString == null || itemString.isEmpty()) return null;

        String[] parts = itemString.split(",");
        Material material = Material.matchMaterial(parts[0].toUpperCase());
        if (material == null) {
            return null;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        int amount = 1;

        for (int i = 1; i < parts.length; i++) {
            String[] option = parts[i].split(":", 2);
            if (option.length < 2) continue;
            String key = option[0].trim().toLowerCase();
            String value = ChatColor.translateAlternateColorCodes('&', option[1].trim());

            switch (key) {
                case "amount" -> {
                    if (!forDisplayStand) try { amount = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                }
                case "name" -> meta.setDisplayName(value);
                case "unbreakable" -> {
                    if (!forDisplayStand) meta.setUnbreakable(Boolean.parseBoolean(value));
                }
                case "lore" -> meta.setLore(Arrays.stream(value.split("\\|")).collect(Collectors.toList()));
                case "enchant" -> {
                    if (!forDisplayStand) {
                        String[] enchDetails = value.split("_");
                        if (enchDetails.length >= 2) {
                            Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(String.join("_", Arrays.copyOfRange(enchDetails, 0, enchDetails.length - 1)).toLowerCase()));
                            try {
                                int level = Integer.parseInt(enchDetails[enchDetails.length - 1]);
                                if (ench != null) meta.addEnchant(ench, level, true);
                            } catch (NumberFormatException e) {
                            }
                        }
                    }
                }
                case "color" -> {
                    if (meta instanceof LeatherArmorMeta lMeta) {
                        String[] rgb = value.split("_");
                        if (rgb.length == 3) {
                            try {
                                lMeta.setColor(Color.fromRGB(Integer.parseInt(rgb[0]), Integer.parseInt(rgb[1]), Integer.parseInt(rgb[2])));
                            } catch (NumberFormatException e) {
                            }
                        }
                    }
                }
            }
        }
        item.setAmount(Math.max(1, amount));
        item.setItemMeta(meta);
        return item;
    }

    private PotionEffect parsePotionEffectFromString(String effectString, int defaultDurationSeconds) {
        if (effectString == null || effectString.isEmpty()) return null;

        String[] parts = effectString.split(",");
        if (parts.length < 2) return null;

        PotionEffectType type = PotionEffectType.getByName(parts[0].trim().toUpperCase());
        if (type == null) return null;

        try {
            int amplifier = Integer.parseInt(parts[1].trim());
            int durationSeconds = (parts.length > 2) ? Integer.parseInt(parts[2].trim()) : defaultDurationSeconds;
            int durationTicks = (durationSeconds == -1) ? Integer.MAX_VALUE : Math.max(20, durationSeconds * 20);
            return new PotionEffect(type, durationTicks, amplifier, false, true, true);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handlePlayerLeave(Player player, UUID playerUUID) {
        boolean wasHuman = humans.remove(playerUUID);
        boolean wasZombie = zombies.remove(playerUUID);
        survivalTicks.remove(playerUUID);
        clearInfectionState(playerUUID);

        String playerName = (player != null) ? player.getName() : Bukkit.getOfflinePlayer(playerUUID).getName();
        playerName = (playerName == null) ? "A player" : playerName;

        if (wasHuman) {
            broadcast("§7" + playerName + " §cdisconnected as a human!");
        } else if (wasZombie) {
            broadcast("§7Zombie " + playerName + " §chas left.");
        }

        if (player != null) {
            super.participants.remove(player);
        } else {
            super.participants.removeIf(p -> p.getUniqueId().equals(playerUUID));
        }

        if (GameManager.getInstance().isGameRunning() && GameManager.getInstance().getCurrentGame() == this) {
            checkZombieWinCondition();
            long onlineCount = super.getParticipants().stream().filter(Player::isOnline).count();
            if (onlineCount < minPlayers && minPlayers > 0 && onlineCount > 0 && !gameHasEnded) {
                broadcast("§cNot enough players left.");
                endGame(false);
            } else if (zombies.isEmpty() && !humans.isEmpty() && onlineCount > 0 && !gameHasEnded) {
                broadcast("§aAll zombies disconnected! Humans win!");
                endGame(false);
            }
        }
    }

    @Override
    public String getName() { return "Zombie Outbreak"; }

    @Override
    public String getId() { return "zombieoutbreak"; }

    @Override
    public void handlePlayerJoinMidGame(Player player) {
        player.sendMessage(ChatColor.YELLOW + "Zombie Outbreak is in progress. Spectating.");
        player.setGameMode(GameMode.SPECTATOR);
        Location target = (spectatorSpawnLocation != null) ? spectatorSpawnLocation
                : (arenaCorner1 != null && arenaCorner1.getWorld() != null ? arenaCorner1.getWorld().getSpawnLocation().add(0, 5, 0) : null);
        if (target != null) {
            player.teleport(target);
        } else {
            player.sendMessage(ChatColor.RED + "Error: Spectator spawn not set.");
        }
    }

    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());

        if (rawExplanation.contains("No explanation found for") || rawExplanation.contains("Error: Explanations unavailable.")) {
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        String formatted = rawExplanation
                .replace("%infection_mechanics.base_infection_duration_seconds%", String.valueOf(this.baseInfectionDurationTicks / 20))
                .replace("%infection_mechanics.acceleration_per_hit_seconds%", String.valueOf(this.accelerationPerHitTicks / 20))
                .replace("%points.human_survival_per_10_seconds%", String.valueOf(this.pointsHumanSurvivalPer10Sec))
                .replace("%points.zombie_infection_bonus%", String.valueOf(this.pointsZombieInfection))
                .replace("%points.human_time_out_survival_bonus%", String.valueOf(this.pointsHumanTimeOutSurvivalBonus))
                .replace("%points.last_human_standing_bonus%", String.valueOf(this.pointsLastHumanStandingBonus))
                .replace("%points.zombie_team_win_bonus%", String.valueOf(this.pointsZombieTeamWinBonus));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());
    }

    @Override public void setDuration(int duration) { super.setDuration(duration); }
    public void setMinPlayers(int minPlayers) { this.minPlayers = minPlayers; }
    public void setInitialZombieCount(int count) { this.initialZombieCount = count; }
    public void setPointsZombieInfection(int points) { this.pointsZombieInfection = points; }
    public void setPointsHumanSurvivalPer10Sec(int points) { this.pointsHumanSurvivalPer10Sec = points; }
    public void setPointsLastHumanStandingBonus(int points) { this.pointsLastHumanStandingBonus = points; }
    public void setPointsHumanTimeOutSurvivalBonus(int points) { this.pointsHumanTimeOutSurvivalBonus = points; }
    public void setPointsZombieTeamWinBonus(int points) { this.pointsZombieTeamWinBonus = points; }

    public Set<UUID> getParticipantUUIDs() {
        return super.getParticipants().stream()
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());
    }

    private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        super.getParticipants().forEach(player -> {
            if (player != null && player.isOnline()) {
                player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
            }
        });
    }

    private class ZombieOutbreakListener implements Listener {
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
            if (gameHasEnded || GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) return;
            if (!(event.getEntity() instanceof Player victim)) return;

            Player damagerPlayer = null;
            if (event.getDamager() instanceof Player dp) {
                damagerPlayer = dp;
            } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player pShooter) {
                damagerPlayer = pShooter;
            }

            if (damagerPlayer == null || !getParticipantUUIDs().contains(victim.getUniqueId()) || !getParticipantUUIDs().contains(damagerPlayer.getUniqueId())) return;

            UUID damagerUUID = damagerPlayer.getUniqueId();
            UUID victimUUID = victim.getUniqueId();

            boolean damagerIsZombie = zombies.contains(damagerUUID);
            boolean victimIsHuman = humans.contains(victimUUID);

            if (damagerIsZombie && victimIsHuman) {
                event.setDamage(0);
                if (infectionProgressTasks.containsKey(victimUUID)) {
                    accelerateInfection(victim, accelerationPerHitTicks);
                } else {
                    startInfectionProcess(victim, damagerPlayer);
                }
            } else if ((damagerIsZombie && zombies.contains(victimUUID)) || (!damagerIsZombie && victimIsHuman)) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onPlayerMove(PlayerMoveEvent event) {
            if (gameHasEnded || !powerUpsEnabled || activePowerUpDisplays.isEmpty() || GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) return;

            Player player = event.getPlayer();
            if (!getParticipantUUIDs().contains(player.getUniqueId()) || player.getGameMode() == GameMode.SPECTATOR) return;

            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ() && random.nextInt(5) != 0) return;

            Location playerEyeLoc = player.getEyeLocation();
            new HashSet<>(activePowerUpDisplays.entrySet()).forEach(entry -> {
                Location baseBlockLoc = entry.getKey();
                ArmorStand stand = entry.getValue();
                if (stand != null && stand.isValid()) {
                    if (stand.getEyeLocation().distanceSquared(playerEyeLoc) < powerUpPickupRadiusSquared) {
                        handlePowerUpPickup(player, baseBlockLoc);
                    }
                } else {
                    activePowerUpDisplays.remove(baseBlockLoc);
                    activePowerUpTypes.remove(baseBlockLoc);
                }
            });
        }

        @EventHandler
        public void onPlayerFallDamage(EntityDamageEvent event) {
            if (event.getEntity() instanceof Player player && event.getCause() == EntityDamageEvent.DamageCause.FALL
                    && GameManager.getInstance().getCurrentGame() == ZombieOutbreak.this
                    && getParticipantUUIDs().contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onFoodLevelChange(FoodLevelChangeEvent event) {
            if (event.getEntity() instanceof Player player
                    && GameManager.getInstance().getCurrentGame() == ZombieOutbreak.this
                    && getParticipantUUIDs().contains(player.getUniqueId())) {
                event.setCancelled(true);
                player.setFoodLevel(20);
                player.setSaturation(5.0f);
            }
        }

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            Player rejoinedPlayer = event.getPlayer();
            if (GameManager.getInstance().getCurrentGame() != ZombieOutbreak.this) return;

            boolean wasActualParticipant = false;
            for (Player p : ZombieOutbreak.this.getParticipants()) {
                if (p.getUniqueId().equals(rejoinedPlayer.getUniqueId())) {
                    wasActualParticipant = true;
                    break;
                }
            }

            if (!wasActualParticipant) {
                if (GameManager.getInstance().isGameRunning() || GameManager.getInstance().isInBuffer()) {
                    handlePlayerJoinMidGame(rejoinedPlayer);
                }
            } else {
                List<Location> spawns = ZombieOutbreak.this.getValidRandomSpawns(arenaCorner1.getWorld(), 1);
                Location rejoinSpawn = spawns.isEmpty() ? spectatorSpawnLocation : spawns.get(0);
                if (rejoinSpawn == null && arenaCorner1 != null && arenaCorner1.getWorld() != null) {
                    rejoinSpawn = arenaCorner1.getWorld().getSpawnLocation();
                }

                if (rejoinSpawn != null) {
                    rejoinedPlayer.teleport(rejoinSpawn);
                }

                UUID uuid = rejoinedPlayer.getUniqueId();
                if (zombies.contains(uuid)) {
                    tagAsZombie(rejoinedPlayer, false, false);
                } else if (humans.contains(uuid)) {
                    tagAsHuman(rejoinedPlayer);
                } else {
                    tagAsHuman(rejoinedPlayer);
                }
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            Player player = event.getPlayer();
            boolean wasParticipant = false;
            for (Player p : ZombieOutbreak.this.getParticipants()) {
                if (p.getUniqueId().equals(player.getUniqueId())) {
                    wasParticipant = true;
                    break;
                }
            }

            if (GameManager.getInstance().getCurrentGame() == ZombieOutbreak.this && wasParticipant) {
                handlePlayerLeave(player, player.getUniqueId());
            }
        }

        @EventHandler
        public void onPlayerDropItem(PlayerDropItemEvent event) {
            if (GameManager.getInstance().getCurrentGame() == ZombieOutbreak.this
                    && getParticipantUUIDs().contains(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
            }
        }

        @EventHandler
        public void onPlayerInteractArmorStand(PlayerInteractAtEntityEvent event) {
            if (GameManager.getInstance().getCurrentGame() == ZombieOutbreak.this
                    && event.getRightClicked() instanceof ArmorStand stand
                    && activePowerUpDisplays.containsValue(stand)) {
                event.setCancelled(true);
            }
        }
    }
}