package mc.dragones.lobbycombat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;

import java.util.List;

public class CombatListener implements Listener {

    private final LobbyCombatPlugin plugin;

    public CombatListener(LobbyCombatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();

        PlayerData attackerData = PlayerData.get(attacker);
        PlayerData victimData = PlayerData.get(victim);

        plugin.getLogger().info("[DEBUG] Damage Event - Attacker: " + attacker.getName() + " -> Victim: " + victim.getName() +
            ", Attacker PvP: " + attackerData.isPvpEnabled() + ", Victim PvP: " + victimData.isPvpEnabled());

        // Check if both have PvP enabled
        if (!attackerData.isPvpEnabled() || !victimData.isPvpEnabled()) {
            // Check cooldown to prevent message spam
            int messageCooldown = plugin.getConfig().getInt("pvp.disable.message-cooldown", 5);
            if (!attackerData.canSendPvpDisabledMessage(messageCooldown)) {
                // Cooldown not expired, silently cancel the attack
                event.setCancelled(true);
                return;
            }

            // Different messages for attacker vs victim PvP disabled
            String message;
            if (!attackerData.isPvpEnabled() && !victimData.isPvpEnabled()) {
                message = plugin.getConfig().getString("messages.pvp-disabled-both", "&cBoth players have PvP disabled!");
            } else if (!attackerData.isPvpEnabled()) {
                message = plugin.getConfig().getString("messages.pvp-disabled-attacker", "&cYou have PvP disabled!");
            } else {
                message = plugin.getConfig().getString("messages.pvp-disabled-victim", "&cThis player has PvP disabled!");
            }
            attacker.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            attacker.playSound(attacker.getLocation(), plugin.getSound("pvp-not-enabled"), 1.0f, 1.0f);

            event.setCancelled(true);
            return;
        }

        // Check anti-interrupt
        if (attackerData.isAntiInterruptEnabled() || victimData.isAntiInterruptEnabled()) {
            if (victimData.getFightingWith() != null && !victimData.getFightingWith().equals(attacker.getUniqueId())) {
                // Victim is fighting someone else
                String message = plugin.getConfig().getString("actionbar.already-fighting", "&cPlayer is already fighting!");
                attacker.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                attacker.playSound(attacker.getLocation(), plugin.getSound("already-fighting"), 1.0f, 1.0f);

                event.setCancelled(true);
                return;
            }

            if (attackerData.getFightingWith() != null && !attackerData.getFightingWith().equals(victim.getUniqueId())) {
                // Attacker is fighting someone else
                String message = plugin.getConfig().getString("actionbar.already-fighting", "&cYou are already fighting!");
                attacker.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                attacker.playSound(attacker.getLocation(), plugin.getSound("already-fighting"), 1.0f, 1.0f);

                event.setCancelled(true);
                return;
            }
        }

        // Set fighting status
        attackerData.setFightingWith(victim.getUniqueId());
        victimData.setFightingWith(attacker.getUniqueId());

        // Set in combat
        attackerData.setInCombat(true);
        victimData.setInCombat(true);

        // Update last damage time
        attackerData.setLastDamageTime(System.currentTimeMillis());
        victimData.setLastDamageTime(System.currentTimeMillis());

        // Add attacker to victim's recent attackers for assist tracking
        victimData.addRecentAttacker(attacker.getUniqueId());
        // Track hits per attacker
        victimData.addHitAgainstAttacker(attacker.getUniqueId());

        plugin.getLogger().info("[DEBUG] Fight started - " + attacker.getName() + " vs " + victim.getName() +
            ", Attacker UUID: " + attacker.getUniqueId() + ", Victim UUID: " + victim.getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        PlayerData victimData = PlayerData.get(victim);

        if (!victimData.isPvpEnabled()) return;

        // Check if killed by player
        Player killer = victim.getKiller();
        if (killer != null) {
            PlayerData killerData = PlayerData.get(killer);
            killerData.incrementKills();
            killerData.incrementKillStreak();

            // Check if kill streak reached the threshold
            int killStreakThreshold = plugin.getConfig().getInt("pvp.kill-streak.threshold", 10);
            if (killerData.getCurrentKillStreak() == killStreakThreshold) {
                String broadcastMessage = plugin.getConfig().getString("pvp.kill-streak.broadcast-message",
                    "&6%p has reached a %k kill streak!");
                broadcastMessage = broadcastMessage.replace("%p", killer.getName())
                                                   .replace("%k", String.valueOf(killStreakThreshold));
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', broadcastMessage));

                // Play sound for all players
                for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                    onlinePlayer.playSound(onlinePlayer.getLocation(), plugin.getSound("kill-streak"), 1.0f, 1.0f);
                }
            }

            // Check if victim's kill streak was broken
            int brokenStreakThreshold = plugin.getConfig().getInt("pvp.kill-streak.break-threshold", 3);
            if (victimData.getCurrentKillStreak() >= brokenStreakThreshold) {
                String breakMessage = plugin.getConfig().getString("pvp.kill-streak.break-message",
                    "&c%p's %k kill streak was broken by %q!");
                breakMessage = breakMessage.replace("%p", victim.getName())
                                          .replace("%q", killer.getName())
                                          .replace("%k", String.valueOf(victimData.getCurrentKillStreak()));
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', breakMessage));

                // Play sound for all players
                for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                    onlinePlayer.playSound(onlinePlayer.getLocation(), plugin.getSound("kill-streak-break"), 1.0f, 1.0f);
                }
            }

            // Run kill commands
            runKillCommands(killer, victim);

            // Reset killer's combat status since the fight is over
            killerData.setFightingWith(null);
            killerData.setInCombat(false);

            // Give assists to all recent attackers except the killer
            for (java.util.UUID attackerId : victimData.getRecentAttackers()) {
                if (!attackerId.equals(killer.getUniqueId())) {
                    PlayerData attackerData = PlayerData.get(attackerId);
                    attackerData.incrementAssists();
                    plugin.getLogger().info("[DEBUG] Gave assist to " + attackerId + " for killing " + victim.getName());

                    // Calculate assist contribution percentage
                    int totalHits = 0;
                    for (java.util.UUID id : victimData.getRecentAttackers()) {
                        totalHits += victimData.getHitsAgainstAttacker(id);
                    }

                    int attackerHits = victimData.getHitsAgainstAttacker(attackerId);
                    double assistPercentage = totalHits > 0 ? (double) attackerHits / totalHits * 100 : 0;

                    // Send assist message to attacker if online
                    Player assistPlayer = plugin.getServer().getPlayer(attackerId);
                    if (assistPlayer != null && assistPlayer.isOnline()) {
                        String assistMessage = plugin.getConfig().getString("messages.assist-message",
                            "&aYou got an assist! (%a% contribution with %h% hits on %p%)");
                        assistMessage = assistMessage.replace("%p", victim.getName())
                                                    .replace("%a", String.format("%.1f", assistPercentage))
                                                    .replace("%h", String.valueOf(attackerHits));
                        assistPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', assistMessage));
                        plugin.getLogger().info("[DEBUG] Sent assist message to " + assistPlayer.getName() +
                            " - " + attackerHits + " hits, " + String.format("%.1f", assistPercentage) + "%");
                    }

                    // Reset their combat status
                    attackerData.setFightingWith(null);
                    attackerData.setInCombat(false);
                }
            }

            // Clear recent attackers and hits after death
            victimData.clearRecentAttackers();
            victimData.clearHits();
        }

        // Reset kill streak and PvP status on death
        victimData.resetKillStreak();
        victimData.setPvpEnabled(false);
        victimData.incrementDeaths();
        victimData.setFightingWith(null);
        victimData.setInCombat(false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        // DEBUG: Log player quit event
        plugin.getLogger().info("[DEBUG] PlayerQuit Event - Player: " + player.getName() + 
            ", PvP Enabled: " + data.isPvpEnabled() + 
            ", In Combat: " + data.isInCombat() + 
            ", Fighting With: " + (data.getFightingWith() != null ? data.getFightingWith().toString() : "null") +
            ", Coward Enabled: " + plugin.getConfig().getBoolean("pvp.coward.enabled", true));

        boolean cowardEnabled = plugin.getConfig().getBoolean("pvp.coward.enabled", true);
        
        if (!cowardEnabled) {
            plugin.getLogger().info("[DEBUG] Coward feature is disabled, skipping coward logic");
            // Still clean up even if coward is disabled
            data.setPvpEnabled(false);
            data.setInCombat(false);
            data.setFightingWith(null);
            PlayerData.remove(player);
            return;
        }

        // Check if currently fighting with another player (don't require PvP to be enabled for coward logic)
        if (data.getFightingWith() != null) {
            plugin.getLogger().info("[DEBUG] Player " + player.getName() + " was fighting, processing coward death...");
            
            // Get opponent first to get their name, BEFORE any saves
            PlayerData opponentData = PlayerData.get(data.getFightingWith());
            String opponentName = opponentData.getUuid().toString(); // Fallback if player not found
            Player opponentPlayer = null;
            try {
                opponentPlayer = plugin.getServer().getPlayer(opponentData.getUuid());
                if (opponentPlayer != null) {
                    opponentName = opponentPlayer.getName();
                    plugin.getLogger().info("[DEBUG] Found opponent player online: " + opponentName);
                } else {
                    plugin.getLogger().info("[DEBUG] Opponent not online, using UUID: " + opponentName);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[DEBUG] Exception getting opponent: " + e.getMessage());
            }

            // Set coward messages BEFORE incrementing deaths (which triggers a save)
            String opponentWinMessage = plugin.getConfig().getString("pvp.coward.win-message", "&a%p disconnected from your fight and you were awarded the kill!")
                .replace("%p", player.getName());
            String cowardDeathMessage = plugin.getConfig().getString("pvp.coward.death-message", "&cYou disconnected from a fight with %o and were marked as dead!")
                .replace("%o", opponentName);

            data.setCowardDeathMessage(cowardDeathMessage);
            data.setCowardOpponentName(opponentName);
            opponentData.setCowardWinMessage(opponentWinMessage);

            plugin.getLogger().info("[DEBUG] Set coward death message for player: " + cowardDeathMessage);
            plugin.getLogger().info("[DEBUG] About to increment deaths with coward message already set");

            // Coward death - player disconnected while fighting
            data.incrementDeaths();
            plugin.getLogger().info("[DEBUG] Incremented deaths for " + player.getName());

            // Grant kill to opponent
            if (data.getFightingWith() != null) {
                opponentData.incrementKills();
                plugin.getLogger().info("[DEBUG] Incremented kills for opponent UUID: " + data.getFightingWith());

                opponentData.setFightingWith(null);
                opponentData.setInCombat(false);

                // Messages for the opponent (send immediately if online, or on rejoin if offline)
                // Send immediately if opponent is online
                if (opponentPlayer != null && opponentPlayer.isOnline()) {
                    opponentPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', opponentWinMessage));
                    plugin.getLogger().info("[DEBUG] Sent coward win message immediately to " + opponentName);
                } else {
                    // Already stored above
                    plugin.getLogger().info("[DEBUG] Set coward win message for opponent (will be sent on rejoin)");
                }

                // Messages for the coward player (the one who disconnected) - sent when they rejoin
                plugin.getLogger().info("[DEBUG] About to remove and save player data for: " + player.getName() + " with deathMsg: " + cowardDeathMessage);

                // Logging and notifications
                if (plugin.getConfig().getBoolean("pvp.coward.logging", true)) {
                    String logMessage = String.format("[LobbyCombat] Coward disconnect: %s disconnected while fighting %s",
                        player.getName(), opponentName);
                    plugin.getLogger().info(logMessage);

                    // Notify players with permission
                    String notifyPermission = plugin.getConfig().getString("pvp.coward.notify-permission", "lobbycombat.coward.notify");
                    String notifyMessage = ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("messages.coward-notification",
                            "&c[LobbyCombat] %p disconnected while fighting %o (coward death!)")
                            .replace("%p", player.getName())
                            .replace("%o", opponentName));

                    int notifiedCount = 0;
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        if (online.hasPermission(notifyPermission)) {
                            PlayerData notifyData = PlayerData.get(online);
                            if (notifyData.isAlertsEnabled()) {
                                online.sendMessage(notifyMessage);
                                notifiedCount++;
                            }
                        }
                    }
                    plugin.getLogger().info("[DEBUG] Notified " + notifiedCount + " players about coward disconnect");
                }

                // Run coward command
                String cowardCommand = plugin.getConfig().getString("pvp.coward.command");
                if (cowardCommand != null && !cowardCommand.trim().isEmpty()) {
                    String cmd = cowardCommand.replace("%p", player.getName()).replace("%o", opponentName);
                    plugin.getLogger().info("[DEBUG] Executing coward command: " + cmd);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } else {
                    plugin.getLogger().info("[DEBUG] No coward command configured");
                }
            }
        } else {
            plugin.getLogger().info("[DEBUG] Player " + player.getName() + " was not fighting, no coward logic applied");
        }

        // Clean up
        data.setPvpEnabled(false);
        data.setInCombat(false);
        data.setFightingWith(null);
        data.clearRecentAttackers();  // Clear recent attackers when disabling PvP
        data.clearHits();  // Clear hits when disabling PvP

        // Clean up rainbow tasks
        plugin.getArmorColorManager().onPlayerQuit(player);

        // Save data to database before player leaves
        plugin.getLogger().info("[DEBUG] Saving player data and removing from memory");
        PlayerData.remove(player);
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
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        plugin.getLogger().info("[DEBUG] PlayerJoin Event - Player: " + player.getName() +
            ", Coward Death Message: " + (data.getCowardDeathMessage() != null ? "present" : "null") +
            ", Coward Win Message: " + (data.getCowardWinMessage() != null ? "present" : "null"));

        // Send coward death message to the player who disconnected
        if (data.getCowardDeathMessage() != null) {
            final String deathMessage = data.getCowardDeathMessage();
            final Player finalPlayer = player;
            int delaySeconds = plugin.getConfig().getInt("pvp.coward.message-delay", 2);

            plugin.getLogger().info("[DEBUG] Scheduling coward death message for " + player.getName() + " in " + delaySeconds + " seconds");

            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (finalPlayer.isOnline()) {
                    finalPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', deathMessage));
                    plugin.getLogger().info("[DEBUG] Sent coward death message to " + finalPlayer.getName());
                } else {
                    plugin.getLogger().info("[DEBUG] Player " + finalPlayer.getName() + " went offline before message was sent");
                }
            }, delaySeconds * 20L);

            data.setCowardDeathMessage(null);
        }


        // Send coward win message to the opponent who got the kill
        if (data.getCowardWinMessage() != null) {
            final String winMessage = data.getCowardWinMessage();
            final Player finalPlayer = player;
            int delaySeconds = plugin.getConfig().getInt("pvp.coward.message-delay", 2);
            
            plugin.getLogger().info("[DEBUG] Scheduling coward win message for " + player.getName() + " in " + delaySeconds + " seconds");
            
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (finalPlayer.isOnline()) {
                    finalPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', winMessage));
                    plugin.getLogger().info("[DEBUG] Sent coward win message to " + finalPlayer.getName());
                } else {
                    plugin.getLogger().info("[DEBUG] Player " + finalPlayer.getName() + " went offline before win message was sent");
                }
            }, delaySeconds * 20L);
            
            data.setCowardWinMessage(null);
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
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
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
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } else {
            player.getInventory().clear();
            // Also clear armor slots
            player.getInventory().setArmorContents(null);
        }
    }

    private void runKillCommands(Player killer, Player victim) {
        List<String> killCommands = plugin.getConfig().getStringList("pvp.kill-commands");
        for (String commandEntry : killCommands) {
            if (commandEntry == null || commandEntry.trim().isEmpty()) continue;

            String[] parts = commandEntry.split(":", 2);
            if (parts.length != 2) continue;

            String executor = parts[0].toLowerCase().trim();
            String command = parts[1].trim();

            // Replace placeholders
            command = command.replace("%k", killer.getName()).replace("%v", victim.getName());

            if ("console".equals(executor)) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } else if ("player".equals(executor)) {
                killer.performCommand(command);
            }
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
