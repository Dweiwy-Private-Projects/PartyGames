package me.siwannie.partygames.admin;

import me.siwannie.partygames.Game;
import me.siwannie.partygames.GameManager;
import me.siwannie.partygames.PartyGames;
import me.siwannie.partygames.games.ColorChaos;
import me.siwannie.partygames.games.PillarsOfFortune;
import me.siwannie.partygames.games.ShearMadness;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.util.*;

public class AdminSession implements Listener {

    public static final String INTERNAL_COMMAND_PREFIX = "/pgadminaction";

    private enum EditMode {
        GAME_SPECIFIC,
        GENERAL_SETTINGS
    }

    private enum GameEditStep {
        CHOOSING_GAME_FIELD,
        EDITING_GAME_DURATION,
        CHOOSING_GAME_POINT_KEY,
        EDITING_GAME_POINT_VALUE,
        CHOOSING_POF_DURATION_KEY,
        EDITING_POF_DURATION_VALUE
    }

    private enum GeneralEditStep {
        CHOOSING_GENERAL_SETTING,
        EDITING_TRANSITION_BUFFER,
        TOGGLING_COMMAND_BLOCKER,
        TOGGLING_CHAT_FORMAT,
        MANAGING_GAME_QUEUE_INFO
    }

    private static class SessionData {
        EditMode mode;
        Game game;
        Object currentStep;
        String editingKey;

        SessionData(Game game, GameEditStep step) {
            this.mode = EditMode.GAME_SPECIFIC;
            this.game = game;
            this.currentStep = step;
        }

        SessionData(GeneralEditStep step) {
            this.mode = EditMode.GENERAL_SETTINGS;
            this.game = null;
            this.currentStep = step;
        }
    }

    private final Map<UUID, SessionData> editingPlayers = new HashMap<>();
    private final PartyGames plugin;

    public AdminSession(PartyGames plugin) {
        this.plugin = plugin;
    }

    public void startSession(Player player, Game game) {
        player.sendMessage(ChatColor.GREEN + "Editing game: " + ChatColor.YELLOW + game.getName());
        TextComponent message = new TextComponent(ChatColor.GREEN + "Click field to edit: ");

        TextComponent durationLink = new TextComponent("[Duration]");
        durationLink.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
        durationLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " duration"));
        durationLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Click to edit duration")));
        message.addExtra(durationLink);

        message.addExtra(new TextComponent(ChatColor.GREEN + " or "));

        TextComponent pointsLink = new TextComponent("[Points]");
        pointsLink.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
        pointsLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " points"));
        pointsLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Click to edit points")));
        message.addExtra(pointsLink);

        message.addExtra(new TextComponent(ChatColor.GREEN + ". Type 'cancel' to exit."));
        player.spigot().sendMessage(message);
        editingPlayers.put(player.getUniqueId(), new SessionData(game, GameEditStep.CHOOSING_GAME_FIELD));
    }

    public void startGeneralSettingsSession(Player player) {
        player.sendMessage(ChatColor.GREEN + "Editing General PartyGames Settings.");
        sendGeneralSettingsOptions(player);
        editingPlayers.put(player.getUniqueId(), new SessionData(GeneralEditStep.CHOOSING_GENERAL_SETTING));
    }

    private void sendGeneralSettingsOptions(Player player) {
        player.sendMessage(ChatColor.YELLOW + "Click a setting key to edit or view, or type 'cancel' to exit:");

        TextComponent buffer = new TextComponent(ChatColor.GOLD + " - ");
        TextComponent bufferLink = new TextComponent("buffer");
        bufferLink.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        bufferLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " buffer"));
        bufferLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GREEN + "Edit Transition Buffer")));
        buffer.addExtra(bufferLink);
        buffer.addExtra(new TextComponent(ChatColor.GRAY + " (Transition Buffer Seconds)"));
        player.spigot().sendMessage(buffer);

        TextComponent cmdblock = new TextComponent(ChatColor.GOLD + " - ");
        TextComponent cmdblockLink = new TextComponent("cmdblock");
        cmdblockLink.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        cmdblockLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " cmdblock"));
        cmdblockLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GREEN + "Toggle Command Blocker")));
        cmdblock.addExtra(cmdblockLink);
        cmdblock.addExtra(new TextComponent(ChatColor.GRAY + " (Command Blocker Enabled T/F)"));
        player.spigot().sendMessage(cmdblock);

        TextComponent chatformat = new TextComponent(ChatColor.GOLD + " - ");
        TextComponent chatformatLink = new TextComponent("chatformat");
        chatformatLink.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        chatformatLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " chatformat"));
        chatformatLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GREEN + "Toggle Chat Formatting")));
        chatformat.addExtra(chatformatLink);
        chatformat.addExtra(new TextComponent(ChatColor.GRAY + " (Chat Formatting Enabled T/F)"));
        player.spigot().sendMessage(chatformat);

        TextComponent queue = new TextComponent(ChatColor.GOLD + " - ");
        TextComponent queueLink = new TextComponent("queue");
        queueLink.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        queueLink.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " queue"));
        queueLink.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GREEN + "View Game Queue Info")));
        queue.addExtra(queueLink);
        queue.addExtra(new TextComponent(ChatColor.GRAY + " (View/Info Game Queue)"));
        player.spigot().sendMessage(queue);
    }

    public void cancelSession(Player player) {
        editingPlayers.remove(player.getUniqueId());
        player.sendMessage(ChatColor.RED + "Editing session cancelled.");
    }

    public void processAdminAction(Player player, String action) {
        UUID uuid = player.getUniqueId();
        if (!editingPlayers.containsKey(uuid)) {
            return;
        }
        SessionData session = editingPlayers.get(uuid);

        if (session.mode == EditMode.GAME_SPECIFIC) {
            handleGameSpecificEdit(player, action, session);
        } else if (session.mode == EditMode.GENERAL_SETTINGS) {
            handleGeneralSettingsEdit(player, action, session);
        }
    }


    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!editingPlayers.containsKey(uuid)) return;

        String message = event.getMessage().trim().toLowerCase();
        SessionData session = editingPlayers.get(uuid);

        event.setCancelled(true);

        if (session.mode == EditMode.GAME_SPECIFIC) {
            handleGameSpecificEdit(player, message, session);
        } else if (session.mode == EditMode.GENERAL_SETTINGS) {
            handleGeneralSettingsEdit(player, message, session);
        }
    }

    private void handleGameSpecificEdit(Player player, String message, SessionData session) {
        GameEditStep step = (GameEditStep) session.currentStep;
        switch (step) {
            case CHOOSING_GAME_FIELD:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                if ("duration".equals(message)) {
                    if (session.game instanceof PillarsOfFortune) {
                        session.currentStep = GameEditStep.CHOOSING_POF_DURATION_KEY;
                        TextComponent pofDurationMsg = new TextComponent(ChatColor.GREEN + "Click to choose duration type: ");
                        TextComponent phase1Link = new TextComponent("[Phase 1]");
                        phase1Link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                        phase1Link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " phase1"));
                        phase1Link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Edit Phase 1 Duration")));
                        pofDurationMsg.addExtra(phase1Link);
                        pofDurationMsg.addExtra(new TextComponent(ChatColor.GREEN + " or "));
                        TextComponent phase2Link = new TextComponent("[Phase 2]");
                        phase2Link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                        phase2Link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " phase2"));
                        phase2Link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Edit Phase 2 Duration")));
                        pofDurationMsg.addExtra(phase2Link);
                        pofDurationMsg.addExtra(new TextComponent(ChatColor.GREEN + ". Type 'cancel' to exit."));
                        player.spigot().sendMessage(pofDurationMsg);
                    } else {
                        session.currentStep = GameEditStep.EDITING_GAME_DURATION;
                        player.sendMessage(ChatColor.GREEN + "Enter new duration (seconds) for " + ChatColor.YELLOW + session.game.getName() + ChatColor.GREEN + ", or 'cancel':");
                    }
                } else if ("points".equals(message)) {
                    session.currentStep = GameEditStep.CHOOSING_GAME_POINT_KEY;
                    sendPointKeysList(player, session.game);
                } else {
                    player.sendMessage(ChatColor.RED + "Invalid selection. Please click an option or type 'cancel'.");
                    startSession(player, session.game);
                }
                break;

            case EDITING_GAME_DURATION:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                try {
                    int seconds = Integer.parseInt(message);
                    if (seconds <= 0) { player.sendMessage(ChatColor.RED + "Duration must be positive. Try again or 'cancel'."); return; }
                    updateGameDuration(session.game, seconds);
                    player.sendMessage(ChatColor.GREEN + "Duration for " + ChatColor.YELLOW + session.game.getName() + ChatColor.GREEN + " set to " + ChatColor.AQUA + seconds + "s.");
                    cancelSession(player);
                } catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Invalid number. Enter an integer or 'cancel'."); }
                break;

            case CHOOSING_POF_DURATION_KEY:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                if ("phase1".equals(message) || "phase2".equals(message)) {
                    session.editingKey = message + "_duration";
                    session.currentStep = GameEditStep.EDITING_POF_DURATION_VALUE;
                    int currentVal = plugin.getConfig().getInt("games.pillarsoffortune." + session.editingKey, 0);
                    player.sendMessage(ChatColor.GREEN + "Current " + message + " duration: " + ChatColor.AQUA + currentVal + "s." + ChatColor.GREEN + " Enter new value or 'cancel':");
                } else {
                    player.sendMessage(ChatColor.RED + "Invalid selection. Please click [Phase 1] or [Phase 2], or type 'cancel'.");
                    TextComponent pofDurationMsg = new TextComponent(ChatColor.RED + "Invalid. Click: ");
                    TextComponent phase1Link = new TextComponent("[Phase 1]");
                    phase1Link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                    phase1Link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " phase1"));
                    phase1Link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Edit Phase 1 Duration")));
                    pofDurationMsg.addExtra(phase1Link);
                    pofDurationMsg.addExtra(new TextComponent(ChatColor.RED + " or "));
                    TextComponent phase2Link = new TextComponent("[Phase 2]");
                    phase2Link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                    phase2Link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " phase2"));
                    phase2Link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Edit Phase 2 Duration")));
                    pofDurationMsg.addExtra(phase2Link);
                    pofDurationMsg.addExtra(new TextComponent(ChatColor.RED + ". Type 'cancel' to exit."));
                    player.spigot().sendMessage(pofDurationMsg);
                }
                break;

            case EDITING_POF_DURATION_VALUE:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                try {
                    int seconds = Integer.parseInt(message);
                    if (seconds <= 0) { player.sendMessage(ChatColor.RED + "Duration must be positive. Try again or 'cancel'."); return; }
                    updatePofDurationValue((PillarsOfFortune) session.game, session.editingKey, seconds);
                    player.sendMessage(ChatColor.GREEN + "Duration for '" + ChatColor.YELLOW + session.editingKey + ChatColor.GREEN + "' set to " + ChatColor.AQUA + seconds + "s.");
                    cancelSession(player);
                } catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Invalid number. Enter an integer or 'cancel'."); }
                break;

            case CHOOSING_GAME_POINT_KEY:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                if (!getPointKeys(session.game).contains(message)) {
                    player.sendMessage(ChatColor.RED + "Invalid key '" + message + "'.");
                    sendPointKeysList(player, session.game); return;
                }
                session.editingKey = message;
                session.currentStep = GameEditStep.EDITING_GAME_POINT_VALUE;
                player.sendMessage(ChatColor.GREEN + "Enter new value for '" + ChatColor.YELLOW + session.editingKey + ChatColor.GREEN + "' in " + ChatColor.YELLOW + session.game.getName() + ChatColor.GREEN + ", or 'cancel':");
                break;

            case EDITING_GAME_POINT_VALUE:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                try {
                    int points = Integer.parseInt(message);
                    updateGamePointValue(session.game, session.editingKey, points);
                    player.sendMessage(ChatColor.GREEN + "Points for '" + ChatColor.YELLOW + session.editingKey + ChatColor.GREEN + "' in " + ChatColor.YELLOW + session.game.getName() + ChatColor.GREEN + " set to " + ChatColor.AQUA + points + ChatColor.GREEN + ".");
                    cancelSession(player);
                } catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Invalid number. Enter an integer or 'cancel'."); }
                break;
        }
    }

    private void handleGeneralSettingsEdit(Player player, String message, SessionData session) {
        GeneralEditStep step = (GeneralEditStep) session.currentStep;
        FileConfiguration config = plugin.getConfig();

        switch (step) {
            case CHOOSING_GENERAL_SETTING:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                session.editingKey = message;
                switch (message) {
                    case "buffer":
                        session.currentStep = GeneralEditStep.EDITING_TRANSITION_BUFFER;
                        player.sendMessage(ChatColor.GREEN + "Current Transition Buffer: " + ChatColor.AQUA + config.getInt("settings.transition_buffer_seconds") + "s" + ChatColor.GREEN + ". Enter new value (seconds) or 'cancel':");
                        break;
                    case "cmdblock":
                        session.currentStep = GeneralEditStep.TOGGLING_COMMAND_BLOCKER;
                        player.sendMessage(ChatColor.GREEN + "Command Blocker currently: " + (config.getBoolean("listeners_settings.command_blocker.enabled") ? ChatColor.AQUA + "ENABLED" : ChatColor.RED + "DISABLED"));
                        player.sendMessage(ChatColor.GREEN + "Type 'true' or 'false' to change, or 'cancel':");
                        break;
                    case "chatformat":
                        session.currentStep = GeneralEditStep.TOGGLING_CHAT_FORMAT;
                        player.sendMessage(ChatColor.GREEN + "Chat Formatting currently: " + (config.getBoolean("listeners_settings.chat_tweaks.format_enabled") ? ChatColor.AQUA + "ENABLED" : ChatColor.RED + "DISABLED"));
                        player.sendMessage(ChatColor.GREEN + "Type 'true' or 'false' to change, or 'cancel':");
                        break;
                    case "queue":
                        List<String> currentQueue = config.getStringList("settings.game_queue");
                        player.sendMessage(ChatColor.YELLOW + "Current Game Queue Order:");
                        if (currentQueue.isEmpty()) player.sendMessage(ChatColor.GRAY + "(Empty)");
                        else for (int i = 0; i < currentQueue.size(); i++) player.sendMessage(ChatColor.GOLD + "" + (i+1) + ". " + ChatColor.AQUA + currentQueue.get(i));
                        player.sendMessage(ChatColor.YELLOW + "To reorder/change the queue, please edit config.yml directly and then use " + ChatColor.GREEN + "/pg reload" + ChatColor.YELLOW + " or restart.");
                        player.sendMessage(ChatColor.YELLOW + "Type 'cancel' to return to main menu selection if desired (or just exit chat).");
                        session.currentStep = GeneralEditStep.CHOOSING_GENERAL_SETTING;
                        break;
                    default:
                        player.sendMessage(ChatColor.RED + "Invalid setting key '" + message + "'.");
                        sendGeneralSettingsOptions(player);
                        break;
                }
                break;

            case EDITING_TRANSITION_BUFFER:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                try {
                    int seconds = Integer.parseInt(message);
                    if (seconds < 0) { player.sendMessage(ChatColor.RED + "Buffer seconds cannot be negative. Try again or 'cancel'."); return; }
                    config.set("settings.transition_buffer_seconds", seconds);
                    plugin.saveConfig();
                    GameManager.getInstance().setTransitionBufferSeconds(seconds);
                    player.sendMessage(ChatColor.GREEN + "Transition Buffer Seconds set to " + ChatColor.AQUA + seconds + ".");
                    cancelSession(player);
                } catch (NumberFormatException ex) { player.sendMessage(ChatColor.RED + "Invalid number. Enter an integer or 'cancel'."); }
                break;

            case TOGGLING_COMMAND_BLOCKER:
            case TOGGLING_CHAT_FORMAT:
                if ("cancel".equals(message)) { cancelSession(player); return; }
                if ("true".equals(message) || "false".equals(message)) {
                    boolean enabled = Boolean.parseBoolean(message);
                    String configPath = session.editingKey.equals("cmdblock") ? "listeners_settings.command_blocker.enabled" : "listeners_settings.chat_tweaks.format_enabled";
                    String settingName = session.editingKey.equals("cmdblock") ? "Command Blocker" : "Chat Formatting";
                    config.set(configPath, enabled);
                    plugin.saveConfig();
                    if (plugin.getGameListenersInstance() != null) plugin.getGameListenersInstance().reloadConfigSettings();
                    player.sendMessage(ChatColor.GREEN + settingName + " " + (enabled ? ChatColor.AQUA + "ENABLED" : ChatColor.RED + "DISABLED") + ".");
                    cancelSession(player);
                } else {
                    player.sendMessage(ChatColor.RED + "Invalid input. Type 'true', 'false', or 'cancel'.");
                }
                break;
        }
    }

    private void sendPointKeysList(Player player, Game game) {
        player.sendMessage(ChatColor.YELLOW + "Click a point key for " + ChatColor.AQUA + game.getName() + ChatColor.YELLOW + " to edit, or type 'cancel':");
        List<String> pointKeys = getPointKeys(game);

        if (pointKeys.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "(No point keys in config or section missing)");
        } else {
            for (String key : pointKeys) {
                TextComponent messageComponent = new TextComponent(ChatColor.GOLD + " - ");
                TextComponent keyComponent = new TextComponent(key);
                keyComponent.setColor(net.md_5.bungee.api.ChatColor.AQUA);
                keyComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " " + key));
                keyComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.GREEN + "Click to select '" + key + "'")));
                messageComponent.addExtra(keyComponent);
                player.spigot().sendMessage(messageComponent);
            }
        }
    }

    private List<String> getPointKeys(Game game) {
        String gameId = game.getId();
        org.bukkit.configuration.ConfigurationSection pointsSection = plugin.getConfig().getConfigurationSection("games." + gameId + ".points");
        if (pointsSection == null) return Collections.emptyList();
        return new ArrayList<>(pointsSection.getKeys(false));
    }

    private void updateGamePointValue(Game game, String pointKey, int value) {
        String gameId = game.getId();
        String configPath = "games." + gameId + ".points." + pointKey;
        plugin.getConfig().set(configPath, value);
        plugin.saveConfig();

        if (game instanceof ShearMadness sm) {
            switch (pointKey.toLowerCase()) {
                case "per_shear": sm.setPointsPerShear(value); break;
                case "streak_bonus": sm.setStreakBonusPoints(value); break;
                case "streak_length": sm.setStreakThresholdForBonus(value); break;
                default: plugin.getLogger().warning("Unknown point key '" + pointKey + "' for ShearMadness in AdminSession."); break;
            }
        } else if (game instanceof ColorChaos cc) {
            switch (pointKey.toLowerCase()) {
                case "correct": cc.setCorrectPoints(value); break;
                case "wrong": cc.setWrongPoints(value); break;
                default: plugin.getLogger().warning("Unknown point key '" + pointKey + "' for ColorChaos in AdminSession."); break;
            }
        } else if (game instanceof PillarsOfFortune) {
            plugin.getLogger().info("Set " + pointKey + " for " + game.getName() + " to " + value + ". (Requires restart/reload or game-specific setters for live update).");
        }
    }

    private void updatePofDurationValue(PillarsOfFortune game, String durationKey, int seconds) {
        String gameId = game.getId();
        String configPath = "games." + gameId + "." + durationKey;
        plugin.getConfig().set(configPath, seconds);
        plugin.saveConfig();
        plugin.getLogger().info("Set " + durationKey + " for " + game.getName() + " to " + seconds + "s. (Live update requires POF changes).");
    }

    private void updateGameDuration(Game game, int seconds) {
        Player player = null;
        for(Map.Entry<UUID, SessionData> entry : editingPlayers.entrySet()){
            if(entry.getValue().game == game){
                Player p = plugin.getServer().getPlayer(entry.getKey());
                if(p != null) player = p;
                break;
            }
        }

        if (game instanceof PillarsOfFortune) {
            if(player != null) {
                player.sendMessage(ChatColor.RED + "For Pillars of Fortune, edit 'phase1' or 'phase2' durations individually.");
                SessionData session = editingPlayers.get(player.getUniqueId());
                if (session != null) {
                    session.currentStep = GameEditStep.CHOOSING_POF_DURATION_KEY;
                    TextComponent pofDurationMsg = new TextComponent(ChatColor.GREEN + "Click to choose duration type: ");
                    TextComponent phase1Link = new TextComponent("[Phase 1]");
                    phase1Link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                    phase1Link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " phase1"));
                    phase1Link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Edit Phase 1 Duration")));
                    pofDurationMsg.addExtra(phase1Link);
                    pofDurationMsg.addExtra(new TextComponent(ChatColor.GREEN + " or "));
                    TextComponent phase2Link = new TextComponent("[Phase 2]");
                    phase2Link.setColor(net.md_5.bungee.api.ChatColor.YELLOW);
                    phase2Link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, INTERNAL_COMMAND_PREFIX + " phase2"));
                    phase2Link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(ChatColor.AQUA + "Edit Phase 2 Duration")));
                    pofDurationMsg.addExtra(phase2Link);
                    pofDurationMsg.addExtra(new TextComponent(ChatColor.GREEN + ". Type 'cancel' to exit."));
                    player.spigot().sendMessage(pofDurationMsg);
                }
            } else {
                plugin.getLogger().warning("PillarsOfFortune duration cannot be set generally. Player context missing for redirection message.");
            }
            return;
        }
        String gameId = game.getId();
        plugin.getConfig().set("games." + gameId + ".duration", seconds);
        plugin.saveConfig();
        if (game != null) game.setDuration(seconds);
    }
}