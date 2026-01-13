package me.siwannie.partygames.commands;

import me.siwannie.partygames.GameManager;
import me.siwannie.partygames.PartyGames;
import me.siwannie.partygames.admin.AdminGUI;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class PartyGamesCommandCenter implements CommandExecutor {

    private final PartyGames plugin;

    public PartyGamesCommandCenter(PartyGames plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        GameManager gm = GameManager.getInstance();

        if (args.length == 0) {
            player.sendMessage(ChatColor.AQUA + "PartyGames Commands:");
            player.sendMessage(ChatColor.YELLOW + "/pg join" + ChatColor.WHITE + " - Join the game queue");
            player.sendMessage(ChatColor.YELLOW + "/pg leave" + ChatColor.WHITE + " - Leave the queue or current game");
            player.sendMessage(ChatColor.YELLOW + "/pg start" + ChatColor.WHITE + " - Start the game series");
            player.sendMessage(ChatColor.YELLOW + "/pg pause" + ChatColor.WHITE + " - Pause or resume the current game/buffer");
            player.sendMessage(ChatColor.YELLOW + "/pg setspawn <gameId>" + ChatColor.WHITE + " - Set a game's teleport location");
            player.sendMessage(ChatColor.YELLOW + "/pg admin" + ChatColor.WHITE + " - Open admin GUI");
            player.sendMessage(ChatColor.YELLOW + "/pg reset" + ChatColor.WHITE + " - Reset all points and game data");
            player.sendMessage(ChatColor.YELLOW + "/pg reload" + ChatColor.WHITE + " - Reload plugin configurations");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "join":
                if (!player.hasPermission("partygames.command.join")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                gm.addPlayerToLobbyQueue(player);
                break;

            case "leave":
                if (!player.hasPermission("partygames.command.leave")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                gm.removePlayerFromLobbyQueue(player);
                break;

            case "start":
                if (!player.hasPermission("partygames.command.start")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                if (gm.isGameStarted()) {
                    player.sendMessage(ChatColor.YELLOW + "PartyGames series is already running or in buffer!");
                    return true;
                }
                if (gm.getLobbyQueue().isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Cannot start PartyGames: The player queue is empty. Players must use /pg join.");
                    return true;
                }
                gm.startNextGameOrBuffer();
                player.sendMessage(ChatColor.GREEN + "Attempting to start PartyGames series...");
                break;

            case "pause":
                if (!player.hasPermission("partygames.command.pause")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                if (gm.isPaused()) {
                    gm.resumeCurrentGame();
                } else if (gm.isGameStarted() || gm.isInBuffer()) {
                    gm.pauseCurrentGame();
                } else {
                    player.sendMessage(ChatColor.RED + "There is no game or buffer active to pause or resume.");
                }
                break;

            case "setspawn":
                if (!player.hasPermission("partygames.command.setspawn")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /pg setspawn <gameId>");
                    return true;
                }
                String gameIdForSpawn = args[1].toLowerCase();
                String pathToGame = "games." + gameIdForSpawn;
                if (!plugin.getConfig().contains(pathToGame)) {
                    player.sendMessage(ChatColor.RED + "Game '" + gameIdForSpawn + "' (path: " + pathToGame + ") does not exist as a top-level entry under 'games:' in the config.");
                    if (plugin.getConfig().getConfigurationSection("games") != null) {
                        player.sendMessage(ChatColor.YELLOW + "Available game IDs: " + plugin.getConfig().getConfigurationSection("games").getKeys(false));
                    }
                    return true;
                }
                Location loc = player.getLocation();
                String teleportBasePath = pathToGame + ".teleport";
                plugin.getConfig().set(teleportBasePath + ".world", loc.getWorld().getName());
                plugin.getConfig().set(teleportBasePath + ".x", loc.getX());
                plugin.getConfig().set(teleportBasePath + ".y", loc.getY());
                plugin.getConfig().set(teleportBasePath + ".z", loc.getZ());
                plugin.getConfig().set(teleportBasePath + ".yaw", loc.getYaw());
                plugin.getConfig().set(teleportBasePath + ".pitch", loc.getPitch());
                plugin.saveConfig();
                player.sendMessage(ChatColor.GREEN + "Set teleport spawn for game '" + gameIdForSpawn + "' at your current location.");
                player.sendMessage(ChatColor.YELLOW + "This will be used when '" + gameIdForSpawn + "' starts.");
                break;

            case "admin":
                if (!player.hasPermission("partygames.command.admin")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission.");
                    return true;
                }
                AdminGUI.open(player);
                break;

            case "reset":
                if (!player.hasPermission("partygames.command.reset")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission.");
                    return true;
                }
                gm.reset();
                player.sendMessage(ChatColor.GREEN + "All PartyGames data and player points have been reset.");
                break;

            case "reload":
            case "rl":
                if (!player.hasPermission("partygames.command.reload")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    return true;
                }

                if (gm.isGameRunning() || gm.isInBuffer()) {
                    player.sendMessage(ChatColor.RED + "Cannot reload configurations while a game or buffer is active. Please wait or end the current game.");
                    return true;
                }

                PartyGames mainPlugin = (PartyGames) plugin;

                mainPlugin.reloadConfig();
                player.sendMessage(ChatColor.YELLOW + "Bukkit config reloaded...");

                gm.reloadAllConfigurableSettings();
                player.sendMessage(ChatColor.YELLOW + "GameManager settings and game queue reloaded...");

                if (mainPlugin.getGameListenersInstance() != null) {
                    mainPlugin.getGameListenersInstance().reloadConfigSettings();
                    player.sendMessage(ChatColor.YELLOW + "GameListeners settings reloaded...");
                }

                AdminGUI.loadConfigurableSettings(mainPlugin);
                player.sendMessage(ChatColor.YELLOW + "AdminGUI display settings reloaded...");

                player.sendMessage(ChatColor.GREEN + "PartyGames configurations have been successfully reloaded!");
                PartyGames.getInstance().getLogger().info("PartyGames configurations reloaded by " + player.getName());
                break;

            default:
                player.sendMessage(ChatColor.RED + "Unknown subcommand. Try /pg");
                break;
        }
        return true;
    }
}