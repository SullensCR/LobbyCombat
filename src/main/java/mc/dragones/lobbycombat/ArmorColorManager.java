package mc.dragones.lobbycombat;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class ArmorColorManager {

    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Map<String, Color> availableColors = new HashMap<>();
    private final Map<String, String> colorPermissions = new HashMap<>();
    private final Map<String, String> specialPermissions = new HashMap<>();
    private final Map<Player, BukkitRunnable> rainbowTasks = new HashMap<>();
    private final Map<Player, Integer> rainbowPhases = new HashMap<>();

    public ArmorColorManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadColors();
    }

    private void loadColors() {
        // Load available colors from config
        if (plugin.getConfig().contains("inventory.items.armor-colors.available-colors")) {
            for (String colorName : plugin.getConfig().getConfigurationSection("inventory.items.armor-colors.available-colors").getKeys(false)) {
                String hex = plugin.getConfig().getString("inventory.items.armor-colors.available-colors." + colorName);
                availableColors.put(colorName, hexToColor(hex));
            }
        }

        // Load permissions
        if (plugin.getConfig().contains("inventory.items.armor-colors.permissions")) {
            for (String colorName : plugin.getConfig().getConfigurationSection("inventory.items.armor-colors.permissions").getKeys(false)) {
                String permission = plugin.getConfig().getString("inventory.items.armor-colors.permissions." + colorName);
                if (permission != null && !permission.isEmpty()) {
                    colorPermissions.put(colorName, permission);
                }
            }
        }

        // Load special permissions
        if (plugin.getConfig().contains("inventory.items.armor-colors.special-colors")) {
            for (String specialName : plugin.getConfig().getConfigurationSection("inventory.items.armor-colors.special-colors").getKeys(false)) {
                String permission = plugin.getConfig().getString("inventory.items.armor-colors.special-colors." + specialName + ".permission");
                if (permission != null && !permission.isEmpty()) {
                    specialPermissions.put(specialName, permission);
                }
            }
        }
    }

    public Set<String> getAvailableColors() {
        return availableColors.keySet();
    }

    public boolean canUseColor(Player player, String colorName) {
        if (colorName.equals("random")) {
            String permission = specialPermissions.get("random");
            return permission == null || permission.isEmpty() || player.hasPermission(permission);
        }

        if (colorName.equals("rainbow")) {
            String permission = specialPermissions.get("rainbow");
            return permission == null || permission.isEmpty() || player.hasPermission(permission);
        }

        if (colorName.equals("pastel")) {
            String permission = specialPermissions.get("pastel");
            return permission == null || permission.isEmpty() || player.hasPermission(permission);
        }

        String permission = colorPermissions.get(colorName);
        return permission == null || permission.isEmpty() || player.hasPermission(permission);
    }

    public Color getColor(String colorName) {
        if (colorName.equals("random")) {
            return getRandomColor();
        }
        return availableColors.get(colorName);
    }

    private Color getRandomColor() {
        String[] colorNames = availableColors.keySet().toArray(new String[0]);
        String randomColorName = colorNames[random.nextInt(colorNames.length)];
        return availableColors.get(randomColorName);
    }

    public void applyArmorColor(Player player, String colorName) {
        PlayerData data = PlayerData.get(player);
        data.setArmorColor(colorName);
        data.save();

        // Stop any existing rainbow task
        stopRainbowTask(player);

        // Apply the color to current armor if they have PvP enabled
        if (data.isPvpEnabled()) {
            // Initialize phase for rainbow/pastel
            if (colorName.equals("rainbow") || colorName.equals("pastel")) {
                rainbowPhases.put(player, 0);
            }
            updateArmorColor(player, colorName);

            // Start rainbow task if needed
            if (colorName.equals("rainbow") || colorName.equals("pastel")) {
                startRainbowTask(player, colorName);
            }
        }
    }

    public void updateArmorColor(Player player, String colorName) {
        if (colorName == null || colorName.isEmpty()) {
            colorName = "random";
        }

        // Update helmet
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.getType() == Material.LEATHER_HELMET) {
            LeatherArmorMeta meta = (LeatherArmorMeta) helmet.getItemMeta();
            if (meta != null) {
                meta.setColor(getArmorColor(colorName, player));
                helmet.setItemMeta(meta);
                player.getInventory().setHelmet(helmet);
            }
        }

        // Update chestplate
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType() == Material.LEATHER_CHESTPLATE) {
            LeatherArmorMeta meta = (LeatherArmorMeta) chestplate.getItemMeta();
            if (meta != null) {
                meta.setColor(getArmorColor(colorName, player));
                chestplate.setItemMeta(meta);
                player.getInventory().setChestplate(chestplate);
            }
        }
    }

    private Color getArmorColor(String colorName, Player player) {
        if (colorName.equals("rainbow")) {
            return getRainbowColor(player, false);
        } else if (colorName.equals("pastel")) {
            return getRainbowColor(player, true);
        } else if (colorName.equals("random")) {
            return getRandomColor();
        } else {
            return availableColors.getOrDefault(colorName, getRandomColor());
        }
    }

    private Color getRainbowColor(Player player, boolean pastel) {
        int phase = rainbowPhases.getOrDefault(player, 0);
        float hue = (phase % 360) / 360.0f;

        if (pastel) {
            // Pastel rainbow using HSB - very low saturation, high brightness for true pastels
            float saturation = 0.35f;  // Very low saturation (35%) for muted colors
            float brightness = 0.95f;  // Very high brightness (95%) for light, airy feel
            int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return Color.fromRGB(r, g, b);
        } else {
            // Normal rainbow
            int r = (int) (Math.sin(hue * 2 * Math.PI) * 127 + 128);
            int g = (int) (Math.sin((hue * 2 * Math.PI) + (2 * Math.PI / 3)) * 127 + 128);
            int b = (int) (Math.sin((hue * 2 * Math.PI) + (4 * Math.PI / 3)) * 127 + 128);
            return Color.fromRGB(Math.max(0, Math.min(255, r)), Math.max(0, Math.min(255, g)), Math.max(0, Math.min(255, b)));
        }
    }

    private void startRainbowTask(Player player, String colorName) {
        boolean pastel = colorName.equals("pastel");
        int speed = plugin.getConfig().getInt("inventory.items.armor-colors.special-colors." + colorName + ".speed", 2);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    rainbowTasks.remove(player);
                    rainbowPhases.remove(player);
                    return;
                }

                PlayerData data = PlayerData.get(player);
                if (!data.isPvpEnabled() || !data.getArmorColor().equals(colorName)) {
                    cancel();
                    rainbowTasks.remove(player);
                    rainbowPhases.remove(player);
                    return;
                }

                // Update phase
                int phase = rainbowPhases.getOrDefault(player, 0);
                phase = (phase + speed) % 360;
                rainbowPhases.put(player, phase);

                // Update armor color with new phase
                updateRainbowArmor(player, colorName);
            }
        };

        task.runTaskTimer(plugin, 0L, 2L); // Run every 2 ticks for smoother animation
        rainbowTasks.put(player, task);
    }

    private void updateRainbowArmor(Player player, String colorName) {
        boolean pastel = colorName.equals("pastel");
        Color color = getRainbowColor(player, pastel);

        // Update helmet
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.getType() == Material.LEATHER_HELMET) {
            LeatherArmorMeta meta = (LeatherArmorMeta) helmet.getItemMeta();
            if (meta != null) {
                meta.setColor(color);
                helmet.setItemMeta(meta);
                player.getInventory().setHelmet(helmet);
            }
        }

        // Update chestplate
        ItemStack chestplate = player.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType() == Material.LEATHER_CHESTPLATE) {
            LeatherArmorMeta meta = (LeatherArmorMeta) chestplate.getItemMeta();
            if (meta != null) {
                meta.setColor(color);
                chestplate.setItemMeta(meta);
                player.getInventory().setChestplate(chestplate);
            }
        }
    }

    private void stopRainbowTask(Player player) {
        BukkitRunnable task = rainbowTasks.remove(player);
        if (task != null) {
            task.cancel();
        }
        rainbowPhases.remove(player);
    }

    public void onPlayerQuit(Player player) {
        stopRainbowTask(player);
    }

    public void startRainbowTaskIfNeeded(Player player) {
        PlayerData data = PlayerData.get(player);
        String colorName = data.getArmorColor();

        if (data.isPvpEnabled() && (colorName.equals("rainbow") || colorName.equals("pastel"))) {
            // Initialize phase if not already set
            if (!rainbowPhases.containsKey(player)) {
                rainbowPhases.put(player, 0);
            }

            // Start the task if not already running
            if (!rainbowTasks.containsKey(player)) {
                startRainbowTask(player, colorName);
            }
        }
    }

    private Color hexToColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return Color.WHITE;
        }

        try {
            return Color.fromRGB(
                Integer.valueOf(hex.substring(1, 3), 16),
                Integer.valueOf(hex.substring(3, 5), 16),
                Integer.valueOf(hex.substring(5, 7), 16)
            );
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }

    public boolean isValidColor(String colorName) {
        return colorName.equals("random") || colorName.equals("rainbow") || colorName.equals("pastel") || availableColors.containsKey(colorName);
    }
}
