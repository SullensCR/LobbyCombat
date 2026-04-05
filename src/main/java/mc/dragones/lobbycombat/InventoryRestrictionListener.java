package mc.dragones.lobbycombat;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;

public class InventoryRestrictionListener implements Listener {

    private final LobbyCombatPlugin plugin;

    public InventoryRestrictionListener(LobbyCombatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        if (!data.isPvpEnabled()) return;

        // Check if it's the disable item
        if (event.getItem() != null && event.getItem().hasItemMeta()) {
            String name = plugin.getConfig().getString("inventory.items.disable-item.name", "&cDisable PvP");
            if (ChatColor.stripColor(event.getItem().getItemMeta().getDisplayName())
                .equals(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', name)))) {

                // Cancel all vanilla mechanics
                event.setCancelled(true);

                // Disable PvP using the same logic as the command
                disablePvp(player, data);
            }
        }

        // Cancel interactions with blocks, NPCs, mobs when in PvP
        if (event.getClickedBlock() != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        if (data.isPvpEnabled() && plugin.getConfig().getBoolean("pvp.entity-interaction.prevent-while-pvp", true)) {
            // Cancel interactions with entities (like NPCs, mobs) when PvP is enabled
            event.setCancelled(true);

            // Send customizable message
            String message = plugin.getConfig().getString("pvp.entity-interaction.message",
                "&cYou cannot interact with entities while PvP is enabled! Disable PvP first.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        PlayerData data = PlayerData.get(player);

        if (data.isPvpEnabled()) {
            // Cancel inventory click events to prevent item changes during PvP
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        PlayerData data = PlayerData.get(player);

        if (data.isPvpEnabled()) {
            // Cancel inventory drag events to prevent item changes during PvP
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        if (data.isPvpEnabled()) {
            // Cancel item drop events to prevent item changes during PvP
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        if (data.isPvpEnabled()) {
            // Cancel item pickup events to prevent item changes during PvP
            event.setCancelled(true);
        }
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

    private void performDisable(Player player, PlayerData data) {
        // Cancel any running disable countdown
        if (data.getDisableCountdownTask() != null) {
            data.getDisableCountdownTask().cancel();
            data.setDisableCountdownTask(null);
        }

        data.setPvpEnabled(false);
        data.setInCombat(false);
        data.setFightingWith(null);
        data.clearRecentAttackers();  // Clear recent attackers when disabling PvP
        data.clearHits();  // Clear hits when disabling PvP

        // Clear inventory
        clearInventory(player);

        // Run disable command if configured
        String disableCommand = plugin.getConfig().getString("pvp.disable.command");
        if (disableCommand != null && !disableCommand.trim().isEmpty()) {
            String cmd = disableCommand.replace("%p", player.getName());
            try {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to execute disable command '" + cmd + "': " + e.getMessage());
                if (plugin.isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        }

        // Play sound
        player.playSound(player.getLocation(), plugin.getSound("pvp-disabled"), 1.0f, 1.0f);

        // Send message
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            plugin.getConfig().getString("messages.pvp-disabled", "&cPvP disabled!")));
    }

    private void clearInventory(Player player) {
        String method = plugin.getConfig().getString("inventory.clear-method", "plugin");

        if ("command".equals(method)) {
            String cmd = plugin.getConfig().getString("inventory.clear-command", "clear %p")
                .replace("%p", player.getName());
            try {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to execute clear inventory command '" + cmd + "': " + e.getMessage());
                if (plugin.isDebugEnabled()) {
                    e.printStackTrace();
                }
                // Fallback to plugin method if command fails
                player.getInventory().clear();
                player.getInventory().setArmorContents(null);
            }
        } else {
            player.getInventory().clear();
            // Also clear armor slots
            player.getInventory().setArmorContents(null);
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
}
