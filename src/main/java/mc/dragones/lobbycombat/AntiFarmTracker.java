package mc.dragones.lobbycombat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Tracks and detects suspicious kill farming activity
 */
public class AntiFarmTracker {

    /**
     * Returns all kill events from all players for global alert display.
     */
    public List<KillEventData> getAllKillEvents() {
        List<KillEventData> all = new ArrayList<>();
        for (List<KillEventData> list : playerKillHistory.values()) {
            all.addAll(list);
        }
        return all;
    }

    private final JavaPlugin plugin;
    private final Map<UUID, List<KillEventData>> playerKillHistory = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> targetKillCountdown = new HashMap<>(); // attacker -> victim -> cooldown time
    private final Map<UUID, Map<Double, Integer>> deathLocationClusters = new HashMap<>(); // victim -> location hash -> count
    private final Map<UUID, List<Location>> lastDeathLocations = new HashMap<>(); // victim -> last N death locations
    private final int maxLocationHistory;

    // Configuration
    private boolean enabled;
    private int maxKillsPerTarget;
    private int timeWindowMinutes;
    private int cooldownPerTargetMinutes;
    private int minUniquePlayersForStreak;
    private double minMovementDistanceBlocks;
    private int coordinateClusterRadiusBlocks;
    private int deathLocationHistorySize;
    private int suspicionScoreThresholdLog;
    private int suspicionScoreThresholdAlert;
    private int suspicionScoreThresholdCritical;
    private boolean logToFile;
    private boolean logToConsole;
    private boolean discordEnabled;
    private String discordWebhookUrl;
    private int discordAlertThreshold;

    // Discord webhook customization
    private String discordUsername;
    private String discordAvatarUrl;
    private String discordMentionRoles;
    private String discordEmbedTitle;
    private String discordEmbedDescription;
    private int discordEmbedColor;
    private String discordEmbedThumbnailUrl;
    private String discordEmbedFooterText;
    private String discordEmbedFooterIconUrl;
    private String discordEmbedAuthorName;
    private String discordEmbedAuthorIconUrl;
    private List<Map<String, Object>> discordEmbedFields;

    public AntiFarmTracker(JavaPlugin plugin) {
        this.plugin = plugin;
        this.maxLocationHistory = 10;
        loadConfiguration();
    }

    /**
     * Load configuration from plugin config
     */
    private void loadConfiguration() {
        try {
            enabled = plugin.getConfig().getBoolean("anti-kill-farm.enabled", false);
            maxKillsPerTarget = plugin.getConfig().getInt("anti-kill-farm.max-kills-per-target", 3);
            timeWindowMinutes = plugin.getConfig().getInt("anti-kill-farm.time-window-minutes", 10);
            cooldownPerTargetMinutes = plugin.getConfig().getInt("anti-kill-farm.cooldown-per-target-minutes", 5);
            minUniquePlayersForStreak = plugin.getConfig().getInt("anti-kill-farm.min-unique-players-for-streak", 3);
            minMovementDistanceBlocks = plugin.getConfig().getDouble("anti-kill-farm.min-movement-distance-blocks", 5.0);
            coordinateClusterRadiusBlocks = plugin.getConfig().getInt("anti-kill-farm.coordinate-cluster-radius-blocks", 10);
            deathLocationHistorySize = plugin.getConfig().getInt("anti-kill-farm.death-location-history-size", 10);
            suspicionScoreThresholdLog = plugin.getConfig().getInt("anti-kill-farm.suspicion-score-threshold-log", 4);
            suspicionScoreThresholdAlert = plugin.getConfig().getInt("anti-kill-farm.suspicion-score-threshold-alert", 6);
            suspicionScoreThresholdCritical = plugin.getConfig().getInt("anti-kill-farm.suspicion-score-threshold-critical", 8);
            logToFile = plugin.getConfig().getBoolean("anti-kill-farm.log-file-enabled", true);
            logToConsole = plugin.getConfig().getBoolean("anti-kill-farm.log-to-console", false);
            discordEnabled = plugin.getConfig().getBoolean("anti-kill-farm.discord-webhook-enabled", false);
            discordWebhookUrl = plugin.getConfig().getString("anti-kill-farm.discord-webhook-url", "");
            discordAlertThreshold = plugin.getConfig().getInt("anti-kill-farm.discord-webhook-alert-threshold", 6);

            // Discord webhook customization
            discordUsername = plugin.getConfig().getString("anti-kill-farm.discord-webhook.username", "LobbyCombat AntiFarm");
            discordAvatarUrl = plugin.getConfig().getString("anti-kill-farm.discord-webhook.avatar-url", "");
            discordMentionRoles = plugin.getConfig().getString("anti-kill-farm.discord-webhook.mention-roles", "");
            discordEmbedTitle = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.title", "WARNING: Kill Farming Detected");
            discordEmbedDescription = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.description", "Suspicious activity flagged for staff review");

            // Parse color with safe type conversion
            try {
                discordEmbedColor = plugin.getConfig().getInt("anti-kill-farm.discord-webhook.embed.color", 15158332);
            } catch (Exception e) {
                plugin.getLogger().warning("[AntiFarm] Failed to parse Discord embed color, using default: " + e.getMessage());
                discordEmbedColor = 15158332;
            }

            discordEmbedThumbnailUrl = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.thumbnail-url", "");
            discordEmbedFooterText = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.footer.text", "LobbyCombat Anti-Farm System");
            discordEmbedFooterIconUrl = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.footer.icon-url", "");
            discordEmbedAuthorName = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.author.name", "Anti-Farm Alert");
            discordEmbedAuthorIconUrl = plugin.getConfig().getString("anti-kill-farm.discord-webhook.embed.author.icon-url", "");
        } catch (Exception e) {
            plugin.getLogger().warning("[AntiFarm] Error loading anti-farm configuration: " + e.getMessage());
            if (plugin instanceof LobbyCombatPlugin && ((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                e.printStackTrace();
            }
        }

        // Load embed fields from config
        discordEmbedFields = new ArrayList<>();
        List<?> fieldsConfig = plugin.getConfig().getList("anti-kill-farm.discord-webhook.embed.fields");
        if (fieldsConfig != null) {
            for (Object fieldObj : fieldsConfig) {
                if (fieldObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> field = (Map<String, Object>) fieldObj;

                    // Validate and log field data if debug is enabled
                    if (plugin instanceof LobbyCombatPlugin) {
                        LobbyCombatPlugin lcp = (LobbyCombatPlugin) plugin;
                        if (lcp.isDebugEnabled()) {
                            plugin.getLogger().info("[AntiFarm Debug] Loading Discord embed field:");
                            plugin.getLogger().info("  - name: " + field.get("name"));
                            plugin.getLogger().info("  - value: " + field.get("value"));
                            Object inline = field.get("inline");
                            plugin.getLogger().info("  - inline: " + inline + " (Type: " + (inline != null ? inline.getClass().getSimpleName() : "null") + ")");
                        }
                    }

                    discordEmbedFields.add(field);
                }
            }
        }
    }

    /**
     * Process a kill event and check for suspicious activity
     */
    public void processKillEvent(KillEventData killData) {
        if (!enabled) return;

        // Calculate suspicion score
        calculateSuspicionScore(killData);

        // Store kill event
        storeKillEvent(killData);

        // Check thresholds and log/alert
        if (killData.getSuspicionScore() >= suspicionScoreThresholdLog) {
            logSuspiciousActivity(killData);

            if (killData.getSuspicionScore() >= suspicionScoreThresholdAlert) {
                sendDiscordAlert(killData);

                if (killData.getSuspicionScore() >= suspicionScoreThresholdCritical) {
                    alertStaffCritical(killData);
                }
            }
        }
    }

    /**
     * Calculate suspicion score for a kill event
     */
    private void calculateSuspicionScore(KillEventData killData) {
        int score = 0;
        killData.clearSuspiciousFlags();

        // Check 1: Victim movement
        if (killData.getVictimMovementDistance() < minMovementDistanceBlocks) {
            score++;
            killData.addSuspiciousFlag("Victim moved < " + minMovementDistanceBlocks + " blocks");
        }

        // Check 2: Victim sprint
        if (!killData.didVictimSprint()) {
            score++;
            killData.addSuspiciousFlag("Victim never sprinted");
        }

        // Check 3: Victim damage dealt
        if (!killData.didVictimDealDamage()) {
            score++;
            killData.addSuspiciousFlag("Victim dealt 0 damage");
        }

        // Check 4: Only one attacker
        if (killData.getTotalAttackers() <= 1) {
            score++;
            killData.addSuspiciousFlag("Only attacker involved");
        }

        // Check 5: Kill location clustering
        Location killLoc = killData.getKillLocation();
        if (killLoc != null) {
            int locationsNearby = countDeathLocationsNearby(killData.getVictimId(), killLoc);
            if (locationsNearby >= 2) {
                score++;
                killData.addSuspiciousFlag("Multiple deaths at same location (" + locationsNearby + " nearby)");
            }
        }

        // Check 6: Repeated target
        if (hasRepeatedKillsInTimeWindow(killData.getAttackerId(), killData.getVictimId())) {
            score++;
            killData.addSuspiciousFlag("Repeated kills on same target in time window");
        }

        // Check 7: Low combat duration
        if (killData.getCombatDuration() < 5000) { // Less than 5 seconds
            score++;
            killData.addSuspiciousFlag("Very short combat duration (" + killData.getCombatDuration() + "ms)");
        }

        // Check 8: Movement variance (if available)
        if (killData.getVictimMovementVariance() < 2.0 && killData.getVictimMovementDistance() > 0) {
            score++;
            killData.addSuspiciousFlag("Low movement variance (linear path)");
        }

        killData.setSuspicionScore(score);
    }

    /**
     * Store kill event in history
     */
    private void storeKillEvent(KillEventData killData) {
        playerKillHistory.computeIfAbsent(killData.getAttackerId(), k -> new ArrayList<>()).add(killData);

        // Store death location
        if (killData.getKillLocation() != null) {
            List<Location> locations = lastDeathLocations.computeIfAbsent(killData.getVictimId(), k -> new ArrayList<>());
            locations.add(killData.getKillLocation().clone());
            if (locations.size() > deathLocationHistorySize) {
                locations.remove(0);
            }
        }
    }

    /**
     * Check if player has multiple kills on same target in time window
     */
    private boolean hasRepeatedKillsInTimeWindow(UUID attackerId, UUID victimId) {
        List<KillEventData> kills = playerKillHistory.getOrDefault(attackerId, new ArrayList<>());
        long timeWindowMs = timeWindowMinutes * 60000L;
        long now = System.currentTimeMillis();

        int killCount = 0;
        for (KillEventData kill : kills) {
            if (kill.getVictimId().equals(victimId) && (now - kill.getTimestamp()) < timeWindowMs) {
                killCount++;
                if (killCount > maxKillsPerTarget) {
                    return true;
                }
            }
        }

        return killCount > maxKillsPerTarget;
    }

    /**
     * Count how many death locations are near the given location
     */
    private int countDeathLocationsNearby(UUID victimId, Location center) {
        List<Location> deathLocs = lastDeathLocations.getOrDefault(victimId, new ArrayList<>());
        int count = 0;

        for (Location loc : deathLocs) {
            if (loc.getWorld() != null && center.getWorld() != null &&
                loc.getWorld().equals(center.getWorld()) &&
                loc.distance(center) <= coordinateClusterRadiusBlocks) {
                count++;
            }
        }

        return count;
    }

    /**
     * Log suspicious activity to file and console
     */
    private void logSuspiciousActivity(KillEventData killData) {
        String logMessage = buildLogMessage(killData);

        if (logToConsole) {
            plugin.getLogger().warning("[AntiFarm] " + logMessage);
        }

        if (logToFile) {
            writeToLogFile(logMessage);
        }
    }

    /**
     * Build a detailed log message for suspicious activity
     */
    private String buildLogMessage(KillEventData killData) {
        StringBuilder sb = new StringBuilder();
        sb.append("[AntiFarm Detection]\n");
        sb.append("  Attacker: ").append(killData.getAttackerName()).append(" (").append(killData.getAttackerId()).append(")\n");
        sb.append("  Victim: ").append(killData.getVictimName()).append(" (").append(killData.getVictimId()).append(")\n");
        sb.append("  Suspicion Score: ").append(killData.getSuspicionScore()).append("\n");
        sb.append("  Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(killData.getTimestamp()))).append("\n");
        sb.append("  Location: ");
        if (killData.getKillLocation() != null) {
            sb.append(String.format("%.1f, %.1f, %.1f", killData.getKillLocation().getX(), killData.getKillLocation().getY(), killData.getKillLocation().getZ()));
        } else {
            sb.append("Unknown");
        }
        sb.append("\n");
        sb.append("  Movement: ").append(String.format("%.2f blocks", killData.getVictimMovementDistance())).append("\n");
        sb.append("  Variance: ").append(String.format("%.2f", killData.getVictimMovementVariance())).append("\n");
        sb.append("  Sprint: ").append(killData.didVictimSprint()).append("\n");
        sb.append("  Damage Dealt: ").append(killData.didVictimDealDamage()).append(" (").append(killData.getVictimDamageDealt()).append(" damage)\n");
        sb.append("  Combat Duration: ").append(killData.getCombatDuration()).append("ms\n");
        sb.append("  Flags:\n");
        for (String flag : killData.getSuspiciousFlags()) {
            sb.append("    - ").append(flag).append("\n");
        }

        return sb.toString();
    }

    /**
     * Write log message to file
     */
    private void writeToLogFile(String message) {
        try {
            File logFolder = new File(plugin.getDataFolder(), "logs");
            if (!logFolder.exists()) {
                logFolder.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File logFile = new File(logFolder, "anti-farm-log_" + timestamp + ".txt");

            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + message + "\n");
                writer.write("---\n");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write to anti-farm log file: " + e.getMessage());
        }
    }

    /**
     * Send Discord webhook alert
     */
    private void sendDiscordAlert(KillEventData killData) {
        if (!discordEnabled || discordWebhookUrl.isEmpty() || killData.getSuspicionScore() < discordAlertThreshold) {
            return;
        }

        // Send the alert asynchronously
        sendDiscordAlertTask(killData);
    }

    /**
     * Alert staff about critical suspicion
     */
    private void sendDiscordAlertTask(KillEventData killData) {
        if (!discordEnabled || discordWebhookUrl.isEmpty()) {
            return;
        }

        // Run async to avoid blocking server
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                    plugin.getLogger().info("[AntiFarm Debug] Starting webhook payload generation...");
                }
                String jsonPayload = buildDiscordPayload(killData);
                if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                    plugin.getLogger().info("[AntiFarm Debug] Webhook payload generated successfully, length: " + jsonPayload.length());
                }
                sendWebhookRequest(discordWebhookUrl, jsonPayload);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
                if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                    plugin.getLogger().warning("[AntiFarm Debug] Exception during webhook alert: " + e.getClass().getSimpleName());
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Build Discord webhook JSON payload
     */
    private String buildDiscordPayload(KillEventData killData) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"username\": \"").append(escapeJson(discordUsername)).append("\",\n");
            if (!discordAvatarUrl.isEmpty()) {
                sb.append("  \"avatar_url\": \"").append(escapeJson(discordAvatarUrl)).append("\",\n");
            }
            if (!discordMentionRoles.isEmpty()) {
                sb.append("  \"content\": \"").append(escapeJson(discordMentionRoles)).append("\",\n");
            }
            sb.append("  \"embeds\": [{\n");
            sb.append("    \"title\": \"").append(replacePlaceholders(discordEmbedTitle, killData)).append("\",\n");
            sb.append("    \"description\": \"").append(replacePlaceholders(discordEmbedDescription, killData)).append("\",\n");
            sb.append("    \"fields\": [\n");

            // Add dynamic fields with placeholder replacement
            for (Map<String, Object> field : discordEmbedFields) {
                try {
                    String fieldName = replacePlaceholders((String) field.get("name"), killData);
                    String fieldValue = replacePlaceholders((String) field.get("value"), killData);

                    // Handle inline field - could be boolean or string
                    boolean inline = false;
                    Object inlineObj = field.get("inline");
                    if (inlineObj != null) {
                        if (inlineObj instanceof Boolean) {
                            inline = (Boolean) inlineObj;
                        } else if (inlineObj instanceof String) {
                            inline = Boolean.parseBoolean((String) inlineObj);
                        }
                    }

                    if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                        plugin.getLogger().info("[AntiFarm Debug] Field - Name: " + fieldName + ", Value: " + fieldValue + ", Inline: " + inline + " (Type: " + (inlineObj != null ? inlineObj.getClass().getSimpleName() : "null") + ")");
                    }

                    sb.append("      {\"name\": \"").append(fieldName).append("\", \"value\": \"").append(fieldValue).append("\", \"inline\": ").append(inline).append("},\n");
                } catch (Exception e) {
                    plugin.getLogger().warning("[AntiFarm] Error processing Discord field: " + e.getMessage());
                    if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                        plugin.getLogger().warning("[AntiFarm Debug] Field object: " + field.toString());
                        e.printStackTrace();
                    }
                }
            }

            // Remove last comma and space
            if (!discordEmbedFields.isEmpty()) {
                sb.setLength(sb.length() - 2);
            }

            sb.append("    ],\n");
            sb.append("    \"color\": ").append(discordEmbedColor);

            // Add optional fields with proper comma handling
            boolean hasOptionalFields = false;
            if (!discordEmbedThumbnailUrl.isEmpty()) {
                sb.append(",\n    \"thumbnail\": {\"url\": \"").append(escapeJson(discordEmbedThumbnailUrl)).append("\"}");
                hasOptionalFields = true;
            }
            if (!discordEmbedFooterText.isEmpty()) {
                sb.append(hasOptionalFields ? ",\n" : ",\n");
                sb.append("    \"footer\": {\"text\": \"").append(replacePlaceholders(discordEmbedFooterText, killData)).append("\", \"icon_url\": \"").append(escapeJson(discordEmbedFooterIconUrl)).append("\"}");
                hasOptionalFields = true;
            }
            if (!discordEmbedAuthorName.isEmpty()) {
                sb.append(hasOptionalFields ? ",\n" : ",\n");
                sb.append("    \"author\": {\"name\": \"").append(replacePlaceholders(discordEmbedAuthorName, killData)).append("\", \"icon_url\": \"").append(escapeJson(discordEmbedAuthorIconUrl)).append("\"}");
            }

            sb.append("\n  }]\n");
            sb.append("}\n");

            return sb.toString();
        } catch (Exception e) {
            plugin.getLogger().warning("[AntiFarm] Exception in buildDiscordPayload: " + e.getMessage());
            if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                plugin.getLogger().warning("[AntiFarm Debug] Full exception details:");
                e.printStackTrace();
            }
            // Return a minimal valid payload as fallback
            return "{\"username\": \"LobbyCombat\", \"content\": \"An error occurred while building the webhook payload\"}";
        }
    }

    /**
     * Send webhook request to Discord
     */
    private void sendWebhookRequest(String webhookUrl, String jsonPayload) {
        try {
            if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                plugin.getLogger().info("[AntiFarm Debug] Discord webhook URL: " + webhookUrl);
                plugin.getLogger().info("[AntiFarm Debug] Discord payload being sent:\n" + jsonPayload);
            }

            java.net.URL url = new java.net.URL(webhookUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();

            // Configure the connection
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "LobbyCombat/1.0");
            connection.setDoOutput(true);

            // Write the JSON payload
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Get the response
            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                plugin.getLogger().info("[AntiFarm] Discord webhook sent successfully (Response: " + responseCode + ")");
            } else {
                plugin.getLogger().warning("[AntiFarm] Discord webhook failed with response code: " + responseCode);
                // Read error response if available
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getErrorStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    plugin.getLogger().warning("[AntiFarm] Discord error response: " + response.toString());
                    if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                        plugin.getLogger().info("[AntiFarm Debug] Full error response: " + response.toString());
                    }
                } catch (Exception e) {
                    // Ignore error reading error stream
                }
            }

            connection.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("[AntiFarm] Failed to send Discord webhook: " + e.getMessage());
            if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                plugin.getLogger().warning("[AntiFarm Debug] Exception type: " + e.getClass().getSimpleName());
                plugin.getLogger().warning("[AntiFarm Debug] Full stacktrace:");
                e.printStackTrace();
            }
        }
    }

    /**
     * Alert staff about critical suspicion
     */
    private void alertStaffCritical(KillEventData killData) {
        plugin.getLogger().severe("[AntiFarm CRITICAL] Kill farming highly suspected: " + killData.getAttackerName() + " -> " + killData.getVictimName() + " (Score: " + killData.getSuspicionScore() + ")");
    }

    /**
     * Escape special characters for JSON
     */
    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
     * Replace placeholders in a string with actual values from kill data
     */
    private String replacePlaceholders(String input, KillEventData killData) {
        if (input == null) return "";

        String result = input;

        // Basic placeholders
        result = result.replace("%attacker%", escapeJson(killData.getAttackerName()));
        result = result.replace("%attacker_uuid%", killData.getAttackerId().toString());
        result = result.replace("%victim%", escapeJson(killData.getVictimName()));
        result = result.replace("%victim_uuid%", killData.getVictimId().toString());
        result = result.replace("%score%", String.valueOf(killData.getSuspicionScore()));

        // Severity level
        String severity = "Low";
        if (killData.getSuspicionScore() >= 8) severity = "Critical";
        else if (killData.getSuspicionScore() >= 6) severity = "High";
        else if (killData.getSuspicionScore() >= 4) severity = "Medium";
        result = result.replace("%severity%", severity);

        // Location placeholders
        if (killData.getKillLocation() != null) {
            result = result.replace("%x%", String.format("%.1f", killData.getKillLocation().getX()));
            result = result.replace("%y%", String.format("%.1f", killData.getKillLocation().getY()));
            result = result.replace("%z%", String.format("%.1f", killData.getKillLocation().getZ()));
            result = result.replace("%world%", killData.getKillLocation().getWorld() != null ?
                escapeJson(killData.getKillLocation().getWorld().getName()) : "Unknown");
        } else {
            result = result.replace("%x%", "Unknown");
            result = result.replace("%y%", "Unknown");
            result = result.replace("%z%", "Unknown");
            result = result.replace("%world%", "Unknown");
        }

        // Flags (suspicious behaviors)
        StringBuilder flagsSb = new StringBuilder();
        List<String> flags = killData.getSuspiciousFlags();
        if (!flags.isEmpty()) {
            for (int i = 0; i < flags.size(); i++) {
                flagsSb.append("• ").append(escapeJson(flags.get(i)));
                if (i < flags.size() - 1) {
                    flagsSb.append("\\n");
                }
            }
        } else {
            flagsSb.append("None");
        }
        result = result.replace("%flags%", flagsSb.toString());

        // Combat stats
        result = result.replace("%combat_duration%", String.format("%.1f", killData.getCombatDuration() / 1000.0));
        result = result.replace("%victim_damage%", String.format("%.1f", killData.getVictimDamageDealt()));
        result = result.replace("%other_attackers%", String.valueOf(Math.max(0, killData.getTotalAttackers() - 1)));

        // Timestamp
        result = result.replace("%timestamp%", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(killData.getTimestamp())));

        // Server name (from server.properties)
        result = result.replace("%server_name%", escapeJson(plugin.getServer().getServerName()));

        return result;
    }

    /**
     * Track player movement during combat
     */
    public void trackPlayerMovement(Player player, Location from, Location to) {
        if (!enabled) return;
        // This would be called from PlayerMoveEvent
        // Placeholder for now
    }

    /**
     * Track player sprint state
     */
    public void trackPlayerSprint(Player player, boolean sprinting) {
        if (!enabled) return;
        // This would be called from PlayerToggleSprintEvent
        // Placeholder for now
    }

    /**
     * Get kill history for a player
     */
    public List<KillEventData> getKillHistory(UUID playerId) {
        return playerKillHistory.getOrDefault(playerId, new ArrayList<>());
    }

    /**
     * Clear old kill events (cleanup task)
     */
    public void cleanupOldEvents(long maxAgeMs) {
        long now = System.currentTimeMillis();
        playerKillHistory.values().forEach(list ->
            list.removeIf(killData -> (now - killData.getTimestamp()) > maxAgeMs)
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void reload() {
        loadConfiguration();
    }
}

