package mc.dragones.lobbycombat;

import org.bukkit.Location;
import java.util.*;

/**
 * Stores detailed information about a single kill event for anti-farm analysis
 */
public class KillEventData {

    private final UUID attackerId;
    private final String attackerName;
    private final UUID victimId;
    private final String victimName;
    private final long timestamp;
    private final Location killLocation;

    // Victim behavior tracking
    private double victimMovementDistance = 0;
    private double victimMovementVariance = 0;
    private boolean victimSprinted = false;
    private boolean victimDealtDamage = false;
    private int victimDamageDealt = 0;
    private long combatDuration = 0;

    // Interaction tracking
    private Set<UUID> otherAttackers = new HashSet<>();
    private int totalAttackers = 1;

    // Movement path tracking
    private List<Location> victimMovementPath = new ArrayList<>();

    // Calculated fields
    private int suspicionScore = 0;
    private List<String> suspiciousFlags = new ArrayList<>();

    public KillEventData(UUID attackerId, String attackerName, UUID victimId, String victimName, Location killLocation) {
        this.attackerId = attackerId;
        this.attackerName = attackerName;
        this.victimId = victimId;
        this.victimName = victimName;
        this.timestamp = System.currentTimeMillis();
        this.killLocation = killLocation != null ? killLocation.clone() : null;
    }

    // Getters
    public UUID getAttackerId() {
        return attackerId;
    }

    public String getAttackerName() {
        return attackerName;
    }

    public UUID getVictimId() {
        return victimId;
    }

    public String getVictimName() {
        return victimName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Location getKillLocation() {
        return killLocation;
    }

    public double getVictimMovementDistance() {
        return victimMovementDistance;
    }

    public void setVictimMovementDistance(double distance) {
        this.victimMovementDistance = distance;
    }

    public double getVictimMovementVariance() {
        return victimMovementVariance;
    }

    public void setVictimMovementVariance(double variance) {
        this.victimMovementVariance = variance;
    }

    public boolean didVictimSprint() {
        return victimSprinted;
    }

    public void setVictimSprinted(boolean sprinted) {
        this.victimSprinted = sprinted;
    }

    public boolean didVictimDealDamage() {
        return victimDealtDamage;
    }

    public void setVictimDealtDamage(boolean dealtDamage) {
        this.victimDealtDamage = dealtDamage;
    }

    public int getVictimDamageDealt() {
        return victimDamageDealt;
    }

    public void setVictimDamageDealt(int damage) {
        this.victimDamageDealt = damage;
    }

    public long getCombatDuration() {
        return combatDuration;
    }

    public void setCombatDuration(long duration) {
        this.combatDuration = duration;
    }

    public Set<UUID> getOtherAttackers() {
        return otherAttackers;
    }

    public void addOtherAttacker(UUID attackerId) {
        this.otherAttackers.add(attackerId);
    }

    public int getTotalAttackers() {
        return totalAttackers;
    }

    public void setTotalAttackers(int count) {
        this.totalAttackers = count;
    }

    public List<Location> getVictimMovementPath() {
        return victimMovementPath;
    }

    public void addMovementPoint(Location location) {
        if (location != null) {
            this.victimMovementPath.add(location.clone());
        }
    }

    public int getSuspicionScore() {
        return suspicionScore;
    }

    public void setSuspicionScore(int score) {
        this.suspicionScore = score;
    }

    public List<String> getSuspiciousFlags() {
        return suspiciousFlags;
    }

    public void addSuspiciousFlag(String flag) {
        this.suspiciousFlags.add(flag);
    }

    public void clearSuspiciousFlags() {
        this.suspiciousFlags.clear();
    }

    /**
     * Get a formatted summary of the kill event for logging
     */
    public String getSummary() {
        return String.format(
            "Kill Event: %s -> %s | Location: %.1f, %.1f, %.1f | Movement: %.2f blocks | Variance: %.2f | Sprint: %s | Damage: %s | Score: %d",
            attackerName, victimName,
            killLocation != null ? killLocation.getX() : 0,
            killLocation != null ? killLocation.getY() : 0,
            killLocation != null ? killLocation.getZ() : 0,
            victimMovementDistance,
            victimMovementVariance,
            victimSprinted,
            victimDealtDamage,
            suspicionScore
        );
    }
}

