package mc.dragones.lobbycombat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerLifecycleListener implements Listener {

    private final LobbyCombatPlugin plugin;

    public PlayerLifecycleListener(LobbyCombatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        if (plugin.isDebugEnabled()) {
            // DEBUG: Log player quit event
            plugin.getLogger().info("[DEBUG] PlayerQuit Event - Player: " + player.getName() +
                ", PvP Enabled: " + data.isPvpEnabled() +
                ", In Combat: " + data.isInCombat() +
                ", Fighting With: " + (data.getFightingWith() != null ? data.getFightingWith().toString() : "null") +
                ", Coward Enabled: " + plugin.getConfig().getBoolean("pvp.coward.enabled", true));
        }

        boolean cowardEnabled = plugin.getConfig().getBoolean("pvp.coward.enabled", true);

        if (!cowardEnabled) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Coward feature is disabled, skipping coward logic");
            }
            // Still clean up even if coward is disabled
            data.setPvpEnabled(false);
            data.setInCombat(false);
            data.setFightingWith(null);
            PlayerData.remove(player);
            return;
        }

        // Check if currently fighting with another player (don't require PvP to be enabled for coward logic)
        if (data.getFightingWith() != null) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Player " + player.getName() + " was fighting, processing coward death...");
            }

            // Get opponent first to get their name, BEFORE any saves
            PlayerData opponentData = PlayerData.get(data.getFightingWith());
            String opponentName = opponentData.getUuid().toString(); // Fallback if player not found
            Player opponentPlayer = null;
            try {
                opponentPlayer = plugin.getServer().getPlayer(opponentData.getUuid());
                if (opponentPlayer != null) {
                    opponentName = opponentPlayer.getName();
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Found opponent player online: " + opponentName);
                    }
                } else {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Opponent not online, using UUID: " + opponentName);
                    }
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

            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Set coward death message for player: " + cowardDeathMessage);
                plugin.getLogger().info("[DEBUG] About to increment deaths with coward message already set");
            }

            // Coward death - player disconnected while fighting
            data.incrementDeaths();
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Incremented deaths for " + player.getName());
            }

            // Grant kill to opponent
            if (data.getFightingWith() != null) {
                opponentData.incrementKills();
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("[DEBUG] Incremented kills for opponent UUID: " + data.getFightingWith());
                }

                opponentData.setFightingWith(null);
                opponentData.setInCombat(false);

                // Messages for the opponent (send immediately if online, or on rejoin if offline)
                // Send immediately if opponent is online
                if (opponentPlayer != null && opponentPlayer.isOnline()) {
                    opponentPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', opponentWinMessage));
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Sent coward win message immediately to " + opponentName);
                    }
                } else {
                    // Already stored above
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Set coward win message for opponent (will be sent on rejoin)");
                    }
                }

                // Messages for the coward player (the one who disconnected) - sent when they rejoin
                if (plugin.isDebugEnabled()) {
                    plugin.getLogger().info("[DEBUG] About to remove and save player data for: " + player.getName() + " with deathMsg: " + cowardDeathMessage);
                }

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
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Notified " + notifiedCount + " players about coward disconnect");
                    }
                }

                // Run coward command
                String cowardCommand = plugin.getConfig().getString("pvp.coward.command");
                if (cowardCommand != null && !cowardCommand.trim().isEmpty()) {
                    String cmd = cowardCommand.replace("%p", player.getName()).replace("%o", opponentName);
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Executing coward command: " + cmd);
                    }
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to execute coward command '" + cmd + "': " + e.getMessage());
                        if (plugin.isDebugEnabled()) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] No coward command configured");
                    }
                }
            }
        } else {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Player " + player.getName() + " was not fighting, no coward logic applied");
            }
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
        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] Saving player data and removing from memory");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = PlayerData.get(player);

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] PlayerJoin Event - Player: " + player.getName() +
                ", Coward Death Message: " + (data.getCowardDeathMessage() != null ? "present" : "null") +
                ", Coward Win Message: " + (data.getCowardWinMessage() != null ? "present" : "null"));
        }

        // Send coward death message to the player who disconnected
        if (data.getCowardDeathMessage() != null) {
            final String deathMessage = data.getCowardDeathMessage();
            final Player finalPlayer = player;
            int delaySeconds = plugin.getConfig().getInt("pvp.coward.message-delay", 2);

            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Scheduling coward death message for " + player.getName() + " in " + delaySeconds + " seconds");
            }

            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (finalPlayer.isOnline()) {
                    finalPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', deathMessage));
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Sent coward death message to " + finalPlayer.getName());
                    }
                } else {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Player " + finalPlayer.getName() + " went offline before message was sent");
                    }
                }
            }, delaySeconds * 20L);

            data.setCowardDeathMessage(null);
        }


        // Send coward win message to the opponent who got the kill
        if (data.getCowardWinMessage() != null) {
            final String winMessage = data.getCowardWinMessage();
            final Player finalPlayer = player;
            int delaySeconds = plugin.getConfig().getInt("pvp.coward.message-delay", 2);

            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Scheduling coward win message for " + player.getName() + " in " + delaySeconds + " seconds");
            }

            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                if (finalPlayer.isOnline()) {
                    finalPlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', winMessage));
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Sent coward win message to " + finalPlayer.getName());
                    }
                } else {
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Player " + finalPlayer.getName() + " went offline before win message was sent");
                    }
                }
            }, delaySeconds * 20L);

            data.setCowardWinMessage(null);
        }
    }
}
