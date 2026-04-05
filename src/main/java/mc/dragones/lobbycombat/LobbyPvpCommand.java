package mc.dragones.lobbycombat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LobbyPvpCommand implements CommandExecutor {

    private final LobbyCombatPlugin plugin;
    private final Random random = new Random();

    public LobbyPvpCommand(LobbyCombatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        PlayerData data = PlayerData.get(player);

        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /lobbypvp <enable|disable|status|color|interrupt|alerts|farmlogs|farmcheck|reload>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "enable":
                if (!player.hasPermission(plugin.getConfig().getString("permissions.enable", "lobbycombat.pvp.enable"))) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to enable PvP!");
                    return true;
                }
                enablePvp(player, data);
                break;

            case "disable":
                if (!player.hasPermission(plugin.getConfig().getString("permissions.disable", "lobbycombat.pvp.disable"))) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to disable PvP!");
                    return true;
                }
                disablePvp(player, data);
                break;

            case "status":
                showStatus(player);
                break;

            case "color":
                if (args.length > 1 && args[1].equalsIgnoreCase("set")) {
                    if (args.length > 2) {
                        setArmorColor(player, args[2]);
                    } else {
                        player.sendMessage(ChatColor.RED + "Usage: /lobbypvp color set <color>");
                    }
                } else {
                    showColorMenu(player);
                }
                break;

            case "interrupt":
                if (!player.hasPermission(plugin.getConfig().getString("permissions.interrupt", "lobbycombat.pvp.interrupt"))) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to toggle anti-interrupt!");
                    return true;
                }
                toggleAntiInterrupt(player);
                break;

            case "alerts":
                if (!player.hasPermission(plugin.getConfig().getString("permissions.admin.alerts", "lobbycombat.admin.alerts"))) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to toggle alerts!");
                    return true;
                }
                toggleAlerts(player, data);
                break;

            case "farmlogs":
                if (!player.hasPermission("lobbycombat.antifarm.staff")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to view anti-farm logs!");
                    return true;
                }
                // Show latest global alerts (not player-specific)
                showLatestGlobalAlerts(player);
                break;

            case "farmcheck":
                if (!player.hasPermission("lobbycombat.antifarm.staff")) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to view anti-farm logs!");
                    return true;
                }
                if (args.length > 1) {
                    viewFarmLogs(player, args[1]);
                } else {
                    player.sendMessage(ChatColor.RED + "Usage: /lobbypvp farmcheck <player>");
                }
                break;

            case "reload":
                if (!player.hasPermission(plugin.getConfig().getString("permissions.admin.reload", "lobbycombat.admin.reload"))) {
                    player.sendMessage(ChatColor.RED + "You don't have permission to reload the plugin!");
                    return true;
                }
                reloadPlugin(player);
                break;

            default:
                player.sendMessage(ChatColor.YELLOW + "Usage: /lobbypvp <enable|disable|status|color|interrupt|alerts|farmlogs|farmcheck|reload>");
                break;
        }

        return true;
    }

    private void enablePvp(Player player, PlayerData data) {
        if (data.isPvpEnabled()) {
            player.sendMessage(ChatColor.YELLOW + "PvP is already enabled!");
            return;
        }

        // Cancel any running disable countdown
        if (data.getDisableCountdownTask() != null) {
            data.getDisableCountdownTask().cancel();
            data.setDisableCountdownTask(null);
        }

        data.setPvpEnabled(true);
        data.setInCombat(false);
        data.setFightingWith(null);

        // Clear inventory directly (without using clear-command)
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] Enabling PvP for " + player.getName() + ", giving items...");
        }

        // Give items
        givePvpItems(player);

        // Start rainbow animation if selected color is rainbow or pastel
        String selectedColor = data.getArmorColor();
        if ("rainbow".equals(selectedColor) || "pastel".equals(selectedColor)) {
            plugin.getArmorColorManager().startRainbowTaskIfNeeded(player);
        }

        // Play sound
        player.playSound(player.getLocation(), plugin.getSound("pvp-enabled"), 1.0f, 1.0f);

        // Send message
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            plugin.getConfig().getString("messages.pvp-enabled", "&aPvP enabled!")));
    }

    private void disablePvp(Player player, PlayerData data) {
        if (!data.isPvpEnabled()) {
            player.sendMessage(ChatColor.YELLOW + "PvP is already disabled!");
            return;
        }

        // Check if a countdown is already running
        if (data.getDisableCountdownTask() != null) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("messages.countdown-already-running", "&cA PvP disable countdown is already running!")));
            return;
        }

        long timeSinceLastDamage = System.currentTimeMillis() - data.getLastDamageTime();
        int noCombatSeconds = plugin.getConfig().getInt("pvp.disable.no-combat-seconds", 10) * 1000;

        if (data.isInCombat() && timeSinceLastDamage < noCombatSeconds) {
            // Start countdown
            int countdownSeconds = plugin.getConfig().getInt("pvp.disable.countdown-seconds", 10);
            startDisableCountdown(player, countdownSeconds);
        } else {
            // Disable instantly
            performDisable(player, data);
        }
    }

    private void startDisableCountdown(final Player player, final int secondsStart) {
        // Cancel any existing countdown task
        PlayerData data = PlayerData.get(player);
        if (data.getDisableCountdownTask() != null) {
            data.getDisableCountdownTask().cancel();
        }

        org.bukkit.scheduler.BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, new org.bukkit.scheduler.BukkitRunnable() {
            private int seconds = secondsStart;

            @Override
            public void run() {
                PlayerData currentData = PlayerData.get(player);
                if (seconds <= 0) {
                    if (currentData.isPvpEnabled()) {
                        performDisable(player, currentData);
                    }
                    currentData.setDisableCountdownTask(null);
                    // Don't cancel here - let the task finish naturally
                    return;
                }

                // Show subtitle using sendMessage instead of sendTitle (not available in 1.8.8)
                String color = plugin.getConfig().getString("pvp.disable.countdown-color", "&c");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    color + seconds));

                // Play sound
                player.playSound(player.getLocation(), plugin.getSound("disable-countdown"), 1.0f, 1.0f);

                seconds--;
            }
        }, 0L, 20L); // Every second

        data.setDisableCountdownTask(task);
    }

    private void performDisable(Player player, PlayerData data) {
        // Cancel any running disable countdown
        if (data.getDisableCountdownTask() != null) {
            data.getDisableCountdownTask().cancel();
            data.setDisableCountdownTask(null);
        }

        data.setPvpEnabled(false);
        data.setInCombat(false);
        data.setFightingWith(null);

        // Clear inventory
        clearInventory(player);

        // Run disable command if configured
        String disableCommand = plugin.getConfig().getString("pvp.disable.command");
        if (disableCommand != null && !disableCommand.trim().isEmpty()) {
            String cmd = disableCommand.replace("%p", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }

        // Play sound
        player.playSound(player.getLocation(), plugin.getSound("pvp-disabled"), 1.0f, 1.0f);

        // Send message
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            plugin.getConfig().getString("messages.pvp-disabled", "&cPvP disabled!")));
    }

    private void toggleAntiInterrupt(Player player) {
        PlayerData data = PlayerData.get(player);
        boolean current = data.isAntiInterruptEnabled();
        data.setAntiInterruptEnabled(!current);

        String status = !current ? "enabled" : "disabled";
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            plugin.getConfig().getString("messages.anti-interrupt-toggled", "&aAnti-interrupt %s").replace("%s", status)));
    }

    private void toggleAlerts(Player player, PlayerData data) {
        boolean currentState = data.isAlertsEnabled();
        data.setAlertsEnabled(!currentState);

        String statusMessage;
        if (!currentState) {
            // Alerts are now enabled
            statusMessage = plugin.getConfig().getString("messages.alerts-enabled", "&aCoward alerts &cenabled!");
        } else {
            // Alerts are now disabled
            statusMessage = plugin.getConfig().getString("messages.alerts-disabled", "&cCoward alerts &cdisabled!");
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', statusMessage));
    }

    private void clearInventory(Player player) {
        String method = plugin.getConfig().getString("inventory.clear-method", "plugin");

        if ("command".equals(method)) {
            String cmd = plugin.getConfig().getString("inventory.clear-command", "clear %p")
                .replace("%p", player.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else {
            player.getInventory().clear();
            // Also clear armor slots
            player.getInventory().setArmorContents(null);
        }
    }

    private void givePvpItems(Player player) {
        // Armor
        giveArmor(player);

        // Weapon
        giveWeapon(player);

        // Food
        giveFood(player);

        // Disable item
        giveDisableItem(player);
    }

    private void giveArmor(Player player) {
        PlayerData data = PlayerData.get(player);
        String selectedColor = data.getArmorColor();

        // Use the selected color for both leather armor pieces
        org.bukkit.Color armorColor = plugin.getArmorColorManager().getColor(selectedColor);
        if (armorColor == null) {
            // Fallback to random if color is invalid
            armorColor = org.bukkit.Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        }

        // Helmet
        ItemStack helmet = createArmorItem("helmet", armorColor);
        if (helmet != null) player.getInventory().setHelmet(helmet);

        // Chestplate
        ItemStack chestplate = createArmorItem("chestplate", armorColor);
        if (chestplate != null) player.getInventory().setChestplate(chestplate);

        // Leggings
        ItemStack leggings = createArmorItem("leggings", null);
        if (leggings != null) player.getInventory().setLeggings(leggings);

        // Boots
        ItemStack boots = createArmorItem("boots", null);
        if (boots != null) player.getInventory().setBoots(boots);
    }

    private ItemStack createArmorItem(String type, org.bukkit.Color sharedColor) {
        String materialName = plugin.getConfig().getString("inventory.items.armor." + type + ".material");
        if (materialName == null) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Armor material for " + type + " is null in config");
            }
            return null;
        }

        Material material = Material.getMaterial(materialName);
        if (material == null) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Material '" + materialName + "' for " + type + " not found");
            }
            return null;
        }

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] Creating armor item: " + type + " with material " + materialName);
        }
        ItemStack item = new ItemStack(material);

        if (material.name().contains("LEATHER")) {
            LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
            String color = plugin.getConfig().getString("inventory.items.armor." + type + ".color", "random");

            if ("random".equals(color)) {
                // Use shared color if provided, otherwise generate new one
                org.bukkit.Color armorColor = sharedColor != null ? sharedColor :
                    org.bukkit.Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256));
                meta.setColor(armorColor);
            } else {
                // Parse hex color
                try {
                    meta.setColor(org.bukkit.Color.fromRGB(Integer.valueOf(color.substring(1, 3), 16),
                        Integer.valueOf(color.substring(3, 5), 16), Integer.valueOf(color.substring(5, 7), 16)));
                } catch (Exception e) {
                    meta.setColor(org.bukkit.Color.fromRGB(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                }
            }
            item.setItemMeta(meta);
        }

        return item;
    }

    private void giveWeapon(Player player) {
        String materialName = plugin.getConfig().getString("inventory.items.weapon.material", "IRON_SWORD");
        Material material = Material.getMaterial(materialName);
        if (material == null) return;

        ItemStack weapon = new ItemStack(material);

        // Add enchantments
        List<String> enchantments = plugin.getConfig().getStringList("inventory.items.weapon.enchantments");
        for (String ench : enchantments) {
            String[] parts = ench.split(":");
            if (parts.length == 2) {
                Enchantment enchantment = Enchantment.getByName(parts[0]);
                if (enchantment != null) {
                    try {
                        int level = Integer.parseInt(parts[1]);
                        weapon.addEnchantment(enchantment, level);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        player.getInventory().addItem(weapon);
    }

    private void giveFood(Player player) {
        String materialName = plugin.getConfig().getString("inventory.items.food.material", "GOLDEN_APPLE");
        Material material = Material.getMaterial(materialName);
        if (material == null) return;

        int amount = plugin.getConfig().getInt("inventory.items.food.amount", 3);
        ItemStack food = new ItemStack(material, amount);
        player.getInventory().addItem(food);
    }

    private void giveDisableItem(Player player) {
        String materialName = plugin.getConfig().getString("inventory.items.disable-item.material", "BARRIER");
        Material material = Material.getMaterial(materialName);
        if (material == null) return;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("inventory.items.disable-item.name", "&cDisable PvP");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = plugin.getConfig().getStringList("inventory.items.disable-item.lore");
        List<String> coloredLore = new ArrayList<>();
        for (String line : lore) {
            coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        meta.setLore(coloredLore);

        item.setItemMeta(meta);
        player.getInventory().setItem(8, item); // Hotbar slot 8
    }

    private void showStatus(Player player) {
        PlayerData data = PlayerData.get(player);

        // PvP status
        String pvpStatus = data.isPvpEnabled() ? "&aEnabled" : "&cDisabled";
        String pvpMessage = plugin.getConfig().getString("messages.status.pvp", "&6PvP Status: %s");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', pvpMessage.replace("%s", pvpStatus)));

        // Combat status
        String combatStatus = data.isInCombat() ? "&aIn Combat" : "&cNot in Combat";
        String combatMessage = plugin.getConfig().getString("messages.status.combat", "&6Combat Status: %s");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', combatMessage.replace("%s", combatStatus)));

        // Anti-interrupt status
        String antiInterruptStatus = data.isAntiInterruptEnabled() ? "&aEnabled" : "&cDisabled";
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Anti-Interrupt: " + antiInterruptStatus));

        // Fighting with
        String fightingWithName = "None";
        if (data.getFightingWith() != null) {
            Player fightingWith = plugin.getServer().getPlayer(data.getFightingWith());
            fightingWithName = fightingWith != null ? fightingWith.getName() : "Unknown Player";
        }
        String fightingMessage = plugin.getConfig().getString("messages.status.fighting", "&6Fighting With: %s");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', fightingMessage.replace("%s", fightingWithName)));
    }

    private void reloadPlugin(Player player) {
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

            player.sendMessage(ChatColor.GREEN + "LobbyCombat configuration reloaded successfully!");
            plugin.getLogger().info("Configuration reloaded by " + player.getName());

        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Failed to reload configuration: " + e.getMessage());
            plugin.getLogger().severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showColorMenu(Player player) {
        ArmorColorManager colorManager = plugin.getArmorColorManager();
        PlayerData data = PlayerData.get(player);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6=== Armor Color Selection ==="));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Current color: &f" + data.getArmorColor()));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Use: /lobbypvp color set <color>"));

        // Special colors first
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Special Colors:"));

        // Random
        if (colorManager.canUseColor(player, "random")) {
            String status = data.getArmorColor().equals("random") ? "&aSelected" : "&eAvailable";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &f/lobbypvp color set random &7- " + status));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  &7[Random] &cLocked"));
        }

        // Rainbow
        if (colorManager.canUseColor(player, "rainbow")) {
            String status = data.getArmorColor().equals("rainbow") ? "&aSelected" : "&eAvailable";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &f/lobbypvp color set rainbow &7- " + status));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  &7[Rainbow] &cLocked (needs: lobbycombat.colors.rainbow)"));
        }

        // Pastel
        if (colorManager.canUseColor(player, "pastel")) {
            String status = data.getArmorColor().equals("pastel") ? "&aSelected" : "&eAvailable";
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "  &f/lobbypvp color set pastel &7- " + status));
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  &7[Pastel] &cLocked (needs: lobbycombat.colors.pastel)"));
        }

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Standard Colors:"));

        // Regular colors
        for (String colorName : colorManager.getAvailableColors()) {
            if (colorManager.canUseColor(player, colorName)) {
                String status = data.getArmorColor().equals(colorName) ? "&aSelected" : "&eAvailable";
                String displayName = colorName.substring(0, 1).toUpperCase() + colorName.substring(1);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "  &f/lobbypvp color set " + colorName + " &7- &7" + displayName + " " + status));
            } else {
                String displayName = colorName.substring(0, 1).toUpperCase() + colorName.substring(1);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  &7[" + displayName + "] &cLocked"));
            }
        }
    }

    private void setArmorColor(Player player, String colorName) {
        ArmorColorManager colorManager = plugin.getArmorColorManager();

        // Check if the color is valid
        if (!colorManager.isValidColor(colorName)) {
            player.sendMessage(ChatColor.RED + "Invalid color! Use /lobbypvp color to see available colors.");
            return;
        }

        // Check if the color is available (permission)
        if (!colorManager.canUseColor(player, colorName)) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this color!");
            return;
        }

        // Apply the color using the color manager
        colorManager.applyArmorColor(player, colorName);
        player.sendMessage(ChatColor.GREEN + "Armor color changed to " + colorName + "!");
    }

    private void viewFarmLogs(Player player, String targetPlayerName) {
        AntiFarmTracker tracker = plugin.getAntiFarmTracker();
        if (tracker == null || !tracker.isEnabled()) {
            player.sendMessage(ChatColor.RED + "Anti-farm system is not enabled!");
            return;
        }
        // Get player UUID by name (simplified approach - in production you'd want to load by UUID)
        Player targetPlayer = plugin.getServer().getPlayer(targetPlayerName);
        if (targetPlayer == null) {
            player.sendMessage(ChatColor.RED + "Player '" + targetPlayerName + "' not found!");
            return;
        }
        java.util.List<KillEventData> killHistory = tracker.getKillHistory(targetPlayer.getUniqueId());
        if (killHistory == null || killHistory.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No kill history found for " + targetPlayerName);
            return;
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6=== Anti-Farm Logs for " + targetPlayerName + " ==="));
        player.sendMessage(ChatColor.YELLOW + "Total kills recorded: " + killHistory.size());
        player.sendMessage("");
        // Show last 5 kills
        int shown = 0;
        for (int i = killHistory.size() - 1; i >= 0 && shown < 5; i--) {
            KillEventData kill = killHistory.get(i);
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(kill.getTimestamp()));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&7[" + timestamp + "] &a" + kill.getAttackerName() + " &7killed &c" + kill.getVictimName() +
                " &7(Score: " + kill.getSuspicionScore() + "/10)"));
            // Show flags if suspicious
            if (kill.getSuspicionScore() >= 4) {
                for (String flag : kill.getSuspiciousFlags()) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  &e• " + flag));
                }
            }
            shown++;
        }
        if (killHistory.size() > 5) {
            player.sendMessage(ChatColor.GRAY + "... and " + (killHistory.size() - 5) + " more kills. Check logs for full history.");
        }
    }

    /**
     * Shows the latest global anti-farm alerts (recent suspicious kills across all players).
     */
    private void showLatestGlobalAlerts(Player player) {
        AntiFarmTracker tracker = plugin.getAntiFarmTracker();
        if (tracker == null || !tracker.isEnabled()) {
            player.sendMessage(ChatColor.RED + "Anti-farm system is not enabled!");
            return;
        }
        // Gather all kill events from all players
        java.util.List<KillEventData> allKills = tracker.getAllKillEvents();
        if (allKills == null || allKills.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No anti-farm alerts found.");
            return;
        }
        // Sort by timestamp descending
        allKills.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6=== Latest Anti-Farm Alerts ==="));
        int shown = 0;
        for (KillEventData kill : allKills) {
            if (shown >= 10) break;
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(kill.getTimestamp()));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&7[" + timestamp + "] &a" + kill.getAttackerName() + " &7killed &c" + kill.getVictimName() +
                " &7(Score: " + kill.getSuspicionScore() + "/10)"));
            // Show flags if suspicious
            if (kill.getSuspicionScore() >= 4) {
                for (String flag : kill.getSuspiciousFlags()) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "  &e• " + flag));
                }
            }
            shown++;
        }
        if (allKills.size() > 10) {
            player.sendMessage(ChatColor.GRAY + "... and " + (allKills.size() - 10) + " more alerts. Check logs for full history.");
        }
    }
}
