package nl.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Slots 0-44 zijn voor items, de onderste rij (45-53) voor de categorie-iconen. */
    private static final int ITEM_SLOTS = 45;
    private static final int BACK_SLOT = 45;            // rode knop linksonder
    private static final int PROGRESS_ICON_SLOT = 1;    // categorie-icoon bovenaan
    /** De kronkel van level 1 (onder het icoon) tot het laatste level (rechtsboven). 20 plekken. */
    private static final int[] PROGRESS_PATH = {
            10, 19, 28, 37, 38, 39, 30, 21, 12, 13,
            14, 23, 32, 41, 42, 43, 34, 25, 16, 7};

    /** Items die nooit verkocht kunnen worden (niet verkrijgbaar of met data). */
    private static final Set<String> BLOCKED = Set.of(
            "AIR", "CAVE_AIR", "VOID_AIR", "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_VOID",
            "STRUCTURE_BLOCK", "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK", "SPAWNER", "TRIAL_SPAWNER",
            "VAULT", "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART", "PLAYER_HEAD", "POTION", "SPLASH_POTION",
            "LINGERING_POTION", "TIPPED_ARROW", "ENCHANTED_BOOK", "FILLED_MAP", "WRITTEN_BOOK",
            "PETRIFIED_OAK_SLAB", "TEST_BLOCK", "TEST_INSTANCE_BLOCK", "FARMLAND");

    private record Category(String id, String name, Material icon, List<double[]> levels) { }

    // prijzen/categorieen worden ook vanuit de netwerk-thread gelezen: volatile + nooit aanpassen na het bouwen
    private volatile Map<Material, Double> prices = new EnumMap<>(Material.class);
    private volatile double priceScale = 1.0;
    private volatile Map<Material, Integer> categoryOf = new EnumMap<>(Material.class);
    private volatile int defaultCategory = 0;
    private volatile List<Category> categories = new ArrayList<>();
    private volatile Map<String, Double> enchantPrices = new ConcurrentHashMap<>();
    private volatile Map<String, Double> potionPrices = new ConcurrentHashMap<>();
    private final AtomicLong loreApplied = new AtomicLong();
    private final Set<UUID> resyncPending = ConcurrentHashMap.newKeySet();
    private volatile String lastLoreError = "geen";

    private final Map<UUID, double[]> soldTotals = new ConcurrentHashMap<>();      // per categorie
    private final Map<UUID, double[]> multiplierCache = new ConcurrentHashMap<>(); // per categorie
    private final Set<UUID> creative = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ItemStack[]> stash = new ConcurrentHashMap<>();
    private final Set<UUID> switching = ConcurrentHashMap.newKeySet();
    private volatile boolean dirty = false;

    private File dataFile;
    private Economy economy;
    private Object worthLore;

    private static class SellHolder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private static class ProgressHolder implements InventoryHolder {
        private Inventory inv;
        private final int category;
        ProgressHolder(int category) { this.category = category; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private static class ItemsHolder implements InventoryHolder {
        private Inventory inv;
        private final int category;
        private final int page;
        private final boolean hasNext;
        private final boolean worthMode;
        private final String query;   // alleen bij zoeken via /worth <zoekterm>
        ItemsHolder(int category, int page, boolean hasNext, boolean worthMode) {
            this(category, page, hasNext, worthMode, null);
        }
        ItemsHolder(int category, int page, boolean hasNext, boolean worthMode, String query) {
            this.category = category;
            this.page = page;
            this.hasNext = hasNext;
            this.worthMode = worthMode;
            this.query = query;
        }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private static class WorthHolder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private record Listed(ItemStack item, double price) { }

    // ------------------------------------------------------------------ start / stop

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("Geen Vault economy gevonden (installeer bv. EssentialsX). Plugin uitgeschakeld.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();

        dataFile = new File(getDataFolder(), "data.yml");
        loadCategories();
        loadData();
        loadPrices();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("sell") != null) getCommand("sell").setExecutor(this);
        if (getCommand("worth") != null) getCommand("worth").setExecutor(this);

        setupWorthLore();
        SellPlaceholderExpansion.tryRegister(this);

        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
        getServer().getScheduler().runTaskTimer(this, () -> refreshAll(), 200L, 600L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> { if (dirty) saveData(); }, 1200L, 6000L);
    }

    @Override
    public void onDisable() {
        // items die in het menu stonden teruggeven
        for (UUID id : new ArrayList<>(stash.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) giveBackStash(p);
        }
        if (worthLore != null) {
            try { WorthLore.unregister(worthLore); } catch (Throwable ignored) { }
        }
        if (dataFile != null) saveData();
    }

    private void setupWorthLore() {
        if (!getConfig().getBoolean("worth-lore.enabled", true)) return;
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().warning("PacketEvents niet gevonden: de waarde in de item-tooltip is uitgeschakeld.");
            return;
        }
        try {
            worthLore = WorthLore.register(this);
            getLogger().info("Worth-tooltip actief (via PacketEvents).");
        } catch (Throwable t) {
            getLogger().warning("Kon worth-tooltip niet starten: " + t);
        }
    }

    // ------------------------------------------------------------------ laden / opslaan

    private void loadCategories() {
        List<double[]> base = new ArrayList<>();
        ConfigurationSection ls = getConfig().getConfigurationSection("progress-levels");
        if (ls != null) {
            for (String k : ls.getKeys(false)) {
                try { base.add(new double[]{Double.parseDouble(k), ls.getDouble(k)}); } catch (NumberFormatException ignored) { }
            }
        }
        if (base.isEmpty()) base.add(new double[]{0, 1.0});
        base.sort(Comparator.comparingDouble(a -> a[0]));

        String color = getConfig().getString("category-name-color", "<gray>");
        List<Category> list = new ArrayList<>();
        ConfigurationSection cs = getConfig().getConfigurationSection("categories");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                if (list.size() >= 9) break;
                ConfigurationSection c = cs.getConfigurationSection(key);
                if (c == null) continue;
                Material icon = Material.matchMaterial(c.getString("icon", "STONE"));
                if (icon == null) icon = Material.STONE;
                double scale = Math.max(0.0001, c.getDouble("scale", 1.0));
                List<double[]> lv = new ArrayList<>();
                for (double[] b : base) lv.add(new double[]{(double) Math.round(b[0] * scale), b[1]});
                list.add(new Category(key, color + MM.stripTags(c.getString("name", key)), icon, lv));
            }
        }
        if (list.isEmpty()) list.add(new Category("all", "<green>Alles", Material.CHEST, base));

        File f = new File(getDataFolder(), "categories.yml");
        if (!f.exists()) saveResource("categories.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Map<Material, Integer> map = new EnumMap<>(Material.class);
        for (int i = 0; i < list.size(); i++) {
            for (String n : y.getStringList("items." + list.get(i).id())) {
                Material m = Material.getMaterial(n);
                if (m != null) map.putIfAbsent(m, i);
            }
        }
        String def = getConfig().getString("default-category", "decor");
        int defIdx = list.size() - 1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(def)) defIdx = i;

        categoryOf = map;
        defaultCategory = defIdx;
        categories = list;
    }

    private void loadData() {
        soldTotals.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("sold");
        if (s == null) return;
        List<Category> cats = categories;
        for (String k : s.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            ConfigurationSection ps = s.getConfigurationSection(k);
            if (ps == null) continue; // oud formaat, overslaan
            double[] arr = new double[cats.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ps.getDouble(cats.get(i).id(), 0);
            soldTotals.put(id, arr);
        }
    }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<Category> cats = categories;
        for (Map.Entry<UUID, double[]> e : soldTotals.entrySet()) {
            double[] arr = e.getValue();
            for (int i = 0; i < arr.length && i < cats.size(); i++) {
                if (arr[i] > 0) y.set("sold." + e.getKey() + "." + cats.get(i).id(), arr[i]);
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().warning("Kon data.yml niet opslaan: " + e.getMessage());
        }
    }

    private static boolean isSellableType(Material m) {
        String n = m.name();
        return m.isItem() && !m.isAir()
                && !n.startsWith("LEGACY_") && !n.startsWith("INFESTED_")
                && !n.endsWith("_SPAWN_EGG") && !BLOCKED.contains(n);
    }

    private void loadPrices() {
        File file = new File(getDataFolder(), "prices.yml");
        if (!file.exists()) saveResource("prices.yml", false);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("prices");
        if (sec == null) sec = yml.createSection("prices");

        Map<Material, Double> map = new EnumMap<>(Material.class);
        boolean changed = false;
        int removed = 0;
        for (String key : new ArrayList<>(sec.getKeys(false))) {
            Material m = Material.getMaterial(key);
            if (m == null || !isSellableType(m)) {
                sec.set(key, null);
                removed++;
                changed = true;
                continue;
            }
            map.put(m, sec.getDouble(key));
        }

        double fallback = getConfig().getDouble("fallback-price", 1.0);
        int added = 0;
        for (Material m : Material.values()) {
            if (!isSellableType(m) || map.containsKey(m)) continue;
            sec.set(m.name(), fallback);
            map.put(m, fallback);
            added++;
            changed = true;
        }

        if (changed) {
            try {
                yml.save(file);
            } catch (IOException e) {
                getLogger().warning("Kon prices.yml niet opslaan: " + e.getMessage());
            }
        }
        priceScale = Math.max(0, getConfig().getDouble("price-scale", 1.0));
        prices = map;
        loadSpecialPrices();
        getLogger().info(map.size() + " prijzen geladen (" + added
                + " automatisch toegevoegd met fallback-prijs, " + removed + " onbekende verwijderd).");
    }

    private void loadSpecialPrices() {
        Map<String, Double> e = new ConcurrentHashMap<>();
        ConfigurationSection es = getConfig().getConfigurationSection("enchanted-books.prices");
        if (es != null) for (String k : es.getKeys(false)) e.put(k.toLowerCase(Locale.ROOT), es.getDouble(k));
        enchantPrices = e;
        Map<String, Double> p = new ConcurrentHashMap<>();
        ConfigurationSection ps = getConfig().getConfigurationSection("potions.prices");
        if (ps != null) for (String k : ps.getKeys(false)) p.put(k.toLowerCase(Locale.ROOT), ps.getDouble(k));
        potionPrices = p;
    }

    // ------------------------------------------------------------------ multiplier

    private int categoryIndex(Material m) {
        return categoryOf.getOrDefault(m, defaultCategory);
    }

    /** Het (live) array met verkochte bedragen van een speler, per categorie. */
    private double[] soldOf(UUID id) {
        int n = categories.size();
        return soldTotals.compute(id, (k, arr) -> (arr == null || arr.length != n)
                ? (arr == null ? new double[n] : Arrays.copyOf(arr, n)) : arr);
    }

    private double progress(int cat, double sold) {
        double m = 1.0;
        for (double[] l : categories.get(cat).levels()) if (sold >= l[0]) m = l[1];
        return Math.min(m, getConfig().getDouble("max-multiplier", 3.0));
    }

    /** {drempel, multiplier} van het volgende level, of null bij max. */
    private double[] nextLevel(int cat, double sold) {
        double current = progress(cat, sold);
        double cap = getConfig().getDouble("max-multiplier", 3.0);
        for (double[] l : categories.get(cat).levels()) {
            if (l[0] > sold && Math.min(l[1], cap) > current) return new double[]{l[0], Math.min(l[1], cap)};
        }
        return null;
    }

    private double globalMultiplier() {
        return Math.max(0, getConfig().getDouble("global-multiplier", 1.0));
    }

    private double[] computeMultipliers(Player p) {
        double[] sold = soldOf(p.getUniqueId());
        double global = globalMultiplier();
        double[] out = new double[categories.size()];
        for (int i = 0; i < out.length; i++) out[i] = progress(i, sold[i]) * global;
        return out;
    }

    private void refreshPlayer(Player p) {
        refreshPlayer(p, false);
    }

    /** Berekent de multipliers opnieuw. Als ze veranderd zijn (of force) worden de tooltips direct ververst. */
    private void refreshPlayer(Player p, boolean force) {
        UUID id = p.getUniqueId();
        double[] old = multiplierCache.get(id);
        double[] now = computeMultipliers(p);
        multiplierCache.put(id, now);
        if (p.getGameMode() == GameMode.CREATIVE) creative.add(id);
        else creative.remove(id);
        if (force || (old != null && !Arrays.equals(old, now))) p.updateInventory();
    }

    private void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
    }

    private void refreshAllForced() {
        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        refreshPlayer(e.getPlayer());
        Bukkit.getScheduler().runTask(this, () -> e.getPlayer().updateInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        giveBackStash(p);
        switching.remove(p.getUniqueId());
        multiplierCache.remove(p.getUniqueId());
        creative.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent e) { resyncInventory(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) resyncInventory(p.getUniqueId());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { resyncInventory(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) { resyncInventory(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) { resyncInventory(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (e.getNewGameMode() == GameMode.CREATIVE) creative.add(id);
        else creative.remove(id);
        // na de wissel het inventory opnieuw sturen: in creative zonder tooltip, daarbuiten met tooltip
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.updateInventory(); }, 2L);
    }

    /** Creative-spelers sturen items terug naar de server: haal onze tooltip er dan weer af zodat hij nooit in een item blijft hangen. */
    @EventHandler
    public void onCreativeSet(InventoryCreativeEvent e) {
        ItemStack cursor = e.getCursor();
        if (cursor == null || cursor.getType().isAir() || !cursor.hasItemMeta()) return;
        ItemMeta meta = cursor.getItemMeta();
        if (!meta.hasLore() || meta.lore() == null || meta.lore().isEmpty()) return;
        String format = MM.stripTags(getConfig().getString("worth-lore.format", "Worth ${price}"));
        int idx = format.indexOf("{price}");
        String prefix = idx >= 0 ? format.substring(0, idx) : format;
        String first = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(meta.lore().get(0));
        if (!prefix.isBlank() && first.startsWith(prefix)) {
            ItemStack clean = cursor.clone();
            clean.lore(null);
            e.setCursor(clean);
        }
    }

    // ------------------------------------------------------------------ prijzen

    /** Prijs per stuk (zonder multiplier), of -1 als het item niet verkocht kan worden. */
    private double unitPrice(ItemStack item, boolean full) {
        Material type = item.getType();
        if (type == Material.ENCHANTED_BOOK) return enchantedBookPrice(item);
        if (type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION) {
            return potionPrice(item);
        }
        Double base = prices.get(item.getType());
        if (base == null || base <= 0) return -1;

        double factor = 1.0;
        double enchantBonus = 0;
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() || meta.hasLore()) return -1;
            if (meta.hasEnchants()) {
                // enchants maken het item meer waard, afhankelijk van enchant en level
                enchantBonus = enchantValue(meta.getEnchants())
                        * getConfig().getDouble("enchanted-items.factor", 0.75);
            }
            if (meta instanceof PotionMeta || meta instanceof EnchantmentStorageMeta
                    || meta instanceof BookMeta || meta instanceof MapMeta || meta instanceof SkullMeta) return -1;
            if (full) {
                if (meta instanceof BundleMeta bm && bm.hasItems()) return -1;
                if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                        && bsm.getBlockState() instanceof Container c && !c.getInventory().isEmpty()) return -1;
            }
            int max = item.getType().getMaxDurability();
            if (max > 0 && meta instanceof Damageable d) {
                factor = Math.max(0.05, 1.0 - (double) d.getDamage() / max);
            }
        }
        return (base * factor + enchantBonus) * priceScale;
    }

    private double enchantedBookPrice(ItemStack item) {
        if (!(item.getItemMeta() instanceof EnchantmentStorageMeta esm)) return -1;
        if (esm.hasDisplayName() || esm.hasLore()) return -1;
        Map<Enchantment, Integer> stored = esm.getStoredEnchants();
        if (stored.isEmpty()) return -1;
        return enchantValue(stored) * priceScale;
    }

    /** Waarde van een set enchants: prijs per level van elke enchant x het level, opgeteld. */
    private double enchantValue(Map<Enchantment, Integer> enchants) {
        double def = getConfig().getDouble("enchanted-books.default-per-level", 50);
        double total = 0;
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            String key = e.getKey().getKey().getKey().toLowerCase(Locale.ROOT);
            total += enchantPrices.getOrDefault(key, def) * e.getValue();
        }
        return total;
    }

    private double potionPrice(ItemStack item) {
        if (!(item.getItemMeta() instanceof PotionMeta pm)) return -1;
        if (pm.hasDisplayName() || pm.hasLore() || pm.hasCustomEffects()) return -1;
        PotionType pt = pm.getBasePotionType();
        if (pt == null) return -1;
        String key = pt.getKey().getKey().toLowerCase(Locale.ROOT);
        double mult = 1.0;
        if (key.startsWith("long_")) {
            key = key.substring(5);
            mult = getConfig().getDouble("potions.long-multiplier", 1.5);
        } else if (key.startsWith("strong_")) {
            key = key.substring(7);
            mult = getConfig().getDouble("potions.strong-multiplier", 1.7);
        }
        double base = potionPrices.getOrDefault(key, getConfig().getDouble("potions.default-price", 10));
        if (item.getType() == Material.SPLASH_POTION) mult *= getConfig().getDouble("potions.splash-multiplier", 1.25);
        if (item.getType() == Material.LINGERING_POTION) mult *= getConfig().getDouble("potions.lingering-multiplier", 2.5);
        return base * mult * priceScale;
    }

    // ---- gebruikt door WorthLore (netwerk-thread, dus alleen thread-safe dingen) ----

    void onLoreApplied() { loreApplied.incrementAndGet(); }

    void onLoreError(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        if (!msg.equals(lastLoreError)) {
            lastLoreError = msg;
            getLogger().warning("Fout in worth-tooltip: " + t);
        }
    }

    /** Stuurt het hele inventory opnieuw (met tooltips) nadat de server een los slot heeft bijgewerkt. */
    void resyncInventory(UUID id) {
        if (!shouldShowLore(id) || !resyncPending.add(id)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            resyncPending.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && shouldShowLore(id)) p.updateInventory();
        }, 2L);
    }

    boolean shouldShowLore(UUID id) {
        return multiplierCache.containsKey(id) && !creative.contains(id);
    }

    double displayUnitPrice(ItemStack item, UUID id) {
        double unit = unitPrice(item, false);
        if (unit < 0) return -1;
        double[] arr = multiplierCache.get(id);
        int c = categoryIndex(item.getType());
        double mult = (arr != null && c < arr.length) ? arr[c] : 1.0;
        return unit * mult;
    }

    Component loreLine(double total) {
        String f = getConfig().getString("worth-lore.format", "<!italic><gray>Worth <green>${price}");
        return MM.deserialize(f.replace("{price}", fmt(total)));
    }

    /** Voor PlaceholderAPI: multiplier_<categorie> en sold_<categorie>. */
    String placeholder(Player p, String params) {
        if (p == null) return "";
        String key = params.toLowerCase(Locale.ROOT);
        int us = key.indexOf('_');
        if (us < 0) return null;
        String type = key.substring(0, us);
        String cat = key.substring(us + 1);
        List<Category> cats = categories;
        for (int i = 0; i < cats.size(); i++) {
            if (!cats.get(i).id().equals(cat)) continue;
            if (type.equals("multiplier")) return fmt(computeMultipliers(p)[i]);
            if (type.equals("sold")) return fmt(soldOf(p.getUniqueId())[i]);
        }
        return null;
    }

    // ------------------------------------------------------------------ menu's

    private String str(String path) {
        return getConfig().getString(path, "");
    }

    private Component line(String path, String... replacements) {
        String s = str(path);
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace(replacements[i], replacements[i + 1]);
        return MM.deserialize(s);
    }

    private ItemStack categoryIcon(Player p, int cat, String clickKey) {
        Category c = categories.get(cat);
        double sold = soldOf(p.getUniqueId())[cat];
        double total = progress(cat, sold) * globalMultiplier();

        List<Component> lore = new ArrayList<>();
        lore.add(line("gui.multiplier-line", "{multiplier}", fmt(total)));
        lore.add(line("gui.sold-line", "{sold}", fmt(sold)));
        double[] next = nextLevel(cat, sold);
        if (next == null) {
            lore.add(line("gui.max-line"));
        } else {
            double cur = 0;
            for (double[] l : c.levels()) if (sold >= l[0]) cur = l[0];
            double frac = next[0] > cur ? (sold - cur) / (next[0] - cur) : 0;
            lore.add(line("gui.next-line", "{next}", fmt(next[1]), "{needed}", fmt(next[0])));
            lore.add(line("gui.progress-line", "{bar}", bar(frac),
                    "{percent}", String.valueOf((int) Math.floor(Math.max(0, Math.min(1, frac)) * 100))));
        }
        if (clickKey != null) {
            lore.add(Component.empty());
            lore.add(line(clickKey));
        }

        ItemStack it = new ItemStack(c.icon());
        it.editMeta(m -> {
            m.displayName(MM.deserialize("<!italic>" + c.name()));
            m.lore(lore);
        });
        return it;
    }

    private static String bar(double frac) {
        int total = 20;
        int filled = (int) Math.round(Math.max(0, Math.min(1, frac)) * total);
        return "<green>" + "|".repeat(filled) + "</green><dark_gray>" + "|".repeat(total - filled) + "</dark_gray>";
    }

    private void openSell(Player p, ItemStack[] items) {
        SellHolder holder = new SellHolder();
        holder.inv = Bukkit.createInventory(holder, 54, MM.deserialize(str("gui.sell-title")));
        for (int i = 0; i < categories.size() && i < 9; i++) {
            holder.inv.setItem(ITEM_SLOTS + i, categoryIcon(p, i, "gui.click-line"));
        }
        if (items != null) {
            for (int i = 0; i < ITEM_SLOTS && i < items.length; i++) holder.inv.setItem(i, items[i]);
        }
        p.openInventory(holder.inv);
    }

    private ItemStack pane(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        it.editMeta(m -> {
            m.displayName(name);
            if (lore != null) m.lore(lore);
        });
        return it;
    }

    private Inventory buildProgress(Player p, int cat) {
        Category c = categories.get(cat);
        ProgressHolder holder = new ProgressHolder(cat);
        holder.inv = Bukkit.createInventory(holder, 54, MM.deserialize(
                str("gui.progress-menu-title").replace("{category}", MM.stripTags(c.name()))));

        // donkere achtergrond
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < 54; i++) holder.inv.setItem(i, filler);

        double sold = soldOf(p.getUniqueId())[cat];
        double cap = getConfig().getDouble("max-multiplier", 3.0);

        // alleen de echte upgrades op de kronkel (x1.0 is het icoon bovenaan)
        List<double[]> up = new ArrayList<>();
        for (double[] l : c.levels()) if (l[1] > 1.0) up.add(l);

        double prev = 0;
        boolean currentMarked = false;
        for (int i = 0; i < up.size() && i < PROGRESS_PATH.length; i++) {
            double[] l = up.get(i);
            boolean reached = sold >= l[0];
            boolean current = !reached && !currentMarked;
            if (current) currentMarked = true;

            List<Component> lore = new ArrayList<>();
            lore.add(line("gui.level-from", "{needed}", fmt(l[0])));
            Material mat;
            String nameKey;
            if (reached) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                nameKey = "gui.level-reached";
            } else if (current) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                nameKey = "gui.level-current";
                double frac = l[0] > prev ? (sold - prev) / (l[0] - prev) : 0;
                frac = Math.max(0, Math.min(1, frac));
                lore.add(line("gui.level-remaining", "{remaining}", fmt(l[0] - sold)));
                lore.add(line("gui.progress-line", "{bar}", bar(frac),
                        "{percent}", String.valueOf((int) Math.floor(frac * 100))));
            } else {
                mat = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
                nameKey = "gui.level-locked";
                lore.add(line("gui.level-remaining", "{remaining}", fmt(l[0] - sold)));
            }
            holder.inv.setItem(PROGRESS_PATH[i], pane(mat,
                    line(nameKey, "{multiplier}", fmt(Math.min(l[1], cap))), lore));
            prev = l[0];
        }

        holder.inv.setItem(PROGRESS_ICON_SLOT, categoryIcon(p, cat, "gui.items-click-line"));
        holder.inv.setItem(BACK_SLOT, pane(Material.RED_STAINED_GLASS_PANE, line("gui.back"), null));
        return holder.inv;
    }

    private List<Listed> categoryItems(Player p, int cat) {
        double mult = computeMultipliers(p)[cat];
        String id = categories.get(cat).id();
        List<Listed> out = new ArrayList<>();

        for (Map.Entry<Material, Double> e : prices.entrySet()) {
            if (e.getValue() <= 0 || categoryIndex(e.getKey()) != cat) continue;
            double price = e.getValue() * priceScale * mult;
            ItemStack it = new ItemStack(e.getKey());
            it.editMeta(m -> m.lore(List.of(loreLine(price))));
            out.add(new Listed(it, price));
        }

        if (id.equals("books")) {
            for (Map.Entry<String, Double> e : enchantPrices.entrySet()) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e.getKey()));
                if (ench == null) continue;
                double price = e.getValue() * priceScale * mult;
                ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
                it.editMeta(m -> {
                    if (m instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(ench, 1, true);
                    m.lore(List.of(loreLine(price)));
                });
                out.add(new Listed(it, price));
            }
        }
        if (id.equals("potions")) {
            for (Map.Entry<String, Double> e : potionPrices.entrySet()) {
                PotionType type = Registry.POTION.get(NamespacedKey.minecraft(e.getKey()));
                if (type == null) continue;
                double price = e.getValue() * priceScale * mult;
                ItemStack it = new ItemStack(Material.POTION);
                it.editMeta(m -> {
                    if (m instanceof PotionMeta pm) pm.setBasePotionType(type);
                    m.lore(List.of(loreLine(price), line("gui.potion-note")));
                });
                out.add(new Listed(it, price));
            }
        }

        out.sort(Comparator.comparingDouble(Listed::price).reversed()
                .thenComparing(l -> l.item().getType().name()));
        return out;
    }

    private Inventory buildItems(Player p, int cat, int requestedPage, boolean worthMode) {
        List<Listed> all = categoryItems(p, cat);
        int pages = Math.max(1, (all.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        ItemsHolder holder = new ItemsHolder(cat, page, page < pages - 1, worthMode);
        String title = str("gui.items-title")
                .replace("{category}", categories.get(cat).name())
                .replace("{page}", String.valueOf(page + 1))
                .replace("{pages}", String.valueOf(pages));
        holder.inv = Bukkit.createInventory(holder, 54, MM.deserialize(title));

        for (int i = 0; i < ITEM_SLOTS; i++) {
            int idx = page * ITEM_SLOTS + i;
            if (idx < all.size()) holder.inv.setItem(i, all.get(idx).item());
        }
        if (page > 0) holder.inv.setItem(45, pane(Material.ARROW, line("gui.prev"), null));
        holder.inv.setItem(49, pane(Material.ARROW, line("gui.back-overview"), null));
        if (holder.hasNext) holder.inv.setItem(53, pane(Material.ARROW, line("gui.next"), null));
        return holder.inv;
    }

    private List<Listed> searchItems(Player p, String query) {
        List<Listed> out = new ArrayList<>();
        for (int c = 0; c < categories.size(); c++) {
            for (Listed l : categoryItems(p, c)) {
                if (l.item().getType().name().toLowerCase(Locale.ROOT).contains(query)) out.add(l);
            }
        }
        out.sort(Comparator.comparing((Listed l) -> l.item().getType().name()).thenComparingDouble(Listed::price));
        return out;
    }

    private Inventory buildSearch(Player p, String query, int requestedPage) {
        List<Listed> all = searchItems(p, query);
        int pages = Math.max(1, (all.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        ItemsHolder holder = new ItemsHolder(-1, page, page < pages - 1, true, query);
        String title = str("gui.search-title")
                .replace("{query}", query.replace('_', ' '))
                .replace("{page}", String.valueOf(page + 1))
                .replace("{pages}", String.valueOf(pages));
        holder.inv = Bukkit.createInventory(holder, 54, MM.deserialize(title));
        for (int i = 0; i < ITEM_SLOTS; i++) {
            int idx = page * ITEM_SLOTS + i;
            if (idx < all.size()) holder.inv.setItem(i, all.get(idx).item());
        }
        if (page > 0) holder.inv.setItem(45, pane(Material.ARROW, line("gui.prev"), null));
        holder.inv.setItem(49, pane(Material.ARROW, line("gui.close"), null));
        if (holder.hasNext) holder.inv.setItem(53, pane(Material.ARROW, line("gui.next"), null));
        return holder.inv;
    }

    private Inventory rebuild(Player p, ItemsHolder ih, int page) {
        return ih.query != null ? buildSearch(p, ih.query, page) : buildItems(p, ih.category, page, ih.worthMode);
    }

    /** Hoofdmenu van /worth: kies een categorie om alle prijzen te zien. */
    private Inventory buildWorthHome(Player p) {
        WorthHolder holder = new WorthHolder();
        holder.inv = Bukkit.createInventory(holder, 27, MM.deserialize(str("gui.worth-title")));
        for (int i = 0; i < categories.size() && i < 9; i++) {
            holder.inv.setItem(9 + i, categoryIcon(p, i, "gui.items-click-line"));
        }
        return holder.inv;
    }

    /** Wisselt van het ene overzichtsmenu naar het andere zonder dat er iets verkocht of teruggegeven wordt. */
    private void switchMenu(Player p, java.util.function.Supplier<Inventory> next) {
        switching.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) p.openInventory(next.get());
        });
    }

    private void giveBackStash(Player p) {
        ItemStack[] items = stash.remove(p.getUniqueId());
        if (items == null) return;
        for (ItemStack it : items) if (it != null) giveBack(p, it);
    }

    private void giveBack(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        leftover.values().forEach(i -> p.getWorld().dropItem(p.getLocation(), i));
    }

    // ------------------------------------------------------------------ menu events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        resyncInventory(player.getUniqueId());   // tooltips kloppend houden na verplaatsen/splitsen
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof ProgressHolder ph) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            if (e.getRawSlot() == BACK_SLOT) {
                UUID id = player.getUniqueId();
                ItemStack[] items = stash.remove(id);
                switching.add(id);
                Bukkit.getScheduler().runTask(this, () -> openSell(player, items));
            } else if (e.getRawSlot() == PROGRESS_ICON_SLOT) {
                switchMenu(player, () -> buildItems(player, ph.category, 0, false));
            }
            return;
        }

        if (holder instanceof WorthHolder) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            int cat = e.getRawSlot() - 9;
            if (cat >= 0 && cat < categories.size() && cat < 9) {
                switchMenu(player, () -> buildItems(player, cat, 0, true));
            }
            return;
        }

        if (holder instanceof ItemsHolder ih) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            int raw = e.getRawSlot();
            if (raw == 45 && ih.page > 0) {
                switchMenu(player, () -> rebuild(player, ih, ih.page - 1));
            } else if (raw == 53 && ih.hasNext) {
                switchMenu(player, () -> rebuild(player, ih, ih.page + 1));
            } else if (raw == 49) {
                if (ih.query != null) Bukkit.getScheduler().runTask(this, () -> player.closeInventory());
                else switchMenu(player, () -> ih.worthMode ? buildWorthHome(player) : buildProgress(player, ih.category));
            }
            return;
        }

        if (holder instanceof SellHolder) {
            int raw = e.getRawSlot();
            if (raw >= ITEM_SLOTS && raw < top.getSize()) {
                e.setCancelled(true);
                int cat = raw - ITEM_SLOTS;
                if (cat < categories.size() && (e.getClick().isLeftClick() || e.getClick().isRightClick())) {
                    ItemStack[] copy = new ItemStack[ITEM_SLOTS];
                    for (int i = 0; i < ITEM_SLOTS; i++) {
                        ItemStack it = top.getItem(i);
                        copy[i] = it == null ? null : it.clone();
                    }
                    UUID id = player.getUniqueId();
                    stash.put(id, copy);
                    switching.add(id);
                    Bukkit.getScheduler().runTask(this, () -> player.openInventory(buildProgress(player, cat)));
                }
                return;
            }
            if (e.getClick() == ClickType.DOUBLE_CLICK) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player dragger) resyncInventory(dragger.getUniqueId());
        Inventory top = e.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof ProgressHolder || holder instanceof ItemsHolder || holder instanceof WorthHolder) {
            e.setCancelled(true);
        } else if (holder instanceof SellHolder) {
            for (int raw : e.getRawSlots()) {
                if (raw >= ITEM_SLOTS && raw < top.getSize()) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ command

    private Component msg(String key, String... replacements) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace(replacements[i], replacements[i + 1]);
        return MM.deserialize(s);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("worth")) return handleWorth(sender, args);
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("worth")) return handleWorth(sender, Arrays.copyOfRange(args, 1, args.length));

        if (sub.equals("reload")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            loadCategories();
            loadPrices();
            refreshAllForced();
            sender.sendMessage(msg("reloaded"));
            return true;
        }
        if (sub.equals("status")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            boolean pe = getServer().getPluginManager().isPluginEnabled("packetevents");
            sender.sendMessage(MM.deserialize("<yellow>SellPlugin status"));
            sender.sendMessage(MM.deserialize("<gray>PacketEvents gevonden: " + (pe ? "<green>ja" : "<red>nee (installeer PacketEvents)")));
            sender.sendMessage(MM.deserialize("<gray>Worth-tooltip actief: " + (worthLore != null ? "<green>ja" : "<red>nee")));
            sender.sendMessage(MM.deserialize("<gray>Items met tooltip verstuurd: <white>" + loreApplied.get()));
            sender.sendMessage(MM.deserialize("<gray>Laatste fout: <white>" + lastLoreError));
            if (sender instanceof Player pl) {
                sender.sendMessage(MM.deserialize("<gray>Jij in creative (geen tooltip): "
                        + (creative.contains(pl.getUniqueId()) ? "<red>ja, ga in survival" : "<green>nee")));
            }
            return true;
        }
        if (sub.equals("global")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            try {
                double v = Double.parseDouble(args[1]);
                getConfig().set("global-multiplier", Math.max(0, v));
                saveConfig();
                refreshAll();
                sender.sendMessage(msg("global-set", "{multiplier}", fmt(v)));
            } catch (Exception e) {
                sender.sendMessage(msg("usage-global"));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Alleen spelers kunnen dit gebruiken.");
            return true;
        }
        if (!player.hasPermission("sell.use")) { player.sendMessage(msg("no-permission")); return true; }

        if (sub.equals("multiplier")) {
            double[] sold = soldOf(player.getUniqueId());
            double[] mult = computeMultipliers(player);
            player.sendMessage(msg("multipliers-header", "{global}", fmt(globalMultiplier())));
            for (int i = 0; i < categories.size(); i++) {
                player.sendMessage(msg("multiplier-line",
                        "{category}", categories.get(i).name(),
                        "{multiplier}", fmt(mult[i]),
                        "{progress}", fmt(progress(i, sold[i]))));
            }
            return true;
        }

        openSell(player, null);
        return true;
    }

    // ------------------------------------------------------------------ /worth

    private boolean isSpecialPriced(Material m) {
        return m == Material.ENCHANTED_BOOK || m == Material.POTION
                || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    private boolean handleWorth(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sell.worth")) { sender.sendMessage(msg("no-permission")); return true; }

        if (args.length == 0) {
            if (sender instanceof Player p) {
                p.openInventory(buildWorthHome(p));
            } else {
                sender.sendMessage("Gebruik: /worth <item>");
            }
            return true;
        }

        Player player = sender instanceof Player pl ? pl : null;

        if (args[0].equalsIgnoreCase("hand")) {
            if (player == null) { sender.sendMessage("Alleen spelers kunnen dit gebruiken."); return true; }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) { player.sendMessage(msg("hold-item")); return true; }
            double unit = unitPrice(hand, true);
            if (unit < 0) { player.sendMessage(msg("not-sellable")); return true; }
            double mult = computeMultipliers(player)[categoryIndex(hand.getType())];
            player.sendMessage(msg("worth-hand",
                    "{item}", hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "{multiplier}", fmt(mult),
                    "{stack}", fmt(unit * mult * hand.getAmount())));
            return true;
        }

        String query = String.join("_", args).toLowerCase(Locale.ROOT);
        List<Material> found = new ArrayList<>();
        Material exact = Material.getMaterial(query.toUpperCase(Locale.ROOT));
        if (exact != null && (prices.containsKey(exact) || isSpecialPriced(exact))) {
            found.add(exact);
        } else {
            List<Material> all = new ArrayList<>(prices.keySet());
            all.addAll(List.of(Material.ENCHANTED_BOOK, Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION));
            for (Material m : all) if (m.name().toLowerCase(Locale.ROOT).contains(query)) found.add(m);
        }

        if (found.isEmpty()) { sender.sendMessage(msg("worth-none")); return true; }
        if (found.size() > 8 && player != null) {
            Bukkit.getScheduler().runTask(this, () -> player.openInventory(buildSearch(player, query, 0)));
            return true;
        }
        if (found.size() > 8) {
            StringBuilder ex = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                if (i > 0) ex.append(", ");
                ex.append(found.get(i).name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            sender.sendMessage(msg("worth-many", "{count}", String.valueOf(found.size()), "{list}", ex.toString()));
            return true;
        }

        for (Material m : found) {
            int cat = categoryIndex(m);
            if (isSpecialPriced(m)) cat = categories.stream().map(Category::id).toList()
                    .indexOf(m == Material.ENCHANTED_BOOK ? "books" : "potions");
            if (cat < 0) cat = defaultCategory;
            String name = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            double mult = player != null ? computeMultipliers(player)[cat] : 1.0;
            if (isSpecialPriced(m)) {
                sender.sendMessage(msg("worth-special", "{item}", name, "{category}", categories.get(cat).name()));
            } else {
                double price = prices.get(m) * priceScale * mult;
                sender.sendMessage(msg("worth-lookup",
                        "{item}", name, "{price}", fmt(price),
                        "{category}", categories.get(cat).name(), "{multiplier}", fmt(mult)));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("worth") || (args.length == 2 && args[0].equalsIgnoreCase("worth"))) {
            String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            if ("hand".startsWith(typed)) names.add("hand");
            for (Material m : prices.keySet()) {
                String n = m.name().toLowerCase(Locale.ROOT);
                if (n.startsWith(typed)) names.add(n);
                if (names.size() >= 60) break;
            }
            return names;
        }
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("worth", "multiplier"));
        if (sender.hasPermission("sell.admin")) out.addAll(List.of("reload", "global", "status"));
        out.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ verkopen

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        UUID id = player.getUniqueId();

        resyncInventory(id);
        if (holder instanceof ProgressHolder || holder instanceof ItemsHolder || holder instanceof WorthHolder) {
            if (switching.remove(id)) return;   // we wisselen naar een ander menu
            giveBackStash(player);              // menu gesloten: items veilig teruggeven
            return;
        }
        if (!(holder instanceof SellHolder)) return;
        if (switching.remove(id)) {             // we wisselen naar het overzicht, niet verkopen
            event.getInventory().clear();
            return;
        }

        int n = categories.size();
        double[] base = new double[n];
        int count = 0;
        ItemStack[] contents = event.getInventory().getContents();
        for (int i = 0; i < ITEM_SLOTS && i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            double unit = unitPrice(item, true);
            if (unit < 0) {
                giveBack(player, item);
                continue;
            }
            base[categoryIndex(item.getType())] += unit * item.getAmount();
            count += item.getAmount();
        }
        event.getInventory().clear();

        double baseTotal = 0;
        for (double b : base) baseTotal += b;
        if (baseTotal <= 0) {
            player.updateInventory();
            return;
        }

        double[] sold = soldOf(id);
        double[] mult = computeMultipliers(player);
        double[] before = new double[n];
        double money = 0;
        for (int i = 0; i < n; i++) {
            before[i] = progress(i, sold[i]);
            money += base[i] * mult[i];
            sold[i] += base[i];     // level telt het bedrag zonder multiplier
        }
        money = Math.round(money * 100.0) / 100.0;
        dirty = true;

        economy.depositPlayer(player, money);
        player.sendMessage(msg("sold",
                "{items}", String.valueOf(count),
                "{money}", String.format(Locale.US, "%,.2f", money),
                "{multiplier}", fmt(money / baseTotal)));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

        for (int i = 0; i < n; i++) {
            double after = progress(i, sold[i]);
            if (after > before[i]) {
                player.sendMessage(msg("category-levelup",
                        "{category}", categories.get(i).name(), "{progress}", fmt(after)));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        }
        refreshPlayer(player, true);
    }

    private static String fmt(double d) {
        String s = String.format(Locale.US, "%,.2f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
