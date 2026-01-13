package me.siwannie.partygames.admin;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import me.siwannie.partygames.PartyGames;
import me.siwannie.partygames.games.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AdminGUI {

    private static String guiTitle;
    private static int guiRows;
    private static Material fillerMaterial;
    private static Material bottomRowFillerMaterial;
    private static String gameItemNameFormat;
    private static String loreStatusEnabled;
    private static String loreStatusDisabled;
    private static String loreInstructionToggle;
    private static String loreInstructionEdit;
    private static String loreHeaderCurrentSettings;
    private static String loreDurationFormat;
    private static String loreNoPointsSettings;
    private static String lorePointsEntryFormat;
    private static String loreStreakRulesHeader;
    private static String loreStreakBonusHeader;
    private static String loreStreakEntryFormat;

    private static Material generalSettingsItemMaterial;
    private static String generalSettingsItemName;
    private static List<String> generalSettingsItemLore;

    private static final List<Game> ALL_GAME_TYPES = Arrays.asList(
            new ShearMadness(PartyGames.getInstance()),
            new ColorChaos(PartyGames.getInstance()),
            new BombDefusal(PartyGames.getInstance()),
            new TargetTerror(PartyGames.getInstance()),
            new PillarsOfFortune(PartyGames.getInstance()),
            new ElytraGauntlet(PartyGames.getInstance()),
            new ZombieOutbreak(PartyGames.getInstance()),
            new EchoesOfTheDeep(PartyGames.getInstance())
    );

    public static void loadConfigurableSettings(PartyGames plugin) {
        FileConfiguration config = plugin.getConfig();
        String basePath = "admin_gui.";

        guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "title", "&5&lPartyGames Admin Panel"));
        guiRows = Math.max(3, Math.min(6, config.getInt(basePath + "rows", 6)));

        String fillerMatName = config.getString(basePath + "filler_item_material", "GRAY_STAINED_GLASS_PANE");
        fillerMaterial = Material.matchMaterial(fillerMatName.toUpperCase());
        if (fillerMaterial == null) {
            plugin.getLogger().warning("[AdminGUI] Invalid filler_item_material: " + fillerMatName + ". Defaulting to GRAY_STAINED_GLASS_PANE.");
            fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        }

        String bottomFillerMatName = config.getString(basePath + "bottom_row_filler_material", "YELLOW_STAINED_GLASS_PANE");
        bottomRowFillerMaterial = Material.matchMaterial(bottomFillerMatName.toUpperCase());
        if (bottomRowFillerMaterial == null) {
            plugin.getLogger().warning("[AdminGUI] Invalid bottom_row_filler_material: " + bottomFillerMatName + ". Defaulting to YELLOW_STAINED_GLASS_PANE.");
            bottomRowFillerMaterial = Material.YELLOW_STAINED_GLASS_PANE;
        }

        gameItemNameFormat = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.name_format", "&6%game_name%"));
        loreStatusEnabled = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_status_enabled", "&aStatus: ENABLED"));
        loreStatusDisabled = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_status_disabled", "&cStatus: DISABLED"));
        loreInstructionToggle = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_instruction_toggle", "&3Shift-Click: &bToggle Enable/Disable"));
        loreInstructionEdit = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_instruction_edit", "&3Left-Click: &bEdit Game Settings"));
        loreHeaderCurrentSettings = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_header_current_settings", "&6Current Settings (from config):"));
        loreDurationFormat = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_duration_format", "&7Duration: &e%duration% seconds"));
        loreNoPointsSettings = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_no_points_settings", "&7No specific points settings."));
        lorePointsEntryFormat = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_points_entry_format", "&7%key%: &e%value%"));
        loreStreakRulesHeader = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_streak_rules_header", "&6Streak Rules:"));
        loreStreakBonusHeader = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_streak_bonus_header", "&6Streak Bonus:"));
        loreStreakEntryFormat = ChatColor.translateAlternateColorCodes('&', config.getString(basePath + "game_item.lore_streak_entry_format", "&7 %key%: &e%value%"));

        String genSetBasePath = basePath + "general_settings_item.";
        String genSetMatName = config.getString(genSetBasePath + "material", "COMPARATOR");
        generalSettingsItemMaterial = Material.matchMaterial(genSetMatName.toUpperCase());
        if (generalSettingsItemMaterial == null) generalSettingsItemMaterial = Material.COMPARATOR;
        generalSettingsItemName = ChatColor.translateAlternateColorCodes('&', config.getString(genSetBasePath + "name", "&b&lGeneral Settings"));
        generalSettingsItemLore = config.getStringList(genSetBasePath + "lore").stream()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());
        if (generalSettingsItemLore.isEmpty()) {
            generalSettingsItemLore.add(ChatColor.GRAY + "Edit global plugin settings.");
        }
    }

    public static void open(Player player) {
        PartyGames pluginInstance = PartyGames.getInstance();
        if (guiTitle == null) {
            loadConfigurableSettings(pluginInstance);
        }
        if (guiTitle == null || fillerMaterial == null || bottomRowFillerMaterial == null || generalSettingsItemMaterial == null || generalSettingsItemName == null) {
            player.sendMessage(ChatColor.RED + "AdminGUI settings not loaded properly. Check console and config.yml!");
            pluginInstance.getLogger().severe("[AdminGUI] Critical error: GUI settings are null. Aborting GUI open.");
            return;
        }

        Inventory gui = Bukkit.createInventory(null, guiRows * 9, guiTitle);
        ItemStack bottomRowFiller = createFillerItem(bottomRowFillerMaterial, " ");

        int lastRowStartIndex = (guiRows - 1) * 9;
        for (int i = lastRowStartIndex; i < gui.getSize(); i++) {
            gui.setItem(i, bottomRowFiller);
        }

        ItemStack generalSettingsItem = new ItemStack(generalSettingsItemMaterial);
        ItemMeta gsMeta = generalSettingsItem.getItemMeta();
        if (gsMeta != null) {
            gsMeta.setDisplayName(generalSettingsItemName);
            gsMeta.setLore(generalSettingsItemLore);
            generalSettingsItem.setItemMeta(gsMeta);
        }
        int generalSettingsSlot = (guiRows * 9) - 5;
        if (generalSettingsSlot >= 0 && generalSettingsSlot < gui.getSize()) {
            gui.setItem(generalSettingsSlot, generalSettingsItem);
        }

        int[] gameSlots;
        if (guiRows >= 5) gameSlots = new int[]{10, 12, 14, 16, 28, 30, 32, 34};
        else if (guiRows >= 3) gameSlots = new int[]{10, 11, 12, 13, 14, 15, 16};
        else gameSlots = new int[]{1, 2, 3, 4, 5, 6, 7};

        for (int i = 0; i < ALL_GAME_TYPES.size(); i++) {
            if (i < gameSlots.length && gameSlots[i] < gui.getSize()) {
                if (gameSlots[i] != generalSettingsSlot && gameSlots[i] < lastRowStartIndex) {
                    addGameItem(gui, gameSlots[i], ALL_GAME_TYPES.get(i));
                } else if (gameSlots[i] == generalSettingsSlot) {
                    pluginInstance.getLogger().warning("[AdminGUI] Game slot " + gameSlots[i] + " conflicts with general settings slot. Skipping game item here.");
                }
            }
        }

        fillRemainingEmptySlots(gui, createFillerItem(fillerMaterial, " "));

        player.openInventory(gui);
    }

    private static ItemStack createFillerItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void fillRemainingEmptySlots(Inventory gui, ItemStack filler) {
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, filler);
            }
        }
    }

    private static void addGameItem(Inventory gui, int slot, Game game) {
        PartyGames plugin = PartyGames.getInstance();
        FileConfiguration config = plugin.getConfig();
        String gameId = game.getId().toLowerCase();
        String gameConfigPath = "games." + gameId + ".";

        String iconMaterialName = config.getString(gameConfigPath + "icon", "BOOK");
        Material iconMaterial = Material.matchMaterial(iconMaterialName.toUpperCase());
        if (iconMaterial == null) iconMaterial = Material.BOOK;

        ItemStack item = new ItemStack(iconMaterial);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            List<String> disabledGames = config.getStringList("disabled-games").stream()
                    .map(String::toLowerCase).collect(Collectors.toList());
            boolean enabled = !disabledGames.contains(gameId);


            List<String> lore = new ArrayList<>();
            lore.add(enabled ? loreStatusEnabled : loreStatusDisabled);
            lore.add(" ");
            lore.add(loreInstructionToggle);
            lore.add(loreInstructionEdit);
            lore.add(" ");
            lore.add(loreHeaderCurrentSettings);

            if (game instanceof PillarsOfFortune) {
                int p1 = config.getInt(gameConfigPath + "phase1_duration", 120);
                int p2 = config.getInt(gameConfigPath + "phase2_duration", 180);
                lore.add(ChatColor.GRAY + "Phase 1: " + ChatColor.YELLOW + p1 + "s");
                lore.add(ChatColor.GRAY + "Phase 2: " + ChatColor.YELLOW + p2 + "s");
            } else {
                int currentDuration = config.getInt(gameConfigPath + "duration", game.getDuration());
                lore.add(loreDurationFormat.replace("%duration%", String.valueOf(currentDuration)));
            }

            ConfigurationSection pointsSection = config.getConfigurationSection(gameConfigPath + "points");
            if (pointsSection != null) {
                pointsSection.getKeys(false).forEach(key ->
                        lore.add(lorePointsEntryFormat.replace("%key%", key.replace("_", " ").toUpperCase())
                                .replace("%value%", String.valueOf(pointsSection.get(key))))
                );
            } else {
                if (game instanceof PillarsOfFortune) {
                    lore.add(lorePointsEntryFormat.replace("%key%", "Y INCREASE").replace("%value%", String.valueOf(config.getInt(gameConfigPath + "points_per_y_increase"))));
                    lore.add(lorePointsEntryFormat.replace("%key%", "SURVIVAL/SEC").replace("%value%", String.valueOf(config.getInt(gameConfigPath + "points_per_second"))));
                    lore.add(lorePointsEntryFormat.replace("%key%", "KILL").replace("%value%", String.valueOf(config.getInt(gameConfigPath + "points_per_kill"))));
                    lore.add(lorePointsEntryFormat.replace("%key%", "LMS BONUS").replace("%value%", String.valueOf(config.getInt(gameConfigPath + "last_man_standing_bonus_points"))));
                    lore.add(lorePointsEntryFormat.replace("%key%", "DROP PENALTY").replace("%value%", String.valueOf(config.getInt(gameConfigPath + "points_penalty_per_drop"))));
                } else {
                    lore.add(loreNoPointsSettings);
                }
            }

            ConfigurationSection streakRulesSection = config.getConfigurationSection(gameConfigPath + "streak_rules");
            if (streakRulesSection != null) {
                lore.add(loreStreakRulesHeader);
                streakRulesSection.getKeys(false).forEach(key ->
                        lore.add(loreStreakEntryFormat.replace("%key%", key.replace("_", " "))
                                .replace("%value%", String.valueOf(streakRulesSection.get(key))))
                );
            }
            ConfigurationSection streakBonusSection = config.getConfigurationSection(gameConfigPath + "streak_bonus");
            if (streakBonusSection != null && streakRulesSection == null) {
                lore.add(loreStreakBonusHeader);
                streakBonusSection.getKeys(false).forEach(key ->
                        lore.add(loreStreakEntryFormat.replace("%key%", key.replace("_", " "))
                                .replace("%value%", String.valueOf(streakBonusSection.get(key))))
                );
            }
            meta.setDisplayName(gameItemNameFormat.replace("%game_name%", game.getName()));
            meta.setLore(lore);
            if (enabled) {
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        gui.setItem(slot, item);
    }


    public static void handleClick(Player player, InventoryClickEvent event) {
        PartyGames pluginInstance = PartyGames.getInstance();
        if (guiTitle == null) loadConfigurableSettings(pluginInstance);
        if (!event.getView().getTitle().equals(guiTitle)) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta() || clicked.getItemMeta() == null || !clicked.getItemMeta().hasDisplayName()) return;

        String clickedItemName = clicked.getItemMeta().getDisplayName();

        if (generalSettingsItemName != null && clickedItemName.equals(generalSettingsItemName)) {
            player.closeInventory();
            pluginInstance.getAdminSession().startGeneralSettingsSession(player);
            return;
        }

        String strippedDisplayName = ChatColor.stripColor(clickedItemName);
        Game selectedGame = null;
        if (gameItemNameFormat != null) {
            for(Game gameType : ALL_GAME_TYPES){
                String gameNameFromFormat = ChatColor.stripColor(gameItemNameFormat.replace("%game_name%", gameType.getName()));
                if(gameNameFromFormat.equalsIgnoreCase(strippedDisplayName)){
                    selectedGame = gameType;
                    break;
                }
            }
        }

        if (selectedGame != null) {
            if (event.isShiftClick()) {
                toggleGameEnablementInConfig(player, selectedGame);
            } else {
                player.closeInventory();
                pluginInstance.getAdminSession().startSession(player, selectedGame);
            }
            return;
        }
    }

    private static void toggleGameEnablementInConfig(Player player, Game game) {
        PartyGames plugin = PartyGames.getInstance();
        FileConfiguration config = plugin.getConfig();
        String gameId = game.getId().toLowerCase();
        List<String> disabledGames = config.getStringList("disabled-games").stream()
                .map(String::toLowerCase).collect(Collectors.toCollection(ArrayList::new));
        boolean currentlyEnabled = !disabledGames.contains(gameId);
        String statusMessage;
        if (currentlyEnabled) {
            if (!disabledGames.contains(gameId)) disabledGames.add(gameId);
            statusMessage = ChatColor.RED + game.getName() + " has been DISABLED.";
        } else {
            disabledGames.remove(gameId);
            statusMessage = ChatColor.GREEN + game.getName() + " has been ENABLED.";
        }
        config.set("disabled-games", disabledGames);
        plugin.saveConfig();
        GameManager.getInstance().reloadGameQueueFromConfig();
        player.sendMessage(statusMessage);
        player.sendMessage(ChatColor.AQUA + "Game queue refreshed.");
        final Player finalPlayer = player;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Inventory openInventory = finalPlayer.getOpenInventory().getTopInventory();
            if (openInventory != null && finalPlayer.getOpenInventory().getTitle().equals(guiTitle)) {
                open(finalPlayer);
            }
        }, 1L);
    }
}