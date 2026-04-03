package mc.dragones.lobbycombat;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class LobbyCombatCommand implements CommandExecutor {

    private final LobbyCombatPlugin plugin;

    public LobbyCombatCommand(LobbyCombatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /lobbycombat <reload>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                if (!sender.hasPermission("lobbycombat.admin.reload")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to reload the plugin!");
                    return true;
                }
                reloadPlugin(sender);
                break;

            default:
                sender.sendMessage(ChatColor.YELLOW + "Usage: /lobbycombat <reload>");
                break;
        }

        return true;
    }

    private void reloadPlugin(CommandSender sender) {
        try {
            // Save all current data
            PlayerData.saveAllToDatabase();

            // Reload configuration
            plugin.reloadConfig();

            // Reconnect database with new settings
            plugin.getDatabaseManager().reloadConfig();

            // Reload PlaceholderAPI expansion if available
            if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                // Note: PlaceholderAPI doesn't have a direct reload method for expansions
                // The expansion will use the new config values automatically
            }

            sender.sendMessage(ChatColor.GREEN + "LobbyCombat configuration reloaded successfully!");
            plugin.getLogger().info("Configuration reloaded by " + sender.getName());

        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "Failed to reload configuration: " + e.getMessage());
            plugin.getLogger().severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
