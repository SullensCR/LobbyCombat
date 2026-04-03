package mc.dragones.lobbycombat;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.UUID;

public class LobbyCombatExpansion extends PlaceholderExpansion {

    private final LobbyCombatPlugin plugin;

    public LobbyCombatExpansion(LobbyCombatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "lobbycombat";
    }

    @Override
    public String getAuthor() {
        return "Dragones";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";

        PlayerData data = PlayerData.get(player);

        switch (identifier.toLowerCase()) {
            case "kills":
                return String.valueOf(data.getKills());
            case "deaths":
                return String.valueOf(data.getDeaths());
            case "assists":
                return String.valueOf(data.getAssists());
            case "kd":
            case "kdratio":
                return String.format("%.2f", data.getKDRatio());
            case "current_kill_streak":
                return String.valueOf(data.getCurrentKillStreak());
            case "best_killstreak":
                return String.valueOf(data.getLongestKillStreak());
            case "pvp_enabled":
                return data.isPvpEnabled() ? "true" : "false";
            case "in_combat":
                return data.isInCombat() ? "true" : "false";
            case "fighting_with":
                UUID opponentUUID = data.getFightingWith();
                if (opponentUUID != null) {
                    Player opponent = plugin.getServer().getPlayer(opponentUUID);
                    return opponent != null ? opponent.getName() : "unknown";
                }
                return "none";
            case "combat_time":
                if (data.isInCombat()) {
                    long timeInCombat = System.currentTimeMillis() - data.getLastDamageTime();
                    return String.valueOf(timeInCombat / 1000); // seconds
                }
                return "0";
            default:
                return null;
        }
    }
}
