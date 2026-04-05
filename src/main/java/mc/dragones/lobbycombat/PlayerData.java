package mc.dragones.lobbycombat;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerData {

    private static final Map<UUID, PlayerData> playerData = new HashMap<>();
    private static DatabaseManager databaseManager;

    private final UUID uuid;
    private boolean pvpEnabled = false;
    private boolean inCombat = false;
    private long lastDamageTime = 0;
    private UUID fightingWith = null;
    private int kills = 0;
    private int deaths = 0;
    private int assists = 0;
    private int currentKillStreak = 0;  // Current consecutive kills
    private int longestKillStreak = 0;  // Longest win streak record
    private String armorColor = "random";  // Selected armor color preference
    private String cowardDeathMessage = null;  // Message for the player who disconnected
    private String cowardWinMessage = null;    // Message for the opponent who got the kill
    private String cowardOpponentName = null;  // Store opponent name for coward messages
    private org.bukkit.scheduler.BukkitTask disableCountdownTask = null;
    private boolean alertsEnabled = true;  // Staff alerts preference (default: enabled)
    private boolean antiInterruptEnabled = false;  // Anti-interrupt preference (default: disabled)
    private java.util.Set<java.util.UUID> recentAttackers = new java.util.HashSet<>();  // Track recent attackers for assists
    private java.util.Map<java.util.UUID, Integer> recentHits = new java.util.HashMap<>();  // Track hits per attacker
    private long pvpDisabledMessageCooldown = 0;  // Cooldown for pvp-disabled messages to prevent spam

    // Anti-farm tracking
    private long combatStartTime = 0;  // Timestamp when combat started
    private org.bukkit.Location lastRecordedLocation = null;  // Last recorded position for movement tracking
    private double movementDistance = 0;  // Total distance moved during current combat
    private boolean hasSprinted = false;  // Did player sprint during current combat
    private int damageDealtInCombat = 0;  // Damage dealt to opponent during current combat
    private long lastMoveTime = 0;  // Last time player moved (for variance calculation)

    // Package-private constructor for use within the package
    PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public static void setDatabaseManager(DatabaseManager dbManager) {
        databaseManager = dbManager;
    }

    public static PlayerData get(Player player) {
        return get(player.getUniqueId());
    }

    public static PlayerData get(UUID uuid) {
        PlayerData data = playerData.get(uuid);
        if (data == null && databaseManager != null) {
            // Load from database
            data = databaseManager.loadPlayerData(uuid);
            playerData.put(uuid, data);
        } else if (data == null) {
            // Create new if no database
            data = new PlayerData(uuid);
            playerData.put(uuid, data);
        }
        return data;
    }

    public static void remove(Player player) {
        PlayerData data = playerData.remove(player.getUniqueId());
        if (data != null) {
            if (databaseManager != null) {
                // Save to database before removing
                databaseManager.savePlayerData(data.uuid, data.kills, data.deaths, data.assists, data.cowardDeathMessage, data.cowardWinMessage);
            }
        }
    }

    public static Map<UUID, PlayerData> getAllPlayerData() {
        return new HashMap<>(playerData);
    }

    public static void saveAllToDatabase() {
        if (databaseManager != null) {
            databaseManager.saveAllPlayerData();
        }
    }

    // Save this player's data to database
    public void save() {
        if (databaseManager != null) {
            databaseManager.savePlayerData(uuid, kills, deaths, assists, cowardDeathMessage, cowardWinMessage);
        }
    }

    // Getters and setters
    public UUID getUuid() {
        return uuid;
    }

    public boolean isPvpEnabled() {
        return pvpEnabled;
    }

    public void setPvpEnabled(boolean pvpEnabled) {
        this.pvpEnabled = pvpEnabled;
    }

    public boolean isInCombat() {
        return inCombat;
    }

    public void setInCombat(boolean inCombat) {
        this.inCombat = inCombat;
    }

    public long getLastDamageTime() {
        return lastDamageTime;
    }

    public void setLastDamageTime(long lastDamageTime) {
        this.lastDamageTime = lastDamageTime;
    }

    public UUID getFightingWith() {
        return fightingWith;
    }

    public void setFightingWith(UUID fightingWith) {
        this.fightingWith = fightingWith;
    }

    public int getKills() {
        return kills;
    }

    public void setKills(int kills) {
        this.kills = kills;
    }

    public void incrementKills() {
        this.kills++;
        save();
    }

    public int getDeaths() {
        return deaths;
    }

    public void setDeaths(int deaths) {
        this.deaths = deaths;
    }

    public void incrementDeaths() {
        this.deaths++;
        save();
    }

    public int getAssists() {
        return assists;
    }

    public void setAssists(int assists) {
        this.assists = assists;
    }

    public void incrementAssists() {
        this.assists++;
        save();
    }

    public int getCurrentKillStreak() {
        return currentKillStreak;
    }

    public void setCurrentKillStreak(int currentKillStreak) {
        this.currentKillStreak = currentKillStreak;
    }

    public void incrementKillStreak() {
        this.currentKillStreak++;
        // Update longest kill streak if needed
        if (currentKillStreak > longestKillStreak) {
            longestKillStreak = currentKillStreak;
        }
        save();
    }

    public void resetKillStreak() {
        this.currentKillStreak = 0;
    }

    public int getLongestKillStreak() {
        return longestKillStreak;
    }

    public void setLongestKillStreak(int longestKillStreak) {
        this.longestKillStreak = longestKillStreak;
    }

    public double getKDRatio() {
        if (deaths == 0) return kills;
        return (double) kills / deaths;
    }


    public String getCowardDeathMessage() {
        return cowardDeathMessage;
    }

    public void setCowardDeathMessage(String cowardDeathMessage) {
        this.cowardDeathMessage = cowardDeathMessage;
    }

    public String getCowardWinMessage() {
        return cowardWinMessage;
    }

    public void setCowardWinMessage(String cowardWinMessage) {
        this.cowardWinMessage = cowardWinMessage;
    }

    public String getCowardOpponentName() {
        return cowardOpponentName;
    }

    public void setCowardOpponentName(String cowardOpponentName) {
        this.cowardOpponentName = cowardOpponentName;
    }

    public org.bukkit.scheduler.BukkitTask getDisableCountdownTask() {
        return disableCountdownTask;
    }

    public void setDisableCountdownTask(org.bukkit.scheduler.BukkitTask disableCountdownTask) {
        this.disableCountdownTask = disableCountdownTask;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public void setAlertsEnabled(boolean alertsEnabled) {
        this.alertsEnabled = alertsEnabled;
    }

    public boolean isAntiInterruptEnabled() {
        return antiInterruptEnabled;
    }

    public void setAntiInterruptEnabled(boolean antiInterruptEnabled) {
        this.antiInterruptEnabled = antiInterruptEnabled;
    }

    public java.util.Set<java.util.UUID> getRecentAttackers() {
        return recentAttackers;
    }

    public void addRecentAttacker(UUID attackerId) {
        recentAttackers.add(attackerId);
    }

    public void clearRecentAttackers() {
        recentAttackers.clear();
    }

    public int getHitsAgainstAttacker(UUID attackerId) {
        return recentHits.getOrDefault(attackerId, 0);
    }

    public void addHitAgainstAttacker(UUID attackerId) {
        recentHits.put(attackerId, getHitsAgainstAttacker(attackerId) + 1);
    }

    public void clearHits() {
        recentHits.clear();
    }

    public boolean canSendPvpDisabledMessage(int cooldownSeconds) {
        long now = System.currentTimeMillis();
        if (now - pvpDisabledMessageCooldown >= cooldownSeconds * 1000L) {
            pvpDisabledMessageCooldown = now;
            return true;
        }
        return false;
    }

    public String getArmorColor() {
        return armorColor;
    }

    public void setArmorColor(String armorColor) {
        this.armorColor = armorColor;
    }

    // Anti-farm tracking methods
    public long getCombatStartTime() {
        return combatStartTime;
    }

    public void setCombatStartTime(long combatStartTime) {
        this.combatStartTime = combatStartTime;
    }

    public org.bukkit.Location getLastRecordedLocation() {
        return lastRecordedLocation;
    }

    public void setLastRecordedLocation(org.bukkit.Location lastRecordedLocation) {
        this.lastRecordedLocation = lastRecordedLocation;
    }

    public double getMovementDistance() {
        return movementDistance;
    }

    public void setMovementDistance(double movementDistance) {
        this.movementDistance = movementDistance;
    }

    public boolean hasSprinted() {
        return hasSprinted;
    }

    public void setHasSprinted(boolean hasSprinted) {
        this.hasSprinted = hasSprinted;
    }

    public int getDamageDealtInCombat() {
        return damageDealtInCombat;
    }

    public void setDamageDealtInCombat(int damageDealtInCombat) {
        this.damageDealtInCombat = damageDealtInCombat;
    }

    public long getLastMoveTime() {
        return lastMoveTime;
    }

    public void setLastMoveTime(long lastMoveTime) {
        this.lastMoveTime = lastMoveTime;
    }
}
