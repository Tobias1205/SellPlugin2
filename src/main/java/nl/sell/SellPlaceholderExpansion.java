package nl.sell;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Optioneel: placeholders voor scoreboards/tablist (alleen als PlaceholderAPI is geinstalleerd).
 *   %sell_multiplier_<categorie>%  bv. %sell_multiplier_crops%
 *   %sell_sold_<categorie>%        totaal verkocht in die categorie
 * Categorie-namen: crops, ores, mobs, natural, tools, fish, books, potions, blocks
 */
public class SellPlaceholderExpansion extends PlaceholderExpansion {

    private final SellPlugin plugin;

    private SellPlaceholderExpansion(SellPlugin plugin) {
        this.plugin = plugin;
    }

    /** Veilig registreren: doet niets als PlaceholderAPI ontbreekt. */
    static void tryRegister(SellPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            new SellPlaceholderExpansion(plugin).register();
            plugin.getLogger().info("PlaceholderAPI placeholders geregistreerd (%sell_...%).");
        } catch (Throwable t) {
            plugin.getLogger().warning("Kon PlaceholderAPI placeholders niet registreren: " + t);
        }
    }

    @Override public @NotNull String getIdentifier() { return "sell"; }
    @Override public @NotNull String getAuthor() { return "SellPlugin"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        return plugin.placeholder(player, params);
    }
}
