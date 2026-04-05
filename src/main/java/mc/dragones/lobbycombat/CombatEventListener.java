package mc.dragones.lobbycombat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.List;

public class CombatEventListener implements Listener {

    private final LobbyCombatPlugin plugin;

    public CombatEventListener(LobbyCombatPlugin plugin) {
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

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] Damage Event - Attacker: " + attacker.getName() + " -> Victim: " + victim.getName() +
                ", Attacker PvP: " + attackerData.isPvpEnabled() + ", Victim PvP: " + victimData.isPvpEnabled());
        }

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
        // Anti-farm tracking: Record combat start time if not already set
        if (victimData.getCombatStartTime() == 0) {
            victimData.setCombatStartTime(System.currentTimeMillis());
            victimData.setLastRecordedLocation(victim.getLocation().clone());
        }
        if (attackerData.getCombatStartTime() == 0) {
            attackerData.setCombatStartTime(System.currentTimeMillis());
            attackerData.setLastRecordedLocation(attacker.getLocation().clone());
        }

        // Anti-farm tracking: Attacker dealt damage (record it)
        attackerData.setDamageDealtInCombat((int)(attackerData.getDamageDealtInCombat() + event.getDamage()));

        // Add attacker to victim's recent attackers for assist tracking
        victimData.addRecentAttacker(attacker.getUniqueId());
        // Track hits per attacker
        victimData.addHitAgainstAttacker(attacker.getUniqueId());

        if (plugin.isDebugEnabled()) {
            plugin.getLogger().info("[DEBUG] Fight started - " + attacker.getName() + " vs " + victim.getName() +
                ", Attacker UUID: " + attacker.getUniqueId() + ", Victim UUID: " + victim.getUniqueId());
        }
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

            // Anti-farm tracking
            trackKillEventForAntiFarm(killer, victim, killerData, victimData);
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
                    if (plugin.isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Gave assist to " + attackerId + " for killing " + victim.getName());
                    }

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
                        if (plugin.isDebugEnabled()) {
                            plugin.getLogger().info("[DEBUG] Sent assist message to " + assistPlayer.getName() +
                                " - " + attackerHits + " hits, " + String.format("%.1f", assistPercentage) + "%");
                        }
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

            try {
                if ("console".equals(executor)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } else if ("player".equals(executor)) {
                    killer.performCommand(command);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to execute kill command '" + command + "' as " + executor + ": " + e.getMessage());
                if (plugin.isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Track kill event for anti-farm detection
     */
    private void trackKillEventForAntiFarm(Player killer, Player victim, PlayerData killerData, PlayerData victimData) {
        AntiFarmTracker antiFarmTracker = plugin.getAntiFarmTracker();
        if (antiFarmTracker == null || !antiFarmTracker.isEnabled()) {
            return;
        }

        // Create kill event data
        KillEventData killData = new KillEventData(
            killer.getUniqueId(), killer.getName(),
            victim.getUniqueId(), victim.getName(),
            victim.getLocation()
        );

        // Populate movement data from victim
        killData.setVictimMovementDistance(victimData.getMovementDistance());
        killData.setVictimSprinted(victimData.hasSprinted());
        killData.setVictimDealtDamage(victimData.getDamageDealtInCombat() > 0);
        killData.setVictimDamageDealt(victimData.getDamageDealtInCombat());

        // Calculate combat duration
        long combatDuration = System.currentTimeMillis() - victimData.getCombatStartTime();
        killData.setCombatDuration(combatDuration);

        // Get other attackers from recent attackers list
        for (java.util.UUID attackerId : victimData.getRecentAttackers()) {
            if (!attackerId.equals(killer.getUniqueId())) {
                killData.addOtherAttacker(attackerId);
            }
        }
        killData.setTotalAttackers(victimData.getRecentAttackers().size());

        // Process the kill event
        antiFarmTracker.processKillEvent(killData);

        // Reset victim's anti-farm tracking data
        victimData.setMovementDistance(0);
        victimData.setHasSprinted(false);
        victimData.setDamageDealtInCombat(0);
        victimData.setCombatStartTime(0);
        victimData.setLastRecordedLocation(null);
    }
}
