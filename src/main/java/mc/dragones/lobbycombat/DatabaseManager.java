package mc.dragones.lobbycombat;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private String tablePrefix;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.tablePrefix = plugin.getConfig().getString("database.mysql.table-prefix", "lc_");
    }

    public void connect() {
        String type = plugin.getConfig().getString("database.type", "sqlite");

        try {
            if ("mysql".equalsIgnoreCase(type)) {
                connectMySQL();
            } else {
                connectSQLite();
            }

            createTables();
            plugin.getLogger().info("Database connected successfully!");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to database", e);
        }
    }

    private void connectSQLite() throws SQLException {
        try {
            // Load the original SQLite JDBC driver (not relocated)
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite JDBC driver not found", e);
            throw new SQLException("SQLite JDBC driver not found", e);
        }

        String fileName = plugin.getConfig().getString("database.sqlite.file", "lobbycombat.db");
        File dbFile = new File(plugin.getDataFolder(), fileName);

        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);
    }

    private void connectMySQL() throws SQLException {
        try {
            // Load the original MySQL JDBC driver (not relocated)
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "MySQL JDBC driver not found", e);
            throw new SQLException("MySQL JDBC driver not found", e);
        }

        String host = plugin.getConfig().getString("database.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("database.mysql.port", 3306);
        String database = plugin.getConfig().getString("database.mysql.database", "lobbycombat");
        String username = plugin.getConfig().getString("database.mysql.username", "root");
        String password = plugin.getConfig().getString("database.mysql.password", "password");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        connection = DriverManager.getConnection(url, username, password);
    }

    private void createTables() throws SQLException {
        String type = plugin.getConfig().getString("database.type", "sqlite");
        
        // Create table if it doesn't exist
        String sql = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "players (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "kills INT DEFAULT 0," +
                    "deaths INT DEFAULT 0," +
                    "assists INT DEFAULT 0," +
                    "current_kill_streak INT DEFAULT 0," +
                    "longest_kill_streak INT DEFAULT 0," +
                    "armor_color VARCHAR(20) DEFAULT 'random'," +
                    "coward_death_message TEXT," +
                    "coward_win_message TEXT," +
                    "anti_interrupt_enabled BOOLEAN DEFAULT 0" +
                    ")";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
        
        // Add missing columns if they don't exist (migration)
        if ("sqlite".equalsIgnoreCase(type)) {
            addColumnIfNotExists("coward_death_message", "TEXT");
            addColumnIfNotExists("coward_win_message", "TEXT");
            addColumnIfNotExists("anti_interrupt_enabled", "BOOLEAN");
            addColumnIfNotExists("current_kill_streak", "INT");
            addColumnIfNotExists("longest_kill_streak", "INT");
            addColumnIfNotExists("armor_color", "VARCHAR(20)");
        } else {
            addColumnIfNotExistsMySQL("coward_death_message", "TEXT");
            addColumnIfNotExistsMySQL("coward_win_message", "TEXT");
            addColumnIfNotExistsMySQL("anti_interrupt_enabled", "BOOLEAN");
            addColumnIfNotExistsMySQL("current_kill_streak", "INT");
            addColumnIfNotExistsMySQL("longest_kill_streak", "INT");
            addColumnIfNotExistsMySQL("armor_color", "VARCHAR(20)");
        }
    }
    
    private void addColumnIfNotExists(String columnName, String columnType) throws SQLException {
        String sql = "PRAGMA table_info(" + tablePrefix + "players)";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            boolean columnExists = false;
            while (rs.next()) {
                if (rs.getString("name").equals(columnName)) {
                    columnExists = true;
                    break;
                }
            }
            
            if (!columnExists) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN " + columnName + " " + columnType;
                try (PreparedStatement alterStmt = connection.prepareStatement(alterSql)) {
                    alterStmt.executeUpdate();
                    plugin.getLogger().info("Added column " + columnName + " to players table");
                }
            }
        }
    }
    
    private void addColumnIfNotExistsMySQL(String columnName, String columnType) throws SQLException {
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='" + tablePrefix + "players' AND COLUMN_NAME='" + columnName + "'";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                String alterSql = "ALTER TABLE " + tablePrefix + "players ADD COLUMN " + columnName + " " + columnType;
                try (PreparedStatement alterStmt = connection.prepareStatement(alterSql)) {
                    alterStmt.executeUpdate();
                    plugin.getLogger().info("Added column " + columnName + " to players table");
                }
            }
        }
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("Database disconnected successfully!");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to disconnect from database", e);
            }
        }
    }

    public void savePlayerData(UUID uuid, int kills, int deaths, int assists, String cowardDeathMessage, String cowardWinMessage) {
        String sql = "INSERT INTO " + tablePrefix + "players (uuid, kills, deaths, assists, current_kill_streak, longest_kill_streak, armor_color, coward_death_message, coward_win_message, anti_interrupt_enabled) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "kills = VALUES(kills), " +
                    "deaths = VALUES(deaths), " +
                    "assists = VALUES(assists), " +
                    "current_kill_streak = VALUES(current_kill_streak), " +
                    "longest_kill_streak = VALUES(longest_kill_streak), " +
                    "armor_color = VALUES(armor_color), " +
                    "coward_death_message = VALUES(coward_death_message), " +
                    "coward_win_message = VALUES(coward_win_message), " +
                    "anti_interrupt_enabled = VALUES(anti_interrupt_enabled)";

        // For SQLite, use INSERT OR REPLACE
        String type = plugin.getConfig().getString("database.type", "sqlite");
        if ("sqlite".equalsIgnoreCase(type)) {
            sql = "INSERT OR REPLACE INTO " + tablePrefix + "players (uuid, kills, deaths, assists, current_kill_streak, longest_kill_streak, armor_color, coward_death_message, coward_win_message, anti_interrupt_enabled) " +
                  "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setInt(2, kills);
            stmt.setInt(3, deaths);
            stmt.setInt(4, assists);
            PlayerData data = PlayerData.get(uuid);
            stmt.setInt(5, data.getCurrentKillStreak());
            stmt.setInt(6, data.getLongestKillStreak());
            stmt.setString(7, data.getArmorColor());
            stmt.setString(8, cowardDeathMessage);
            stmt.setString(9, cowardWinMessage);
            stmt.setBoolean(10, data.isAntiInterruptEnabled());
            stmt.executeUpdate();
            if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] Saved player " + uuid + " - deaths=" + deaths + ", deathMsg=" + cowardDeathMessage + ", winMsg=" + cowardWinMessage);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save player data for " + uuid, e);
        }
    }

    public PlayerData loadPlayerData(UUID uuid) {
        String sql = "SELECT kills, deaths, assists, current_kill_streak, longest_kill_streak, armor_color, coward_death_message, coward_win_message, anti_interrupt_enabled FROM " + tablePrefix + "players WHERE uuid = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int kills = rs.getInt("kills");
                    int deaths = rs.getInt("deaths");
                    int assists = rs.getInt("assists");
                    int currentKillStreak = rs.getInt("current_kill_streak");
                    int longestKillStreak = rs.getInt("longest_kill_streak");
                    String armorColor = rs.getString("armor_color");
                    String cowardDeathMessage = rs.getString("coward_death_message");
                    String cowardWinMessage = rs.getString("coward_win_message");
                    boolean antiInterruptEnabled = rs.getBoolean("anti_interrupt_enabled");

                    PlayerData data = new PlayerData(uuid);
                    data.setKills(kills);
                    data.setDeaths(deaths);
                    data.setAssists(assists);
                    data.setCurrentKillStreak(currentKillStreak);
                    data.setLongestKillStreak(longestKillStreak);
                    data.setArmorColor(armorColor != null ? armorColor : "random");
                    data.setCowardDeathMessage(cowardDeathMessage);
                    data.setCowardWinMessage(cowardWinMessage);
                    data.setAntiInterruptEnabled(antiInterruptEnabled);
                    if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] Loaded player data from DB for " + uuid + " - deathMsg: " + cowardDeathMessage + ", winMsg: " + cowardWinMessage);
                    }
                    return data;
                } else {
                    if (((LobbyCombatPlugin) plugin).isDebugEnabled()) {
                        plugin.getLogger().info("[DEBUG] No player data found in DB for " + uuid + " - creating new");
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player data for " + uuid, e);
        }

        // Return new data if not found
        return new PlayerData(uuid);
    }

    public void saveAllPlayerData() {
        java.util.Collection<PlayerData> allData = PlayerData.getAllPlayerData().values();
        for (PlayerData data : allData) {
            savePlayerData(data.getUuid(), data.getKills(), data.getDeaths(),
                          data.getAssists(), data.getCowardDeathMessage(), data.getCowardWinMessage());
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public void reloadConfig() {
        // Close current connection
        disconnect();

        // Update table prefix
        this.tablePrefix = plugin.getConfig().getString("database.mysql.table-prefix", "lc_");

        // Reconnect with new settings
        connect();
    }
}
