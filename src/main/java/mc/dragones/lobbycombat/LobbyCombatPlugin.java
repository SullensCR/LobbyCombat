package mc.dragones.lobbycombat;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * LobbyCombat - Main Plugin Class
 * A Minecraft lobbying and combat system for PandaSpigot
 */
public class LobbyCombatPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private ArmorColorManager armorColorManager;
    private static final int CURRENT_CONFIG_VERSION = 3;

    @Override
    public void onEnable() {
        getLogger().info("====================================");
        getLogger().info("LobbyCombat v" + getDescription().getVersion() + " is loading...");
        getLogger().info("====================================");
        
        // Create plugin data folder if it doesn't exist
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        // Save default config if not exists
        saveDefaultConfig();
        
        // Handle config versioning
        handleConfigVersion();
        
        // Initialize database
        databaseManager = new DatabaseManager(this);
        databaseManager.connect();
        PlayerData.setDatabaseManager(databaseManager);
        
        // Initialize armor color manager
        armorColorManager = new ArmorColorManager(this);

        // Register commands
        getCommand("lobbypvp").setExecutor(new LobbyPvpCommand(this));
        getCommand("lobbycombat").setExecutor(new LobbyCombatCommand(this));
        
        // Register events
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        
        // Register PlaceholderAPI expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LobbyCombatExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered!");
        } else {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
        }
        
        // Start combat timeout checker
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                PlayerData data = PlayerData.get(player);
                if (data.isInCombat()) {
                    long timeSinceLastDamage = System.currentTimeMillis() - data.getLastDamageTime();
                    int noCombatSeconds = getConfig().getInt("pvp.disable.no-combat-seconds", 10) * 1000;
                    
                    if (timeSinceLastDamage >= noCombatSeconds) {
                        data.setInCombat(false);
                        data.setFightingWith(null);
                    }
                }
            }
        }, 20L, 20L); // Check every second
        
        getLogger().info("LobbyCombat successfully enabled!");
        getLogger().info("For help, use: /lobbycombat help");
    }

    @Override
    public void onDisable() {
        getLogger().info("====================================");
        getLogger().info("LobbyCombat is being disabled...");
        getLogger().info("====================================");
        
        // Save all player data before shutdown
        PlayerData.saveAllToDatabase();
        
        // Disconnect database
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        
        getLogger().info("LobbyCombat successfully disabled!");
    }
    
    /**
     * Handles configuration versioning and migration
     */
    private void handleConfigVersion() {
        int configVersion = getConfig().getInt("config-version", 1);
        
        if (configVersion < CURRENT_CONFIG_VERSION) {
            getLogger().warning("Configuration version mismatch detected!");
            getLogger().warning("Current version: " + configVersion + ", Required version: " + CURRENT_CONFIG_VERSION);
            
            // Backup old config
            backupConfig(configVersion);
            
            // Delete old config to allow fresh one to be generated
            File configFile = new File(getDataFolder(), "config.yml");
            if (configFile.exists()) {
                configFile.delete();
            }
            
            // Save fresh config
            saveDefaultConfig();
            reloadConfig();
            
            getLogger().info("Configuration has been updated!");
            getLogger().info("Your old configuration has been backed up with timestamp.");
        }
    }
    
    /**
     * Backs up the current configuration with a timestamp
     */
    private void backupConfig(int oldVersion) {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return;
        }
        
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File backupFile = new File(getDataFolder(), "config_v" + oldVersion + "_backup_" + timestamp + ".yml");
        
        try {
            Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("Configuration backup saved to: " + backupFile.getName());
        } catch (IOException e) {
            getLogger().warning("Failed to backup configuration: " + e.getMessage());
        }
    }
    
    public Sound getSound(String key) {
        String soundName = getConfig().getString("sounds." + key);
        if (soundName == null) return Sound.CLICK; // Use CLICK as default for 1.8.8 compatibility
        
        try {
            return Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid sound: " + soundName + " for key: " + key);
            return Sound.CLICK; // Use CLICK as default for 1.8.8 compatibility
        }
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ArmorColorManager getArmorColorManager() {
        return armorColorManager;
    }
}
