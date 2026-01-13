package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class TargetTerror extends Game {

    private final List<LivingEntity> activeTargets = new ArrayList<>();
    private Listener gameListener;
    private Location corner1;
    private Location corner2;
    private final Random random = new Random();

    private final List<EntityType> allSpawnableTypes = new ArrayList<>();
    private final Map<EntityType, Integer> mobBasePoints = new EnumMap<>(EntityType.class);

    private int maxSimultaneousTargets = 15;

    private final Map<UUID, Integer> playerHitStreaks = new HashMap<>();
    private int streakLengthForBonus;
    private double streakBonusMultiplierValue;
    private static final String TARGET_BOW_NAME = "§6Target Bow";

    public TargetTerror(JavaPlugin plugin) {
        super(plugin);
        initializeTargetTypesAndSettings();
    }

    @Override
    public void startGame() {
        FileConfiguration config = plugin.getConfig();
        String baseWorldPath = "games.targetterror.spawn_corners.world";
        String c1BasePath = "games.targetterror.spawn_corners.corner1";
        String c2BasePath = "games.targetterror.spawn_corners.corner2";

        World world = Bukkit.getWorld(config.getString(baseWorldPath, "world"));
        if (world == null) {
            plugin.getLogger().severe("[TargetTerror] World '" + config.getString(baseWorldPath) + "' not found!");
            broadcast("§c[TargetTerror] Error: Game world not found.");
            GameManager.getInstance().endCurrentGame();
            return;
        }

        if (!config.contains(c1BasePath + ".x") || !config.contains(c2BasePath + ".x")) {
            plugin.getLogger().severe("[TargetTerror] Spawn corners not fully configured!");
            broadcast("§c[TargetTerror] Error: Spawn area not configured.");
            GameManager.getInstance().endCurrentGame();
            return;
        }
        corner1 = new Location(world, config.getDouble(c1BasePath + ".x"), config.getDouble(c1BasePath + ".y"), config.getDouble(c1BasePath + ".z"));
        corner2 = new Location(world, config.getDouble(c2BasePath + ".x"), config.getDouble(c2BasePath + ".y"), config.getDouble(c2BasePath + ".z"));

        if (allSpawnableTypes.isEmpty()) {
            plugin.getLogger().severe("[TargetTerror] No spawnable mob types configured or loaded correctly!");
            broadcast("§c[TargetTerror] Error: No targets configured for this game.");
            GameManager.getInstance().endCurrentGame();
            return;
        }

        if (this.participants.isEmpty()) {
            broadcast("§c[TargetTerror] No participants joined from the queue. Game cannot start.");
            GameManager.getInstance().endCurrentGame();
            return;
        }

        playerHitStreaks.clear();
        broadcast("§fGame Started! §6Shoot the targets for points!");

        gameListener = new TargetTerrorListener();
        registerListener(gameListener);
        givePlayersBows();
        spawnInitialTargets();
        sendTitleToAll("§6Target Terror", "§aSTARTED!", 10, 70, 20);
    }

    @Override
    public void endGame() {
        broadcast("§fTarget Terror Over!");
        if (gameListener != null) {
            unregisterListener(gameListener);
        }
        for (LivingEntity target : new ArrayList<>(activeTargets)) {
            if (target != null && !target.isDead()) {
                target.remove();
            }
        }
        activeTargets.clear();
        playerHitStreaks.clear();

        for (Player player : participants) {
            if (player != null && player.isOnline()) {
                player.getInventory().clear();
                sendActionBar(player, "");
            }
        }
    }

    @Override
    public void handlePlayerJoinMidGame(Player player) {
        giveBowToPlayer(player);
        sendActionBar(player, "§aWelcome back! Start shooting targets!");
    }

    private void initializeTargetTypesAndSettings() {
        FileConfiguration config = plugin.getConfig();
        this.duration = config.getInt("games.targetterror.duration", 120);
        setDuration(this.duration);

        this.maxSimultaneousTargets = config.getInt("games.targetterror.max_simultaneous_targets", 15);

        this.streakLengthForBonus = config.getInt("games.targetterror.streak_bonus.length", 5);
        this.streakBonusMultiplierValue = config.getDouble("games.targetterror.streak_bonus.multiplier", 1.5);

        allSpawnableTypes.clear();
        mobBasePoints.clear();

        ConfigurationSection pointsAndMobsSection = config.getConfigurationSection("games.targetterror.points_and_mobs");
        if (pointsAndMobsSection == null) {
            plugin.getLogger().severe("[TargetTerror] 'games.targetterror.points_and_mobs' section is missing in config.yml!");
            mobBasePoints.put(EntityType.CHICKEN, 10);
            allSpawnableTypes.add(EntityType.CHICKEN);
            plugin.getLogger().info("[TargetTerror] Using CHICKEN as a fallback target type.");
            return;
        }

        for (String categoryKey : pointsAndMobsSection.getKeys(false)) {
            ConfigurationSection categorySection = pointsAndMobsSection.getConfigurationSection(categoryKey);
            if (categorySection == null) continue;
            int points = categorySection.getInt("points");
            List<String> mobNameList = categorySection.getStringList("mobs");
            if (mobNameList.isEmpty()) {
                plugin.getLogger().warning("[TargetTerror] Empty mob list for category '" + categoryKey + "'. Skipping.");
                continue;
            }
            for (String mobName : mobNameList) {
                try {
                    EntityType type = EntityType.valueOf(mobName.toUpperCase());
                    if (!mobBasePoints.containsKey(type)) {
                        mobBasePoints.put(type, points);
                        allSpawnableTypes.add(type);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("[TargetTerror] Invalid EntityType '" + mobName + "' in config for category '" + categoryKey + "'. Skipping.");
                }
            }
        }
        if (allSpawnableTypes.isEmpty()) {
            plugin.getLogger().severe("[TargetTerror] No valid spawnable mob types loaded! Check 'games.targetterror.points_and_mobs'.");
            mobBasePoints.put(EntityType.PIG, 5);
            allSpawnableTypes.add(EntityType.PIG);
            plugin.getLogger().info("[TargetTerror] Using PIG as a fallback target type.");
        }
    }

    private void spawnInitialTargets() {
        activeTargets.clear();
        for (int i = 0; i < maxSimultaneousTargets; i++) {
            spawnNewTarget();
        }
    }

    private void spawnNewTarget() {
        if (corner1 == null || corner2 == null || corner1.getWorld() == null || allSpawnableTypes.isEmpty()) {
            return;
        }
        World world = corner1.getWorld();
        double minX = Math.min(corner1.getX(), corner2.getX());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        double x = minX + (maxX - minX) * random.nextDouble();
        double y = minY + (maxY - minY) * random.nextDouble();
        double z = minZ + (maxZ - minZ) * random.nextDouble();
        Location loc = new Location(world, x, y, z);

        EntityType selectedType = allSpawnableTypes.get(random.nextInt(allSpawnableTypes.size()));
        Entity spawnedEntity = world.spawnEntity(loc, selectedType);

        if (spawnedEntity instanceof LivingEntity) {
            LivingEntity target = (LivingEntity) spawnedEntity;
            target.setAI(false);
            target.setSilent(true);
            target.setGravity(false);
            target.setInvulnerable(true);
            target.setRemoveWhenFarAway(false);

            if (target instanceof Zombie) {
                target.setCanPickupItems(false);
            } else if (target instanceof Creeper) {
                ((Creeper) target).setPowered(false);
                ((Creeper) target).setMaxFuseTicks(Integer.MAX_VALUE);
                ((Creeper) target).setExplosionRadius(0);
            }
            activeTargets.add(target);
        } else if (spawnedEntity != null) {
            spawnedEntity.remove();
        }
    }

    private void despawnAndReplaceTarget(LivingEntity oldTarget) {
        activeTargets.remove(oldTarget);
        if (oldTarget != null && !oldTarget.isDead()) {
            oldTarget.remove();
        }
        GameManager gm = GameManager.getInstance();
        if (gm.isGameRunning() && gm.getCurrentGame() == this) {
            spawnNewTarget();
        }
    }

    private void sendTitleToAll(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        String finalTitle = ChatColor.translateAlternateColorCodes('&', title);
        String finalSubtitle = ChatColor.translateAlternateColorCodes('&', subtitle);
        for (Player p : participants) {
            if (p != null && p.isOnline()) {
                p.sendTitle(finalTitle, finalSubtitle, fadeIn, stay, fadeOut);
            }
        }
    }

    private void giveBowToPlayer(Player player) {
        if (player == null || !player.isOnline()) return;
        player.getInventory().clear();
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TARGET_BOW_NAME);
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.INFINITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            bow.setItemMeta(meta);
        }
        player.getInventory().setItem(0, bow);
        player.getInventory().setItem(8, new ItemStack(Material.ARROW, 1));
    }

    private void givePlayersBows() {
        for (Player player : participants) {
            giveBowToPlayer(player);
        }
    }

    private void sendActionBar(Player player, String message) {
        if (player == null || !player.isOnline()) return;
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    private boolean isTargetBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && TARGET_BOW_NAME.equals(meta.getDisplayName());
    }


    @Override public int getDuration() { return this.duration; }
    @Override public String getName() { return "Target Terror"; }
    @Override public String getId() { return "targetterror"; }
    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());
        FileConfiguration config = plugin.getConfig();
        String pointsAndMobsConfigPath = "games.targetterror.points_and_mobs.";
        String streakBonusConfigPath = "games.targetterror.streak_bonus.";

        if (rawExplanation.startsWith("§7No explanation found for") || rawExplanation.startsWith("§cError: Explanations unavailable.")) {
            plugin.getLogger().warning("Explanation string not found for: " + getId() + ". Using default/error message.");
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        String formatted = rawExplanation
                .replace("%duration%", String.valueOf(this.duration))
                .replace("%max_simultaneous_targets%", String.valueOf(this.maxSimultaneousTargets))
                .replace("%points_and_mobs.small.points%", String.valueOf(config.getInt(pointsAndMobsConfigPath + "small.points", 10)))
                .replace("%points_and_mobs.medium.points%", String.valueOf(config.getInt(pointsAndMobsConfigPath + "medium.points", 5)))
                .replace("%points_and_mobs.large.points%", String.valueOf(config.getInt(pointsAndMobsConfigPath + "large.points", 2)))
                .replace("%points_and_mobs.penalty_large.points%", String.valueOf(config.getInt(pointsAndMobsConfigPath + "penalty_large.points", -25)))
                .replace("%streak_bonus.length%", String.valueOf(this.streakLengthForBonus))
                .replace("%streak_bonus.multiplier%", String.format("%.1f", this.streakBonusMultiplierValue));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());
    }

    private class TargetTerrorListener implements Listener {

        @EventHandler
        public void onProjectileHit(ProjectileHitEvent event) {
            if (!(event.getEntity() instanceof Arrow)) return;
            Arrow arrow = (Arrow) event.getEntity();
            if (!(arrow.getShooter() instanceof Player)) return;

            Player player = (Player) arrow.getShooter();
            if (!participants.contains(player)) return;

            Entity hitEntity = event.getHitEntity();

            arrow.remove();

            if (hitEntity instanceof LivingEntity && activeTargets.contains(hitEntity)) {
                LivingEntity target = (LivingEntity) hitEntity;

                int currentStreak = playerHitStreaks.getOrDefault(player.getUniqueId(), 0) + 1;
                playerHitStreaks.put(player.getUniqueId(), currentStreak);

                int basePoints = mobBasePoints.getOrDefault(target.getType(), 0);
                int pointsToAward = basePoints;
                boolean bonusApplied = false;

                if (streakLengthForBonus > 0 && currentStreak > 0 && (currentStreak % streakLengthForBonus == 0)) {
                    pointsToAward = (int) (basePoints * streakBonusMultiplierValue);
                    bonusApplied = true;
                }

                GameManager.getInstance().addPointsForGame(player, getId(), pointsToAward);

                String mobTypeName = target.getType().name().toLowerCase().replace("_", " ");
                mobTypeName = mobTypeName.substring(0, 1).toUpperCase() + mobTypeName.substring(1);

                String actionBarMessage;
                if (pointsToAward < 0) {
                    actionBarMessage = String.format("§c%d points! §f(Hit %s - Streak: %d)", pointsToAward, mobTypeName, currentStreak);
                } else if (bonusApplied) {
                    actionBarMessage = String.format("§a+%d points! §6§lSTREAK BONUS! §e(%d hits)", pointsToAward, bonusApplied ? currentStreak : currentStreak);
                } else {
                    actionBarMessage = String.format("§a+%d points! §f(Hit %s - Streak: %d)", pointsToAward, mobTypeName, currentStreak);
                }
                sendActionBar(player, actionBarMessage);

                if (pointsToAward >= 0) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.2f);
                } else {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
                }

                Location particleLoc = target.getEyeLocation();
                if (target.getHeight() < 0.5) {
                    particleLoc = target.getLocation().add(0, target.getHeight() / 2.0, 0);
                }
                if (particleLoc.getWorld() != null) {
                    particleLoc.getWorld().spawnParticle(pointsToAward >= 0 ? Particle.HAPPY_VILLAGER : Particle.LARGE_SMOKE, particleLoc, 10, 0.3, 0.3, 0.3, 0.05);
                }

                despawnAndReplaceTarget(target);

            } else {
                if (playerHitStreaks.getOrDefault(player.getUniqueId(), 0) > 0) {
                    sendActionBar(player, "§cStreak Reset! (Missed)");
                    player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 1.5f);
                }
                playerHitStreaks.put(player.getUniqueId(), 0);
            }
        }

        @EventHandler
        public void onCreeperPrime(ExplosionPrimeEvent event) {
            if (event.getEntity() instanceof Creeper && activeTargets.contains(event.getEntity())) {
                event.setCancelled(true);
                event.setRadius(0F);
                event.setFire(false);
            }
        }

        @EventHandler
        public void onItemDrop(PlayerDropItemEvent event) {
            if (participants.contains(event.getPlayer()) && isTargetBow(event.getItemDrop().getItemStack())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "You cannot drop the Target Bow during the game!");
            }
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player)) return;
            Player player = (Player) event.getWhoClicked();
            if (!participants.contains(player)) return;

            ItemStack current = event.getCurrentItem();
            ItemStack cursor = event.getCursor();

            if (isTargetBow(current) || isTargetBow(cursor)) {
                if (event.getClickedInventory() != player.getInventory()) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot move the Target Bow!");
                }
                if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && isTargetBow(current)) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot move the Target Bow!");
                }
            }
        }
    }
}