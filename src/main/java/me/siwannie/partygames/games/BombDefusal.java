package me.siwannie.partygames.games;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

public class BombDefusal extends Game {

    private final Map<UUID, Long> frozenPlayers = new HashMap<>();
    private final Map<UUID, List<Material>> answerPatterns = new HashMap<>();
    private final Map<UUID, List<Integer>> answerSlotsMap = new HashMap<>();
    private final Map<UUID, Long> guiOpenTimestamps = new HashMap<>();
    private final Map<UUID, BukkitTask> freezeTasks = new HashMap<>();
    private final Map<UUID, BombState> playerStates = new HashMap<>();
    private final Set<UUID> programmaticInventoryClose = new HashSet<>();
    private final Map<UUID, BukkitTask> memorizeViewTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> inputGuiTasks = new HashMap<>();
    private final Map<UUID, Integer> playerBombRewards = new HashMap<>();
    private final Map<UUID, String> playerBombDifficulties = new HashMap<>();
    private final Map<UUID, Location> playerInteractingBombLocation = new HashMap<>();
    private final Set<UUID> defusedPlayersThisAttempt = new HashSet<>();

    private Listener gameListener;
    private final Random random = new Random();

    private Location regionCorner1;
    private Location regionCorner2;
    private World gameWorld;
    private Material blockToReplaceWithBomb;
    private ItemStack defusalKeyItem;
    private String defusalKeyName;

    private final List<Location> activeBombBlockLocations = new ArrayList<>();
    private final Map<Location, BukkitTask> bombRespawnSchedulerTasks = new HashMap<>();
    private Material postDefusalAttemptMaterial;
    private int bombRespawnDelayTicks;
    private String gameConfigBasePath;

    private int easyPatternLength, mediumPatternLength, hardPatternLength;
    private int easyChance, mediumChance;
    private final List<Material> possiblePatternMaterials = Arrays.asList(
            Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.GREEN_CONCRETE,
            Material.YELLOW_CONCRETE, Material.PURPLE_CONCRETE, Material.CYAN_CONCRETE,
            Material.ORANGE_CONCRETE, Material.PINK_CONCRETE, Material.LIME_CONCRETE
    );

    private static final ItemStack PLACEHOLDER_PANE = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    static {
        ItemMeta meta = PLACEHOLDER_PANE.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RESET + "");
            PLACEHOLDER_PANE.setItemMeta(meta);
        }
    }

    private enum BombState { IDLE, MEMORIZING, DEFUSING }

    public BombDefusal(JavaPlugin plugin) {
        super(plugin);
        this.gameConfigBasePath = "games." + getId() + ".";
        loadConfigSettings();
        createDefusalKeyItem();
    }

    private void loadConfigSettings() {
        FileConfiguration config = plugin.getConfig();
        this.duration = config.getInt(gameConfigBasePath + "duration", 360);
        super.setDuration(this.duration);

        String worldName = config.getString(gameConfigBasePath + "region.world");
        if (worldName != null) this.gameWorld = Bukkit.getWorld(worldName);
        if (this.gameWorld == null) {
            plugin.getLogger().severe("[BombDefusal] Region world not found or not specified for " + getId() + " in config: " + gameConfigBasePath + "region.world");
        }

        if (this.gameWorld != null && config.isConfigurationSection(gameConfigBasePath + "region.corner1") && config.isConfigurationSection(gameConfigBasePath + "region.corner2")) {
            regionCorner1 = new Location(gameWorld, config.getDouble(gameConfigBasePath + "region.corner1.x"), config.getDouble(gameConfigBasePath + "region.corner1.y"), config.getDouble(gameConfigBasePath + "region.corner1.z"));
            regionCorner2 = new Location(gameWorld, config.getDouble(gameConfigBasePath + "region.corner2.x"), config.getDouble(gameConfigBasePath + "region.corner2.y"), config.getDouble(gameConfigBasePath + "region.corner2.z"));
        } else if (this.gameWorld != null) {
            plugin.getLogger().severe("[BombDefusal] Region corners not fully configured for " + getId() + " under " + gameConfigBasePath + "region.corner1 and/or region.corner2");
        }


        String materialName = config.getString(gameConfigBasePath + "bomb_settings.block_to_replace", "REDSTONE_BLOCK").toUpperCase();
        this.blockToReplaceWithBomb = Material.matchMaterial(materialName);
        if (this.blockToReplaceWithBomb == null) {
            this.blockToReplaceWithBomb = Material.REDSTONE_BLOCK;
            plugin.getLogger().warning("[BombDefusal] Invalid block_to_replace material: " + materialName + ". Defaulting to REDSTONE_BLOCK for " + getId());
        }

        String defusedMatName = config.getString(gameConfigBasePath + "bomb_settings.defused_bomb_material", "BEDROCK").toUpperCase();
        this.postDefusalAttemptMaterial = Material.matchMaterial(defusedMatName);
        if (this.postDefusalAttemptMaterial == null) {
            this.postDefusalAttemptMaterial = Material.BEDROCK;
            plugin.getLogger().warning("[BombDefusal] Invalid defused_bomb_material: " + defusedMatName + ". Defaulting to BEDROCK for " + getId());
        }

        this.bombRespawnDelayTicks = config.getInt(gameConfigBasePath + "bomb_settings.bomb_respawn_delay_seconds", 30) * 20;

        this.easyPatternLength = config.getInt(gameConfigBasePath + "pattern_length.easy", 3);
        this.mediumPatternLength = config.getInt(gameConfigBasePath + "pattern_length.medium", 5);
        this.hardPatternLength = config.getInt(gameConfigBasePath + "pattern_length.hard", 7);

        this.easyChance = config.getInt(gameConfigBasePath + "difficulty_chances.easy", 50);
        this.mediumChance = config.getInt(gameConfigBasePath + "difficulty_chances.medium", 35);
    }

    private void createDefusalKeyItem() {
        FileConfiguration config = plugin.getConfig();
        String keySettingsPath = gameConfigBasePath + "bomb_settings.";
        String keyMatName = config.getString(keySettingsPath + "key_material", "TRIPWIRE_HOOK").toUpperCase();
        Material keyMaterial = Material.matchMaterial(keyMatName);
        if (keyMaterial == null) {
            keyMaterial = Material.TRIPWIRE_HOOK;
            plugin.getLogger().warning("[BombDefusal] Invalid key_material: " + keyMatName + ". Defaulting to TRIPWIRE_HOOK for " + getId());
        }
        this.defusalKeyName = ChatColor.translateAlternateColorCodes('&', config.getString(keySettingsPath + "key_name", "&c&lDefusal Key"));
        List<String> lore = new ArrayList<>();
        List<String> configLore = config.getStringList(keySettingsPath + "key_lore");
        if (configLore.isEmpty()) {
            lore.add(ChatColor.GRAY + "Right-click a placed bomb (TNT)");
            lore.add(ChatColor.GRAY + "to attempt defusal.");
            lore.add(ChatColor.DARK_GRAY + "This key is bound to you.");
        } else {
            for (String line : configLore) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
        }
        this.defusalKeyItem = new ItemStack(keyMaterial);
        ItemMeta meta = this.defusalKeyItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(this.defusalKeyName);
            meta.setLore(lore);
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            this.defusalKeyItem.setItemMeta(meta);
        }
    }

    @Override
    public void startGame() {
        loadConfigSettings();
        if (gameWorld == null || regionCorner1 == null || regionCorner2 == null || blockToReplaceWithBomb == null || defusalKeyItem == null) {
            broadcast("§c[BombDefusal] Error: Game not properly configured. Some critical settings are missing. Cannot start for " + getId());
            GameManager.getInstance().endCurrentGame();
            return;
        }
        if (super.getParticipants().isEmpty()) {
            broadcast("§c[BombDefusal] No participants. Game cannot start for " + getId());
            GameManager.getInstance().endCurrentGame();
            return;
        }
        broadcast("§fGame Started! §cFind and right-click TNT with your key to defuse bombs!");
        activeBombBlockLocations.clear();
        playerStates.clear();
        bombRespawnSchedulerTasks.values().forEach(BukkitTask::cancel);
        bombRespawnSchedulerTasks.clear();
        defusedPlayersThisAttempt.clear();
        if (gameListener == null) gameListener = new BombDefusalListener();
        registerListener(gameListener);
        spawnBombsInRegion();
        givePlayersDefusalKey();
        sendTitleToAllGame("§cBomb Defusal", "§eSTARTED!", 10, 70, 20);
    }

    @Override
    public void endGame() {
        broadcast("§fBomb Defusal Over!");
        if (gameListener != null) unregisterListener(gameListener);

        for (UUID uuid : new HashSet<>(memorizeViewTasks.keySet())) {
            BukkitTask task = memorizeViewTasks.remove(uuid);
            if (task != null) try { task.cancel(); } catch (IllegalStateException ignored) {}
        }
        for (UUID uuid : new HashSet<>(inputGuiTasks.keySet())) {
            BukkitTask task = inputGuiTasks.remove(uuid);
            if (task != null) try { task.cancel(); } catch (IllegalStateException ignored) {}
        }
        for (UUID uuid : new HashSet<>(freezeTasks.keySet())) {
            BukkitTask task = freezeTasks.remove(uuid);
            if (task != null) try { task.cancel(); } catch (IllegalStateException ignored) {}
        }
        for (BukkitTask task : bombRespawnSchedulerTasks.values()) {
            if (task != null) try { task.cancel(); } catch (IllegalStateException ignored) {}
        }
        bombRespawnSchedulerTasks.clear();

        revertBombsToOriginalType();
        for (Player player : super.getParticipants()) {
            if (player != null && player.isOnline()) {
                player.getInventory().clear();
                sendActionBar(player, "");
                if (player.getOpenInventory().getTitle().startsWith("§bMemorize: ") || player.getOpenInventory().getTitle().startsWith(ChatColor.RED + "Defuse: ")) {
                    programmaticInventoryClose.add(player.getUniqueId());
                    player.closeInventory();
                }
            }
        }
        playerStates.clear(); playerBombRewards.clear(); playerBombDifficulties.clear();
        answerPatterns.clear(); answerSlotsMap.clear(); guiOpenTimestamps.clear();
        defusedPlayersThisAttempt.clear(); frozenPlayers.clear(); playerInteractingBombLocation.clear();
    }

    private void spawnBombsInRegion() {
        activeBombBlockLocations.clear();
        if (gameWorld == null || regionCorner1 == null || regionCorner2 == null || blockToReplaceWithBomb == null) return;
        int minX = Math.min(regionCorner1.getBlockX(), regionCorner2.getBlockX());
        int maxX = Math.max(regionCorner1.getBlockX(), regionCorner2.getBlockX());
        int minY = Math.min(regionCorner1.getBlockY(), regionCorner2.getBlockY());
        int maxY = Math.max(regionCorner1.getBlockY(), regionCorner2.getBlockY());
        int minZ = Math.min(regionCorner1.getBlockZ(), regionCorner2.getBlockZ());
        int maxZ = Math.max(regionCorner1.getBlockZ(), regionCorner2.getBlockZ());
        int bombsPlaced = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block currentBlock = gameWorld.getBlockAt(x, y, z);
                    if (currentBlock.getType() == blockToReplaceWithBomb) {
                        currentBlock.setType(Material.TNT);
                        activeBombBlockLocations.add(currentBlock.getLocation());
                        bombsPlaced++;
                    }
                }
            }
        }
        if (bombsPlaced == 0) {
            broadcast("§e[BombDefusal] Warning: No " + blockToReplaceWithBomb.name() + " blocks found to replace!");
        }
    }

    private void revertBombsToOriginalType() {
        if (gameWorld == null) return;
        if (regionCorner1 != null && regionCorner2 != null) {
            int minX = Math.min(regionCorner1.getBlockX(), regionCorner2.getBlockX());
            int maxX = Math.max(regionCorner1.getBlockX(), regionCorner2.getBlockX());
            int minY = Math.min(regionCorner1.getBlockY(), regionCorner2.getBlockY());
            int maxY = Math.max(regionCorner1.getBlockY(), regionCorner2.getBlockY());
            int minZ = Math.min(regionCorner1.getBlockZ(), regionCorner2.getBlockZ());
            int maxZ = Math.max(regionCorner1.getBlockZ(), regionCorner2.getBlockZ());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block currentBlock = gameWorld.getBlockAt(x, y, z);
                        if (currentBlock.getType() == Material.TNT || currentBlock.getType() == postDefusalAttemptMaterial) {
                            currentBlock.setType(blockToReplaceWithBomb);
                        }
                    }
                }
            }
        }
        activeBombBlockLocations.clear();
    }

    private void handleBombInteractionOutcome(Location bombLocation) {
        if (gameWorld == null || bombLocation == null) return;
        Location normalizedBombLoc = bombLocation.getBlock().getLocation();
        activeBombBlockLocations.remove(normalizedBombLoc);
        Block block = gameWorld.getBlockAt(normalizedBombLoc);
        if (block.getType() == Material.TNT) {
            block.setType(postDefusalAttemptMaterial);
            BukkitTask existingRespawnTask = bombRespawnSchedulerTasks.remove(normalizedBombLoc);
            if(existingRespawnTask != null) existingRespawnTask.cancel();

            if (bombRespawnDelayTicks > 0) {
                BukkitTask respawnTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        GameManager gm = GameManager.getInstance();
                        if (gm.isGameRunning() && gm.getCurrentGame() == BombDefusal.this) {
                            Block currentBlockState = gameWorld.getBlockAt(normalizedBombLoc);
                            if (currentBlockState.getType() == postDefusalAttemptMaterial) {
                                currentBlockState.setType(Material.TNT);
                                activeBombBlockLocations.add(normalizedBombLoc);
                            }
                        }
                        bombRespawnSchedulerTasks.remove(normalizedBombLoc);
                    }
                }.runTaskLater(plugin, this.bombRespawnDelayTicks);
                bombRespawnSchedulerTasks.put(normalizedBombLoc, respawnTask);
            }
        }
    }

    private void givePlayersDefusalKey() {
        for (Player player : super.getParticipants()) {
            if (player == null || !player.isOnline()) continue;
            player.getInventory().clear();
            player.getInventory().setItem(0, defusalKeyItem.clone());
        }
    }

    @Override
    public void handlePlayerJoinMidGame(Player player) {
        givePlayersDefusalKey();
        playerStates.put(player.getUniqueId(), BombState.IDLE);
        sendActionBar(player, "§aWelcome back! Find a bomb to defuse.");
    }

    private void triggerDefusal(Player player, Location bombLocation) {
        UUID uuid = player.getUniqueId();
        if (playerStates.getOrDefault(uuid, BombState.IDLE) != BombState.IDLE) {
            sendActionBar(player, "§cYou are already defusing or recovering!");
            return;
        }
        if (frozenPlayers.containsKey(uuid)) {
            sendActionBar(player, "§cYou are recovering from a failed attempt!");
            return;
        }
        playerStates.put(uuid, BombState.MEMORIZING);
        playerInteractingBombLocation.put(uuid, bombLocation.getBlock().getLocation());
        FileConfiguration config = plugin.getConfig();

        int chance = random.nextInt(100);
        String difficultyKey; String difficultyDisplay; int reward;
        int patternCount; int viewTimeTicks; int answerTimeTicks;

        if (chance < easyChance) {
            difficultyKey = "easy"; difficultyDisplay = "§aEasy"; reward = config.getInt(gameConfigBasePath + "points.easy", 5);
            patternCount = this.easyPatternLength;
            viewTimeTicks = config.getInt(gameConfigBasePath + "memorize_time.easy", 100);
            answerTimeTicks = config.getInt(gameConfigBasePath + "answer_time.easy", 200);
        } else if (chance < easyChance + mediumChance) {
            difficultyKey = "medium"; difficultyDisplay = "§eMedium"; reward = config.getInt(gameConfigBasePath + "points.medium", 10);
            patternCount = this.mediumPatternLength;
            viewTimeTicks = config.getInt(gameConfigBasePath + "memorize_time.medium", 80);
            answerTimeTicks = config.getInt(gameConfigBasePath + "answer_time.medium", 160);
        } else {
            difficultyKey = "hard"; difficultyDisplay = "§cHard"; reward = config.getInt(gameConfigBasePath + "points.hard", 20);
            patternCount = this.hardPatternLength;
            viewTimeTicks = config.getInt(gameConfigBasePath + "memorize_time.hard", 60);
            answerTimeTicks = config.getInt(gameConfigBasePath + "answer_time.hard", 120);
        }

        playerBombRewards.put(uuid, reward);
        playerBombDifficulties.put(uuid, difficultyDisplay);
        List<Material> pattern = generatePattern(patternCount);
        answerPatterns.put(uuid, pattern);
        openViewGUI(player, pattern, reward, difficultyDisplay, viewTimeTicks, answerTimeTicks);
    }

    private void checkDefusal(Player player, Inventory gui, List<Material> pattern, int reward, String difficulty, boolean calledFromTimer) {
        UUID uuid = player.getUniqueId();

        if (playerStates.get(uuid) != BombState.DEFUSING && !calledFromTimer) {
            if (player.getOpenInventory().getTitle().startsWith(ChatColor.RED + "Defuse: ")) {
                programmaticInventoryClose.add(uuid); player.closeInventory();
            }
            sendActionBar(player, ChatColor.RED + "Defusal error: State mismatch or GUI closed early.");
            cleanup(uuid);
            return;
        }
        if (playerStates.get(uuid) != BombState.DEFUSING) {
            cleanup(uuid);
            return;
        }

        if (this.defusedPlayersThisAttempt.contains(uuid)) {
            if (calledFromTimer && player.getOpenInventory().getTitle().startsWith(ChatColor.RED + "Defuse: ")) {
                if(!programmaticInventoryClose.contains(uuid)) { programmaticInventoryClose.add(uuid); player.closeInventory(); }
            }
            cleanup(uuid);
            return;
        }

        List<Integer> slots = answerSlotsMap.get(uuid);
        boolean correct = true;
        if (pattern == null || slots == null || slots.size() != pattern.size()) {
            correct = false;
        } else {
            for (int i = 0; i < pattern.size(); i++) {
                ItemStack itemInSlot = gui.getItem(slots.get(i));
                if (itemInSlot == null || itemInSlot.getType() != pattern.get(i)) {
                    correct = false;
                    break;
                }
            }
        }

        Location bombLoc = playerInteractingBombLocation.get(uuid);
        World worldForEffects = (bombLoc != null && bombLoc.getWorld() != null) ? bombLoc.getWorld() : player.getWorld();

        if (correct) {
            sendActionBar(player, ChatColor.GREEN + "Bomb defused! +" + reward + " points");
            GameManager.getInstance().addPointsForGame(player, getId(), reward);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            if (worldForEffects != null && bombLoc != null) worldForEffects.playSound(bombLoc, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, 1.5f);
            this.defusedPlayersThisAttempt.add(uuid);
        } else {
            int failPenalty = plugin.getConfig().getInt(gameConfigBasePath + "points.fail_penalty", -5);
            sendActionBar(player, ChatColor.RED + "Defusal failed! " + failPenalty + " points.");
            GameManager.getInstance().addPointsForGame(player, getId(), failPenalty);
            applyFailurePenalty(player);
            if (bombLoc != null && worldForEffects != null) {
                worldForEffects.playSound(bombLoc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                worldForEffects.spawnParticle(Particle.EXPLOSION, bombLoc.clone().add(0.5,0.5,0.5), 1);
            }
        }

        if (bombLoc != null) {
            handleBombInteractionOutcome(bombLoc);
        }

        if (player.getOpenInventory().getTitle().startsWith(ChatColor.RED + "Defuse: ")) {
            if(!programmaticInventoryClose.contains(uuid)) {
                programmaticInventoryClose.add(uuid);
                player.closeInventory();
            }
        }
        cleanup(uuid);
    }

    private void cleanup(UUID uuid) {
        answerPatterns.remove(uuid);
        answerSlotsMap.remove(uuid);
        guiOpenTimestamps.remove(uuid);
        this.defusedPlayersThisAttempt.remove(uuid);
        playerInteractingBombLocation.remove(uuid);

        BukkitTask memorizeTask = memorizeViewTasks.remove(uuid);
        if (memorizeTask != null) try { memorizeTask.cancel(); } catch (IllegalStateException ignored) {}
        BukkitTask inputTask = inputGuiTasks.remove(uuid);
        if (inputTask != null) try { inputTask.cancel(); } catch (IllegalStateException ignored) {}

        playerStates.put(uuid, BombState.IDLE);
        playerBombRewards.remove(uuid);
        playerBombDifficulties.remove(uuid);
    }

    private void sendActionBar(Player player, String message) {
        if (player == null || !player.isOnline()) return;
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    private List<Material> generatePattern(int count) {
        List<Material> result = new ArrayList<>();
        if (possiblePatternMaterials.isEmpty()) return result;
        for (int i = 0; i < count; i++) result.add(possiblePatternMaterials.get(random.nextInt(possiblePatternMaterials.size())));
        return result;
    }

    private void openViewGUI(Player player, List<Material> pattern, int reward, String difficulty, int viewTimeTicks, int answerTimeTicks) {
        String guiTitle = "§bMemorize: " + difficulty + " (" + pattern.size() + " items)";
        Inventory view = Bukkit.createInventory(null, 27, guiTitle);
        int startSlot = 9 + (9 - pattern.size()) / 2;
        for (int i = 0; i < pattern.size(); i++) {
            if (startSlot + i < 18) {
                view.setItem(startSlot + i, new ItemStack(pattern.get(i)));
            } else break;
        }

        playerStates.put(player.getUniqueId(), BombState.MEMORIZING);
        player.openInventory(view);
        final int totalSeconds = Math.max(1, viewTimeTicks / 20);
        UUID playerUUID = player.getUniqueId();

        BukkitTask existingTask = memorizeViewTasks.remove(playerUUID);
        if(existingTask != null) existingTask.cancel();

        BukkitRunnable runnable = new BukkitRunnable() {
            int secondsLeft = totalSeconds;
            @Override public void run() {
                Player currentPlayer = Bukkit.getPlayer(playerUUID);
                if (currentPlayer == null || !currentPlayer.isOnline() || playerStates.get(playerUUID) != BombState.MEMORIZING) {
                    cancel(); memorizeViewTasks.remove(playerUUID);
                    if (currentPlayer != null) sendActionBar(currentPlayer, "");
                    return;
                }
                String currentOpenGUITitle = currentPlayer.getOpenInventory().getTitle();
                if (secondsLeft <= 0) {
                    cancel(); memorizeViewTasks.remove(playerUUID);
                    if (currentPlayer.isOnline() && playerStates.get(playerUUID) == BombState.MEMORIZING && currentOpenGUITitle.equals(guiTitle)) {
                        programmaticInventoryClose.add(playerUUID); currentPlayer.closeInventory();
                        openInputGUI(currentPlayer, pattern, reward, difficulty, answerTimeTicks);
                    } else if (playerStates.get(playerUUID) != BombState.IDLE){
                        if (playerStates.get(playerUUID) == BombState.MEMORIZING) {
                            openInputGUI(currentPlayer, pattern, reward, difficulty, answerTimeTicks);
                        } else {
                            cleanup(playerUUID);
                        }
                    }
                    return;
                }
                if (currentPlayer.isOnline() && playerStates.get(playerUUID) == BombState.MEMORIZING && currentOpenGUITitle.equals(guiTitle)) {
                    sendActionBar(currentPlayer, "§eMemorize Pattern: §b" + secondsLeft + "s");
                } else if (currentPlayer.isOnline() && playerStates.get(playerUUID) == BombState.MEMORIZING && !currentOpenGUITitle.equals(guiTitle)) {
                    cancel(); memorizeViewTasks.remove(playerUUID);
                    sendActionBar(currentPlayer, "§eMemorization cancelled. §cOpening defusal GUI...");
                    openInputGUI(currentPlayer, pattern, reward, difficulty, answerTimeTicks);
                }
                secondsLeft--;
            }
        };
        this.memorizeViewTasks.put(playerUUID, runnable.runTaskTimer(plugin, 0L, 20L));
    }

    private void openInputGUI(Player player, List<Material> pattern, int reward, String difficulty, int answerTimeTicks) {
        String guiTitle = ChatColor.RED + "Defuse: " + difficulty + " (" + pattern.size() + " items)";
        Inventory input = Bukkit.createInventory(null, 27, guiTitle);

        Map<Material, Integer> patternColorCounts = new HashMap<>();
        for (Material mat : pattern) {
            patternColorCounts.put(mat, patternColorCounts.getOrDefault(mat, 0) + 1);
        }
        List<ItemStack> choicesForDisplay = new ArrayList<>();
        List<Material> uniquePatternColors = new ArrayList<>(patternColorCounts.keySet());
        for (Material mat : uniquePatternColors) {
            int countNeeded = patternColorCounts.get(mat);
            for (int i = 0; i < countNeeded; i++) {
                if (choicesForDisplay.size() < 9) {
                    choicesForDisplay.add(new ItemStack(mat));
                }
            }
        }
        int initialSize = choicesForDisplay.size();
        if (initialSize < 9) {
            List<Material> fillerOptions = new ArrayList<>(possiblePatternMaterials);
            fillerOptions.removeAll(uniquePatternColors);
            Collections.shuffle(fillerOptions);
            for (int i = 0; i < Math.min(fillerOptions.size(), 9 - initialSize); i++) {
                choicesForDisplay.add(new ItemStack(fillerOptions.get(i)));
            }
        }
        while (choicesForDisplay.size() < 9 && !possiblePatternMaterials.isEmpty()){
            Material randomMat = possiblePatternMaterials.get(random.nextInt(possiblePatternMaterials.size()));
            if(choicesForDisplay.stream().noneMatch(item -> item.getType() == randomMat)) {
                choicesForDisplay.add(new ItemStack(randomMat));
            } else if (choicesForDisplay.size() < 5) {
                choicesForDisplay.add(new ItemStack(randomMat));
            }
        }
        Collections.shuffle(choicesForDisplay);
        for (int i = 0; i < Math.min(9, choicesForDisplay.size()); i++) {
            input.setItem(i, choicesForDisplay.get(i));
        }

        List<Integer> answerSlots = new ArrayList<>();
        int startSlotForRow2 = 9 + (9 - pattern.size()) / 2;
        for (int i = 0; i < pattern.size(); i++) {
            if (startSlotForRow2 + i < 18) {
                input.setItem(startSlotForRow2 + i, PLACEHOLDER_PANE.clone());
                answerSlots.add(startSlotForRow2 + i);
            } else break;
        }
        answerSlotsMap.put(player.getUniqueId(), answerSlots);
        UUID playerUUID = player.getUniqueId();
        guiOpenTimestamps.put(playerUUID, System.currentTimeMillis());
        playerStates.put(playerUUID, BombState.DEFUSING);
        player.openInventory(input);
        final int totalSeconds = Math.max(1, answerTimeTicks / 20);

        BukkitTask existingTask = inputGuiTasks.remove(playerUUID);
        if (existingTask != null) existingTask.cancel();

        BukkitRunnable runnable = new BukkitRunnable() {
            int secondsLeft = totalSeconds;
            @Override public void run() {
                Player currentPlayer = Bukkit.getPlayer(playerUUID);
                if (currentPlayer == null || !currentPlayer.isOnline() || playerStates.get(playerUUID) != BombState.DEFUSING) { cancel(); inputGuiTasks.remove(playerUUID); if (currentPlayer != null) sendActionBar(currentPlayer, ""); return; }
                String currentOpenGUITitle = currentPlayer.getOpenInventory().getTitle();

                if (secondsLeft <= 0) {
                    cancel(); inputGuiTasks.remove(playerUUID);
                    if (currentPlayer.isOnline() && playerStates.get(playerUUID) == BombState.DEFUSING && currentOpenGUITitle.equals(guiTitle)) {
                        sendActionBar(currentPlayer, ChatColor.RED + "Time's up! Checking bomb...");
                        List<Material> currentPattern = answerPatterns.get(playerUUID); Integer currentReward = playerBombRewards.get(playerUUID); String currentDiff = playerBombDifficulties.get(playerUUID);
                        if (currentPattern != null && currentReward != null && currentDiff != null) {
                            checkDefusal(currentPlayer, currentPlayer.getOpenInventory().getTopInventory(), currentPattern, currentReward, currentDiff, true);
                        } else {
                            if(!programmaticInventoryClose.contains(playerUUID)){ programmaticInventoryClose.add(playerUUID); currentPlayer.closeInventory(); }
                            sendActionBar(currentPlayer, ChatColor.RED + "Bomb defusal timed out due to error!");
                            GameManager.getInstance().addPointsForGame(currentPlayer, getId(), plugin.getConfig().getInt(gameConfigBasePath + "points.fail_penalty", -5));
                            applyFailurePenalty(currentPlayer);
                            Location bombLoc = playerInteractingBombLocation.get(playerUUID);
                            if (bombLoc != null) handleBombInteractionOutcome(bombLoc);
                            cleanup(playerUUID);
                        }
                    } else if (playerStates.get(playerUUID) != BombState.IDLE){
                        cleanup(playerUUID);
                    }
                    return;
                }
                if (currentPlayer.isOnline() && playerStates.get(playerUUID) == BombState.DEFUSING && currentOpenGUITitle.equals(guiTitle)) {
                    sendActionBar(currentPlayer, "§cDefuse Bomb: §6" + secondsLeft + "s");
                } else if (currentPlayer.isOnline() && playerStates.get(playerUUID) == BombState.DEFUSING && !currentOpenGUITitle.equals(guiTitle)) {
                    cancel(); inputGuiTasks.remove(playerUUID);
                }
                secondsLeft--;
            }
        };
        this.inputGuiTasks.put(playerUUID, runnable.runTaskTimer(plugin, 0L, 20L));
    }

    private void applyFailurePenalty(Player player) {
        UUID uuid = player.getUniqueId();
        player.setVelocity(new Vector(0, 0.5, 0));
        sendActionBar(player, "§7Recovering from failed defusal...");
        frozenPlayers.put(uuid, System.currentTimeMillis());
        BukkitTask existingFreezeTask = freezeTasks.remove(uuid);
        if (existingFreezeTask != null) try { existingFreezeTask.cancel(); } catch (IllegalStateException ignored) {}

        BukkitRunnable runnable = new BukkitRunnable() {
            @Override public void run() {
                frozenPlayers.remove(uuid);
                freezeTasks.remove(uuid);
                if (player.isOnline()) {
                    sendActionBar(player, "§aRecovery complete! You can try another bomb.");
                }
            }
        };
        this.freezeTasks.put(uuid, runnable.runTaskLater(plugin, 60L));
    }

    private class BombDefusalListener implements Listener {
        @EventHandler(priority = EventPriority.NORMAL)
        public void onPlayerInteract(PlayerInteractEvent event) {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock == null || clickedBlock.getType() != Material.TNT) return;
            Player player = event.getPlayer();
            if (!participants.contains(player)) return;

            ItemStack itemInHand = player.getInventory().getItemInMainHand();
            if (!isDefusalKey(itemInHand)) {
                sendActionBar(player, ChatColor.YELLOW + "You need the Defusal Key!");
                return;
            }

            Location clickedLocation = clickedBlock.getLocation().getBlock().getLocation();
            if (activeBombBlockLocations.contains(clickedLocation)) {
                if (playerStates.getOrDefault(player.getUniqueId(), BombState.IDLE) == BombState.IDLE && !frozenPlayers.containsKey(player.getUniqueId())) {
                    event.setCancelled(true);
                    triggerDefusal(player, clickedLocation);
                } else {
                    sendActionBar(player, "§cYou are busy or recovering!");
                }
            }
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            String title = event.getView().getTitle();
            Inventory topInventory = event.getView().getTopInventory();
            Inventory clickedInventory = event.getClickedInventory();

            if (!participants.contains(player) && !(title.startsWith("§bMemorize: ") || title.startsWith(ChatColor.RED + "Defuse: "))) {
                return;
            }

            if (title.startsWith("§bMemorize: ")) {
                event.setCancelled(true);
            } else if (title.startsWith(ChatColor.RED + "Defuse: ")) {
                if (clickedInventory == null) return;

                UUID playerUUID = player.getUniqueId();
                List<Integer> playerAnswerSlots = answerSlotsMap.get(playerUUID);
                if (playerAnswerSlots == null) { event.setCancelled(true); return; }

                if (clickedInventory.equals(player.getInventory())) {
                    if(isDefusalKey(event.getCurrentItem()) || isDefusalKey(event.getCursor())){
                        event.setCancelled(true);
                    }
                    if (event.isShiftClick() && event.getCurrentItem() != null && event.getCurrentItem().getType().toString().endsWith("_CONCRETE")) {
                        ItemStack sourceItem = event.getCurrentItem();
                        for (int slotIndex : playerAnswerSlots) {
                            ItemStack itemInAnswerSlot = topInventory.getItem(slotIndex);
                            if (itemInAnswerSlot != null && itemInAnswerSlot.isSimilar(PLACEHOLDER_PANE)) {
                                ItemStack toPlace = sourceItem.clone(); toPlace.setAmount(1);
                                topInventory.setItem(slotIndex, toPlace);
                                if (sourceItem.getAmount() > 1) sourceItem.setAmount(sourceItem.getAmount() - 1);
                                else event.setCurrentItem(null);
                                event.setCancelled(true);
                                player.playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 0.7f, 1.5f);
                                return;
                            }
                        }
                        event.setCancelled(true);
                    }
                } else if (clickedInventory.equals(topInventory)) {
                    ItemStack clickedItemInGui = event.getCurrentItem();
                    ItemStack cursorItem = player.getItemOnCursor();
                    int slot = event.getSlot();

                    if (slot < 9) {
                        if (clickedItemInGui != null && clickedItemInGui.getType().toString().endsWith("_CONCRETE")) {
                            event.setCancelled(false);
                        } else {
                            event.setCancelled(true);
                        }
                    } else if (playerAnswerSlots.contains(slot)) {
                        if (cursorItem != null && cursorItem.getType().toString().endsWith("_CONCRETE")) {
                            ItemStack toPlace = cursorItem.clone(); toPlace.setAmount(1);
                            topInventory.setItem(slot, toPlace);
                            player.setItemOnCursor(null);
                            event.setCancelled(true);
                            player.playSound(player.getLocation(), Sound.BLOCK_STONE_PLACE, 0.7f, 1.5f);
                        } else if (clickedItemInGui != null && clickedItemInGui.getType().toString().endsWith("_CONCRETE")) {
                            player.setItemOnCursor(clickedItemInGui);
                            topInventory.setItem(slot, PLACEHOLDER_PANE.clone());
                            event.setCancelled(true);
                            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BREAK, 0.7f, 1.5f);
                        } else if (clickedItemInGui != null && clickedItemInGui.isSimilar(PLACEHOLDER_PANE)) {
                            if (cursorItem != null && cursorItem.getType().toString().endsWith("_CONCRETE")) {
                                event.setCancelled(false);
                            } else {
                                event.setCancelled(true);
                            }
                        } else {
                            event.setCancelled(true);
                        }
                    } else {
                        event.setCancelled(true);
                    }
                }
            }
        }


        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            if (!(event.getPlayer() instanceof Player player)) return;
            UUID uuid = player.getUniqueId();
            String title = event.getView().getTitle();

            if (programmaticInventoryClose.remove(uuid)) return;
            BombState state = playerStates.get(uuid);

            if (state == BombState.MEMORIZING && title.startsWith("§bMemorize: ")) {
                BukkitTask memorizeTask = memorizeViewTasks.remove(uuid);
                if (memorizeTask != null) try { memorizeTask.cancel(); } catch (IllegalStateException ignored) {}

                List<Material> currentPattern = answerPatterns.get(uuid);
                Integer currentReward = playerBombRewards.get(uuid);
                String currentDifficulty = playerBombDifficulties.get(uuid);
                FileConfiguration config = plugin.getConfig();
                int answerTimeTicks = config.getInt(gameConfigBasePath + "answer_time." + getDifficultyKeyFromName(currentDifficulty), 200);

                if (currentPattern != null && currentReward != null && currentDifficulty != null) {
                    sendActionBar(player, "§eMemorization GUI closed. §cOpening defusal GUI...");
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (player.isOnline() && participants.contains(player) && playerStates.get(uuid) == BombState.MEMORIZING) {
                                openInputGUI(player, currentPattern, currentReward, currentDifficulty, answerTimeTicks);
                            } else {
                                cleanup(uuid);
                            }
                        }
                    }.runTaskLater(plugin, 1L);
                } else {
                    sendActionBar(player, "§cError during memorization phase. Bomb attempt cancelled.");
                    Location bombLoc = playerInteractingBombLocation.get(uuid);
                    if (bombLoc != null) handleBombInteractionOutcome(bombLoc);
                    cleanup(uuid);
                }
            } else if (state == BombState.DEFUSING && title.startsWith(ChatColor.RED + "Defuse: ")) {
                BukkitTask defusalTask = inputGuiTasks.remove(uuid);
                if (defusalTask != null) try { defusalTask.cancel(); } catch (IllegalStateException ignored) {}

                List<Material> currentPattern = answerPatterns.get(uuid);
                Integer currentReward = playerBombRewards.get(uuid);
                String currentDifficulty = playerBombDifficulties.get(uuid);

                if (currentPattern != null && currentReward != null && currentDifficulty != null) {
                    sendActionBar(player, "§eDefusal GUI closed. Checking answer...");
                    checkDefusal(player, event.getView().getTopInventory(), currentPattern, currentReward, currentDifficulty, false);
                } else {
                    sendActionBar(player, "§cBomb details missing. Defusal attempt considered failed.");
                    GameManager.getInstance().addPointsForGame(player, getId(), plugin.getConfig().getInt(gameConfigBasePath + "points.fail_penalty", -5));
                    applyFailurePenalty(player);
                    Location bombLoc = playerInteractingBombLocation.get(uuid);
                    if (bombLoc != null) handleBombInteractionOutcome(bombLoc);
                    cleanup(uuid);
                }
            }
        }

        @EventHandler
        public void onItemDrop(PlayerDropItemEvent event){
            if(participants.contains(event.getPlayer()) && isDefusalKey(event.getItemDrop().getItemStack())){
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "You cannot drop the Defusal Key!");
            }
        }
        private boolean isDefusalKey(ItemStack item) {
            if (item == null || item.getType() == Material.AIR) return false;
            if (defusalKeyItem == null || item.getType() != defusalKeyItem.getType() || !item.hasItemMeta()) return false;
            ItemMeta meta = item.getItemMeta();
            return meta != null && defusalKeyName != null && defusalKeyName.equals(meta.getDisplayName());
        }
    }

    @Override public String getName() { return "Bomb Defusal"; }
    @Override public String getId() { return "bombdefusal"; }

    @Override
    public List<String> getFormattedExplanationLines() {
        String rawExplanation = GameManager.getInstance().getGameExplanation(this.getId());
        FileConfiguration config = plugin.getConfig();

        if (rawExplanation.startsWith("§7No explanation found for") || rawExplanation.startsWith("§cError: Explanations unavailable.")) {
            plugin.getLogger().warning("Explanation string not found for: " + getId() + ". Using default/error message.");
            return Arrays.asList(rawExplanation.split("\\|"));
        }

        String formatted = rawExplanation
                .replace("%duration%", String.valueOf(this.duration))
                .replace("%points.easy%", String.valueOf(config.getInt(gameConfigBasePath + "points.easy", 5)))
                .replace("%points.medium%", String.valueOf(config.getInt(gameConfigBasePath + "points.medium", 10)))
                .replace("%points.hard%", String.valueOf(config.getInt(gameConfigBasePath + "points.hard", 20)))
                .replace("%points.fail_penalty%", String.valueOf(config.getInt(gameConfigBasePath + "points.fail_penalty", -5)))
                .replace("%pattern_length.easy%", String.valueOf(this.easyPatternLength))
                .replace("%pattern_length.medium%", String.valueOf(this.mediumPatternLength))
                .replace("%pattern_length.hard%", String.valueOf(this.hardPatternLength))
                .replace("%memorize_time.easy_seconds%", String.valueOf(config.getInt(gameConfigBasePath + "memorize_time.easy", 100) / 20))
                .replace("%memorize_time.medium_seconds%", String.valueOf(config.getInt(gameConfigBasePath + "memorize_time.medium", 80) / 20))
                .replace("%memorize_time.hard_seconds%", String.valueOf(config.getInt(gameConfigBasePath + "memorize_time.hard", 60) / 20))
                .replace("%answer_time.easy_seconds%", String.valueOf(config.getInt(gameConfigBasePath + "answer_time.easy", 200) / 20))
                .replace("%answer_time.medium_seconds%", String.valueOf(config.getInt(gameConfigBasePath + "answer_time.medium", 160) / 20))
                .replace("%answer_time.hard_seconds%", String.valueOf(config.getInt(gameConfigBasePath + "answer_time.hard", 120) / 20));

        return formatted.lines()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());    }

    private void sendTitleToAllGame(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        for (Player p : super.getParticipants()) {
            if (p != null && p.isOnline()) {
                p.sendTitle(ChatColor.translateAlternateColorCodes('&', title),
                        ChatColor.translateAlternateColorCodes('&', subtitle),
                        fadeIn, stay, fadeOut);
            }
        }
    }

    private String getDifficultyKeyFromName(String difficultyDisplayName) {
        if (difficultyDisplayName == null) return "easy";
        String stripped = ChatColor.stripColor(difficultyDisplayName).toLowerCase();
        if (stripped.contains("easy")) return "easy";
        if (stripped.contains("medium")) return "medium";
        if (stripped.contains("hard")) return "hard";
        return "easy";
    }
}