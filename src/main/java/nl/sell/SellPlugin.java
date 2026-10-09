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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Slots 0-44 zijn voor items, de onderste rij (45-53) voor de categorie-iconen. */
    private static final int ITEM_SLOTS = 45;
    private static final int NAV_BACK = 45;
    private static final int NAV_PREV = 48;
    private static final int NAV_INFO = 49;
    private static final int NAV_NEXT = 50;
    private static final int BACK_SLOT = 45;            // rode knop linksonder
    private static final int PROGRESS_ICON_SLOT = 1;    // categorie-icoon bovenaan
    /** De kronkel van level 1 (onder het icoon) tot het laatste level (rechtsboven). 20 plekken. */
    private static final int[] PROGRESS_PATH = {
            10, 19, 28, 37, 38, 39, 30, 21, 12, 13,
            14, 23, 32, 41, 42, 43, 34, 25, 16, 7};

    /** Items die nooit verkocht kunnen worden (niet verkrijgbaar of met data). */
    // Alleen echte admin-items / items met eigen data staan hier. Al het andere kan verkocht worden.
    private static final Set<String> BLOCKED = Set.of(
            "AIR", "CAVE_AIR", "VOID_AIR", "BARRIER", "LIGHT", "STRUCTURE_VOID",
            "STRUCTURE_BLOCK", "JIGSAW", "DEBUG_STICK", "KNOWLEDGE_BOOK",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW", "ENCHANTED_BOOK",
            "FILLED_MAP", "WRITTEN_BOOK", "PETRIFIED_OAK_SLAB", "TEST_BLOCK", "TEST_INSTANCE_BLOCK");

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
    private final Map<UUID, Integer> heldSlot = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMining = new ConcurrentHashMap<>();
    private final Map<UUID, HeldLore> heldLore = new ConcurrentHashMap<>();
    private final Set<UUID> staleHeld = ConcurrentHashMap.newKeySet();
    private final Set<UUID> idleCheck = ConcurrentHashMap.newKeySet();

    /** Wat de client het laatst als worth te zien kreeg op het vastgehouden item. */
    private record HeldLore(int slot, Material type, double total) { }
    private final Map<UUID, List<SaleEntry>> history = new ConcurrentHashMap<>();
    private File historyFile;

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

    /** Eén verkoop in de geschiedenis. top = "MATERIAL:aantal" van de meest waardevolle items. */
    private record SaleEntry(long time, double money, int items, double mult, List<String> top) { }

    private static class HistoryHolder implements InventoryHolder {
        private Inventory inv;
        private final int page;
        private final boolean hasNext;
        HistoryHolder(int page, boolean hasNext) { this.page = page; this.hasNext = hasNext; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

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
        historyFile = new File(getDataFolder(), "history.yml");
        loadHistory();
        loadLang();
        loadCategories();
        loadData();
        loadPrices();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("sell") != null) getCommand("sell").setExecutor(this);
        if (getCommand("worth") != null) getCommand("worth").setExecutor(this);

        setupWorthLore();
        SellPlaceholderExpansion.tryRegister(this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            heldSlot.put(p.getUniqueId(), p.getInventory().getHeldItemSlot());
            refreshPlayer(p);
        }
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

    // ------------------------------------------------------------------ talen

    private static final List<String> BUNDLED_LANGS = List.of("nl", "en", "de", "fr", "es");
    private volatile YamlConfiguration langDisk;   // plugins/SellPlugin/lang/<taal>.yml (aanpasbaar)
    private volatile YamlConfiguration langJar;    // dezelfde taal uit de jar (vangnet voor ontbrekende regels)
    private volatile YamlConfiguration langEn;     // Engels uit de jar (laatste vangnet)

    private YamlConfiguration readJarLang(String code) {
        try (java.io.InputStream in = getResource("lang/" + code + ".yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private void loadLang() {
        String code = getConfig().getString("language", "nl").toLowerCase(Locale.ROOT);
        File dir = new File(getDataFolder(), "lang");
        dir.mkdirs();
        for (String l : BUNDLED_LANGS) {
            File lf = new File(dir, l + ".yml");
            try {
                if (!lf.exists()) {
                    saveResource("lang/" + l + ".yml", false);
                    continue;
                }
                // Nieuwere ingebouwde versie? Oud bestand bewaren als .bak en het nieuwe neerzetten.
                YamlConfiguration jar = readJarLang(l);
                int jarVersion = jar == null ? 0 : jar.getInt("lang-version", 1);
                int diskVersion = YamlConfiguration.loadConfiguration(lf).getInt("lang-version", 0);
                if (diskVersion < jarVersion) {
                    java.nio.file.Files.copy(lf.toPath(), new File(dir, l + ".yml.bak").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    saveResource("lang/" + l + ".yml", true);
                    getLogger().info("Taalbestand " + l + ".yml bijgewerkt naar versie " + jarVersion
                            + " (je oude versie staat in " + l + ".yml.bak).");
                }
            } catch (IOException | IllegalArgumentException e) {
                getLogger().warning("Kon taalbestand " + l + ".yml niet bijwerken: " + e.getMessage());
            }
        }
        File f = new File(dir, code + ".yml");
        langDisk = f.exists() ? YamlConfiguration.loadConfiguration(f) : null;
        langJar = readJarLang(code);
        langEn = readJarLang("en");
        if (langDisk == null && langJar == null) {
            getLogger().warning("Taal '" + code + "' niet gevonden, Engels wordt gebruikt.");
        }
    }

    String currentLanguage() {
        return getConfig().getString("language", "nl").toLowerCase(Locale.ROOT);
    }

    private List<String> availableLanguages() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>(BUNDLED_LANGS);
        File[] files = new File(getDataFolder(), "lang").listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File x : files) set.add(x.getName().substring(0, x.getName().length() - 4));
        return new ArrayList<>(set);
    }

    /** Tekst opzoeken: taalbestand op schijf, dezelfde taal uit de jar, Engels, oude config, standaardwaarde. */
    String tr(String path, String def) {
        for (YamlConfiguration y : new YamlConfiguration[]{langDisk, langJar, langEn}) {
            if (y != null && y.isString(path)) return y.getString(path, def);
        }
        return getConfig().getString(path, def);
    }

    // ------------------------------------------------------------------ laden / opslaan

    private void loadCategories() {
        shortNumbers = getConfig().getBoolean("number-format.short", true);
        smallCaps = getConfig().getBoolean("small-caps", true);
        List<double[]> base = new ArrayList<>();
        ConfigurationSection ls = getConfig().getConfigurationSection("progress-levels");
        if (ls != null) {
            for (String k : ls.getKeys(false)) {
                try { base.add(new double[]{Double.parseDouble(k), ls.getDouble(k)}); } catch (NumberFormatException ignored) { }
            }
        }
        if (base.isEmpty()) base.add(new double[]{0, 1.0});
        base.sort(Comparator.comparingDouble(a -> a[0]));

        // multipliers.yml: eigen levels per categorie (heeft voorrang op progress-levels + scale uit config.yml)
        File mfile = new File(getDataFolder(), "multipliers.yml");
        if (!mfile.exists()) {
            try { saveResource("multipliers.yml", false); } catch (IllegalArgumentException ignored) { }
        }
        YamlConfiguration mult = YamlConfiguration.loadConfiguration(mfile);
        double baseMult = mult.getDouble("base-multiplier", 1.0);

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
                ConfigurationSection ms = mult.getConfigurationSection(key);
                if (ms != null) {
                    List<double[]> custom = new ArrayList<>();
                    for (String lk : ms.getKeys(false)) {
                        ConfigurationSection e = ms.getConfigurationSection(lk);
                        if (e == null) continue;
                        double need = e.getDouble("amountNeeded", -1);
                        double m = e.getDouble("multi", -1);
                        if (need < 0 || m <= 0) continue;
                        custom.add(new double[]{need, m});
                    }
                    if (!custom.isEmpty()) {
                        custom.sort(Comparator.comparingDouble(a -> a[0]));
                        if (custom.get(0)[0] > 0) custom.add(0, new double[]{0, baseMult});
                        lv = custom;
                    }
                }
                list.add(new Category(key, color + sc(MM.stripTags(tr("categories." + key, c.getString("name", key)))), icon, lv));
            }
        }
        if (list.isEmpty()) list.add(new Category("all", "<green>Alles", Material.CHEST, base));

        syncBundled("categories.yml", "categories-version");
        File f = new File(getDataFolder(), "categories.yml");
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

    private void loadHistory() {
        history.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(historyFile);
        ConfigurationSection root = y.getConfigurationSection("history");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            List<SaleEntry> list = new CopyOnWriteArrayList<>();
            for (Map<?, ?> m : root.getMapList(k)) {
                try {
                    List<String> top = new ArrayList<>();
                    Object t = m.get("top");
                    if (t instanceof List<?> l) for (Object o : l) top.add(String.valueOf(o));
                    list.add(new SaleEntry(((Number) m.get("t")).longValue(), ((Number) m.get("m")).doubleValue(),
                            ((Number) m.get("i")).intValue(), ((Number) m.get("x")).doubleValue(), top));
                } catch (Exception ignored) { }
            }
            if (!list.isEmpty()) history.put(id, list);
        }
    }

    private synchronized void saveHistory() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<SaleEntry>> e : history.entrySet()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (SaleEntry se : e.getValue()) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("t", se.time());
                m.put("m", se.money());
                m.put("i", se.items());
                m.put("x", se.mult());
                m.put("top", se.top());
                out.add(m);
            }
            y.set("history." + e.getKey(), out);
        }
        try {
            getDataFolder().mkdirs();
            y.save(historyFile);
        } catch (IOException ex) {
            getLogger().warning("Kon history.yml niet opslaan: " + ex.getMessage());
        }
    }

    private synchronized void saveData() {
        saveHistory();
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
                && !n.startsWith("LEGACY_") && !BLOCKED.contains(n);
    }

    /**
     * Vervangt een bestand op schijf door de nieuwere versie uit de jar als het versienummer ("version-key")
     * in de jar hoger is. De oude versie wordt bewaard als <naam>.bak.
     */
    private void syncBundled(String name, String versionKey) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) { saveResource(name, false); return; }
        try (java.io.InputStream in = getResource(name)) {
            if (in == null) return;
            YamlConfiguration jar = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            int jarVersion = jar.getInt(versionKey, 0);
            int diskVersion = YamlConfiguration.loadConfiguration(file).getInt(versionKey, 0);
            if (diskVersion < jarVersion) {
                File bak = new File(getDataFolder(), name + ".bak");
                java.nio.file.Files.copy(file.toPath(), bak.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                saveResource(name, true);
                getLogger().info(name + " bijgewerkt naar versie " + jarVersion + " (oude versie staat in " + name + ".bak).");
            }
        } catch (Exception e) {
            getLogger().warning("Kon " + name + " niet controleren: " + e.getMessage());
        }
    }

    private void loadPrices() {
        syncBundled("prices.yml", "prices-version");
        File file = new File(getDataFolder(), "prices.yml");
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
        heldSlot.put(e.getPlayer().getUniqueId(), e.getPlayer().getInventory().getHeldItemSlot());
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
        heldSlot.remove(p.getUniqueId());
        lastMining.remove(p.getUniqueId());
        heldLore.remove(p.getUniqueId());
        staleHeld.remove(p.getUniqueId());
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent e) { resyncInventory(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) resyncInventory(p.getUniqueId());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { resyncInventory(e.getPlayer().getUniqueId()); }

    // Tijdens het hakken houden we de worth-tekst van het vastgehouden item vast (zie frozenTotal).
    @EventHandler
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) { touchMining(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onBlockDamage(org.bukkit.event.block.BlockDamageEvent e) { touchMining(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) { touchMining(e.getPlayer().getUniqueId()); }

    private void touchMining(UUID id) {
        lastMining.put(id, System.currentTimeMillis());
        if (idleCheck.add(id)) scheduleIdleCheck(id);
    }

    /** Hoe lang (ms) je niet meer hakt voordat het vastgehouden item de juiste worth krijgt. */
    private long miningIdleMs() {
        return Math.max(100, getConfig().getLong("worth-lore.mining-idle-ms", 250));
    }

    /** Als je een tijdje niet meer hakt, krijgt het vastgehouden item de juiste worth weer. */
    private void scheduleIdleCheck(UUID id) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Long last = lastMining.get(id);
            if (last != null && System.currentTimeMillis() - last < miningIdleMs()) {
                scheduleIdleCheck(id);
                return;
            }
            idleCheck.remove(id);
            refreshHeldNow(id);
        }, 2L);
    }

    /** Stuurt het inventory opnieuw, maar alleen als de worth van het vastgehouden item achterliep. */
    private void refreshHeldNow(UUID id) {
        boolean stale = staleHeld.remove(id);
        // Belangrijk: de freeze loslaten, anders stuurt de verversing zelf weer de oude tekst
        lastMining.remove(id);
        if (!stale) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline() && shouldShowLore(id)) p.updateInventory();
    }

    /** Je laat de muisknop los of kijkt naar een ander blok: direct bijwerken, geen wachttijd. */
    @EventHandler
    public void onDamageAbort(org.bukkit.event.block.BlockDamageAbortEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastMining.remove(id);
        Bukkit.getScheduler().runTask(this, () -> refreshHeldNow(id));
    }

    @EventHandler
    public void onHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        heldSlot.put(e.getPlayer().getUniqueId(), e.getNewSlot());
        resyncInventory(e.getPlayer().getUniqueId());   // oude en nieuwe vastgehouden slot wisselen van tooltip
    }

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
        String format = MM.stripTags(tr("worth-lore.format", "Worth ${price}"));
        int idx = format.indexOf("{price}");
        String prefix = sc(idx >= 0 ? format.substring(0, idx) : format);
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
            int max = item.getType().getMaxDurability();
            if (max > 0 && meta instanceof Damageable d) {
                factor = Math.max(0.05, 1.0 - (double) d.getDamage() / max);
            }
        }
        return (base * factor + enchantBonus) * priceScale;
    }

    private void tally(ItemStack item, Map<Material, double[]> map, int depth) {
        double unit = unitPrice(item, true);
        double[] a = map.computeIfAbsent(item.getType(), k -> new double[2]);
        a[0] += item.getAmount();
        a[1] += Math.max(0, unit) * item.getAmount();
        if (depth > 3) return;
        for (ItemStack in : innerItems(item)) tally(in, map, depth + 1);
    }

    private void recordSale(UUID id, double money, int items, double mult, Map<Material, double[]> tallied) {
        List<Map.Entry<Material, double[]>> sorted = new ArrayList<>(tallied.entrySet());
        sorted.sort((x, y) -> Double.compare(y.getValue()[1], x.getValue()[1]));
        List<String> top = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < 5; i++) {
            top.add(sorted.get(i).getKey().name() + ":" + (int) sorted.get(i).getValue()[0]);
        }
        List<SaleEntry> list = history.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
        list.add(0, new SaleEntry(System.currentTimeMillis(), money, items, mult, top));
        int max = Math.max(10, getConfig().getInt("history.max-entries", 200));
        while (list.size() > max) list.remove(list.size() - 1);
        dirty = true;
    }

    /** Items die in een shulker box of bundle zitten (leeg als het geen container is). */
    private List<ItemStack> innerItems(ItemStack item) {
        List<ItemStack> out = new ArrayList<>();
        if (!item.hasItemMeta()) return out;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                && bsm.getBlockState() instanceof Container c) {
            for (ItemStack in : c.getInventory().getContents()) {
                if (in != null && !in.getType().isAir()) out.add(in);
            }
        } else if (meta instanceof BundleMeta bm && bm.hasItems()) {
            out.addAll(bm.getItems());
        }
        return out;
    }

    /**
     * Telt de waarde van een item PLUS alles wat erin zit op, per categorie (zonder multiplier).
     * Geeft false terug als het item of iets erin niet verkocht kan worden.
     */
    private boolean addValue(ItemStack item, double[] perCat, int depth) {
        double unit = unitPrice(item, true);
        if (unit < 0 || depth > 3) return false;
        perCat[categoryIndex(item.getType())] += unit * item.getAmount();
        for (ItemStack in : innerItems(item)) {
            if (!addValue(in, perCat, depth + 1)) return false;
        }
        return true;
    }

    /** Totale waarde (met multipliers) van een stapel inclusief inhoud, of -1 als niet verkoopbaar. */
    private double stackValue(ItemStack item, double[] mult) {
        double[] perCat = new double[categories.size()];
        if (!addValue(item, perCat, 0)) return -1;
        double total = 0;
        for (int i = 0; i < perCat.length; i++) total += perCat[i] * (mult != null && i < mult.length ? mult[i] : 1.0);
        return total;
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
        return potionPriceFor(pt, item.getType()) * priceScale;
    }

    /** Prijs van een potion-type in een bepaalde vorm (drinkbaar, splash, lingering), zonder scale. */
    private double potionPriceFor(PotionType pt, Material kind) {
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
        if (kind == Material.SPLASH_POTION) mult *= getConfig().getDouble("potions.splash-multiplier", 1.25);
        if (kind == Material.LINGERING_POTION) mult *= getConfig().getDouble("potions.lingering-multiplier", 2.5);
        return base * mult;
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

    private String heldMode() {
        return getConfig().getString("worth-lore.held-slot", "freeze").toLowerCase(Locale.ROOT);
    }

    boolean isHeldSlot(UUID id, int windowId, int slot) {
        if (windowId != 0) return false;
        Integer held = heldSlot.get(id);
        return held != null && slot == 36 + held;
    }

    /** Alleen bij held-slot: hide. Dan krijgt het vastgehouden item nooit een worth-regel. */
    boolean skipSlot(UUID id, int windowId, int slot) {
        return heldMode().equals("hide") && isHeldSlot(id, windowId, slot);
    }

    /**
     * Verandert de tekst van het vastgehouden item terwijl je hakt (bv. door oppakken), dan reset
     * Minecraft het hakken. Tijdens het hakken sturen we daarom dezelfde tekst als de vorige keer.
     * Zodra je stopt wordt de juiste waarde weer getoond.
     */
    Double frozenTotal(UUID id, int slot, Material type) {
        if (!heldMode().equals("freeze")) return null;
        Long last = lastMining.get(id);
        if (last == null || System.currentTimeMillis() - last > miningIdleMs() + 150) return null;
        HeldLore h = heldLore.get(id);
        if (h == null || h.slot() != slot || h.type() != type) return null;
        staleHeld.add(id);
        // Zorg dat er altijd een controle volgt, ook als de vorige al klaar was
        if (idleCheck.add(id)) Bukkit.getScheduler().runTask(this, () -> scheduleIdleCheck(id));
        return h.total();
    }

    void rememberHeld(UUID id, int slot, Material type, double total) {
        heldLore.put(id, new HeldLore(slot, type, total));
    }

    boolean shouldShowLore(UUID id) {
        return multiplierCache.containsKey(id) && !creative.contains(id);
    }

    double displayUnitPrice(ItemStack item, UUID id) {
        double total = stackValue(item, multiplierCache.get(id));
        if (total < 0) return -1;
        return total / Math.max(1, item.getAmount());
    }

    Component loreLine(double total) {
        String f = scMini(tr("worth-lore.format", "<!italic><gray>Worth <green>${price}"));
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

    private static final String SC_FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String SC_TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** Gewone tekst naar kleine hoofdletters (ᴅɪᴛ ʟᴇᴛᴛᴇʀᴛʏᴘᴇ). */
    static String sc(String text) {
        if (!smallCaps) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            int i = SC_FROM.indexOf(Character.toLowerCase(ch));
            sb.append(i >= 0 ? SC_TO.charAt(i) : ch);
        }
        return sb.toString();
    }

    /** Zet alleen de zichtbare tekst om; <tags> en {placeholders} blijven ongemoeid. */
    static String scMini(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        StringBuilder chunk = new StringBuilder();
        char close = 0;
        for (char ch : text.toCharArray()) {
            if (close != 0) {
                sb.append(ch);
                if (ch == close) close = 0;
            } else if (ch == '<' || ch == '{') {
                sb.append(sc(chunk.toString()));
                chunk.setLength(0);
                sb.append(ch);
                close = ch == '<' ? '>' : '}';
            } else {
                chunk.append(ch);
            }
        }
        sb.append(sc(chunk.toString()));
        return sb.toString();
    }

    private Component title(String path, String... replacements) {
        String s = scMini(str(path));
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace(replacements[i], replacements[i + 1]);
        return MM.deserialize(applyTheme(s));
    }

    private String str(String path) {
        return tr(path, "");
    }

    private Component line(String path, String... replacements) {
        String s = scMini(str(path));
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace(replacements[i], replacements[i + 1]);
        return MM.deserialize(applyTheme(s));
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
        holder.inv = Bukkit.createInventory(holder, 54, title("gui.sell-title"));
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
        holder.inv = Bukkit.createInventory(holder, 54,
                title("gui.progress-menu-title", "{category}", MM.stripTags(c.name())));

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
            double def = getConfig().getDouble("enchanted-books.default-per-level", 50);
            for (Enchantment ench : Registry.ENCHANTMENT) {
                String key = ench.getKey().getKey().toLowerCase(Locale.ROOT);
                double per = enchantPrices.getOrDefault(key, def);
                for (int lvl = 1; lvl <= Math.max(1, ench.getMaxLevel()); lvl++) {
                    final int level = lvl;
                    double price = per * level * priceScale * mult;
                    ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
                    it.editMeta(m -> {
                        if (m instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(ench, level, true);
                        m.lore(List.of(loreLine(price)));
                    });
                    out.add(new Listed(it, price));
                }
            }
        }
        if (id.equals("potions")) {
            Material[] kinds = {Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION};
            for (PotionType type : Registry.POTION) {
                for (Material kind : kinds) {
                    double price = potionPriceFor(type, kind) * priceScale * mult;
                    ItemStack it = new ItemStack(kind);
                    it.editMeta(m -> {
                        if (m instanceof PotionMeta pm) pm.setBasePotionType(type);
                        m.lore(List.of(loreLine(price)));
                    });
                    out.add(new Listed(it, price));
                }
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
        holder.inv = Bukkit.createInventory(holder, 54, title(worthMode ? "gui.worth-items-title" : "gui.items-title",
                "{category}", categories.get(cat).name(),
                "{page}", String.valueOf(page + 1), "{pages}", String.valueOf(pages)));

        for (int i = 0; i < ITEM_SLOTS; i++) {
            int idx = page * ITEM_SLOTS + i;
            if (idx < all.size()) holder.inv.setItem(i, all.get(idx).item());
        }
        navBar(holder.inv, page, pages, worthMode ? "gui.back-worth" : "gui.back-overview");
        return holder.inv;
    }

    /** Onderste rij van alle lijst-menu's: terug (rood) links, pijlen en pagina-info in het midden. */
    private void navBar(Inventory inv, int page, int pages, String backKey) {
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(NAV_BACK, pane(Material.RED_STAINED_GLASS_PANE, line(backKey), null));
        if (page > 0) inv.setItem(NAV_PREV, pane(Material.ARROW, line("gui.prev"), null));
        if (page < pages - 1) inv.setItem(NAV_NEXT, pane(Material.ARROW, line("gui.next"), null));
        inv.setItem(NAV_INFO, pane(Material.PAPER,
                line("gui.page-info", "{page}", String.valueOf(page + 1), "{pages}", String.valueOf(pages)), null));
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
        holder.inv = Bukkit.createInventory(holder, 54, title("gui.search-title",
                "{query}", sc(query.replace('_', ' ')),
                "{page}", String.valueOf(page + 1), "{pages}", String.valueOf(pages)));
        for (int i = 0; i < ITEM_SLOTS; i++) {
            int idx = page * ITEM_SLOTS + i;
            if (idx < all.size()) holder.inv.setItem(i, all.get(idx).item());
        }
        navBar(holder.inv, page, pages, "gui.close");
        return holder.inv;
    }

    private Inventory rebuild(Player p, ItemsHolder ih, int page) {
        return ih.query != null ? buildSearch(p, ih.query, page) : buildItems(p, ih.category, page, ih.worthMode);
    }

    private static String niceName(String material) {
        return sc(material.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private Inventory buildHistory(Player p, int requestedPage) {
        List<SaleEntry> all = history.getOrDefault(p.getUniqueId(), List.of());
        int pages = Math.max(1, (all.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        HistoryHolder holder = new HistoryHolder(page, page < pages - 1);
        holder.inv = Bukkit.createInventory(holder, 54, title("gui.history-title",
                "{page}", String.valueOf(page + 1), "{pages}", String.valueOf(pages)));

        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern(getConfig().getString("history.date-format", "dd-MM HH:mm"))
                .withZone(ZoneId.systemDefault());
        for (int i = 0; i < ITEM_SLOTS; i++) {
            int idx = page * ITEM_SLOTS + i;
            if (idx >= all.size()) break;
            SaleEntry se = all.get(idx);

            Material icon = Material.PAPER;
            if (!se.top().isEmpty()) {
                Material m = Material.matchMaterial(se.top().get(0).split(":")[0]);
                if (m != null && m.isItem() && !m.isAir()) icon = m;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(line("gui.history-money", "{money}", fmt(se.money())));
            lore.add(line("gui.history-items", "{items}", String.valueOf(se.items())));
            lore.add(line("gui.history-mult", "{multiplier}", fmt(se.mult())));
            if (!se.top().isEmpty()) {
                lore.add(Component.empty());
                lore.add(line("gui.history-top-header"));
                for (String t : se.top()) {
                    String[] parts = t.split(":");
                    if (parts.length < 2) continue;
                    lore.add(line("gui.history-top-line", "{count}", parts[1], "{item}", niceName(parts[0])));
                }
            }
            ItemStack it = new ItemStack(icon);
            it.editMeta(m -> {
                m.displayName(line("gui.history-entry-name", "{number}", String.valueOf(all.size() - idx), "{date}",
                        dateFmt.format(Instant.ofEpochMilli(se.time()))));
                m.lore(lore);
                m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            });
            holder.inv.setItem(i, it);
        }

        navBar(holder.inv, page, pages, "gui.back");
        double total = 0;
        for (SaleEntry se : all) total += se.money();
        holder.inv.setItem(NAV_INFO, pane(Material.GOLD_INGOT, line("gui.history-summary-name"), List.of(
                line("gui.history-summary-total", "{money}", fmt(total)),
                line("gui.history-summary-sales", "{sales}", String.valueOf(all.size())),
                line("gui.page-info", "{page}", String.valueOf(page + 1), "{pages}", String.valueOf(pages)))));
        if (all.isEmpty()) {
            holder.inv.setItem(22, pane(Material.BARRIER, line("gui.history-empty"), null));
        }
        return holder.inv;
    }

    /** Hoofdmenu van /worth: kies een categorie om alle prijzen te zien. */
    private Inventory buildWorthHome(Player p) {
        WorthHolder holder = new WorthHolder();
        holder.inv = Bukkit.createInventory(holder, 27, title("gui.worth-title"));
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < 27; i++) holder.inv.setItem(i, filler);
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

        if (holder instanceof HistoryHolder hh) {
            e.setCancelled(true);
            if (e.getClickedInventory() != top) return;
            int raw = e.getRawSlot();
            if (raw == NAV_PREV && hh.page > 0) {
                switchMenu(player, () -> buildHistory(player, hh.page - 1));
            } else if (raw == NAV_NEXT && hh.hasNext) {
                switchMenu(player, () -> buildHistory(player, hh.page + 1));
            } else if (raw == NAV_BACK) {
                switching.add(player.getUniqueId());
                Bukkit.getScheduler().runTask(this, () -> openSell(player, null));
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
            if (raw == NAV_PREV && ih.page > 0) {
                switchMenu(player, () -> rebuild(player, ih, ih.page - 1));
            } else if (raw == NAV_NEXT && ih.hasNext) {
                switchMenu(player, () -> rebuild(player, ih, ih.page + 1));
            } else if (raw == NAV_BACK) {
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
        if (holder instanceof ProgressHolder || holder instanceof ItemsHolder || holder instanceof WorthHolder
                || holder instanceof HistoryHolder) {
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

    /**
     * Vervangt <theme>...</theme> in teksten door de themakleuren uit config.yml (theme-colors).
     * Meerdere kleuren = een kleurverloop (gradient), een enkele kleur = effen kleur.
     */
    private String applyTheme(String s) {
        if (!s.contains("<theme>")) return s;
        List<String> colors = new ArrayList<>();
        for (String c : getConfig().getStringList("theme-colors")) {
            if (c != null && c.matches("#[0-9a-fA-F]{6}")) colors.add(c);
        }
        if (colors.isEmpty()) colors = List.of("#FFB347", "#FFA531", "#FF981B", "#FF8C00");
        String open;
        String close;
        if (colors.size() == 1) {
            open = "<" + colors.get(0) + ">";
            close = "</" + colors.get(0) + ">";
        } else {
            open = "<gradient:" + String.join(":", colors) + ">";
            close = "</gradient>";
        }
        return s.replace("<theme>", open).replace("</theme>", close);
    }

    private Component msg(String key, String... replacements) {
        String s = tr("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace(replacements[i], replacements[i + 1]);
        return MM.deserialize(applyTheme(s));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (cmd.getName().equalsIgnoreCase("worth")) return handleWorth(sender, args);
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("worth")) return handleWorth(sender, Arrays.copyOfRange(args, 1, args.length));

        if (sub.equals("reload")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            loadLang();
            loadCategories();
            loadPrices();
            refreshAllForced();
            sender.sendMessage(msg("reloaded"));
            return true;
        }
        if (sub.equals("language") || sub.equals("lang") || sub.equals("taal")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            List<String> codes = availableLanguages();
            if (args.length < 2) {
                sender.sendMessage(msg("language-current", "{language}", currentLanguage(), "{list}", String.join(", ", codes)));
                return true;
            }
            String code = args[1].toLowerCase(Locale.ROOT);
            if (!codes.contains(code)) {
                sender.sendMessage(msg("language-unknown", "{list}", String.join(", ", codes)));
                return true;
            }
            getConfig().set("language", code);
            saveConfig();
            loadLang();
            loadCategories();
            refreshAllForced();
            sender.sendMessage(msg("language-set", "{language}", code));
            return true;
        }
        if (sub.equals("status")) {
            if (!sender.hasPermission("sell.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            boolean pe = getServer().getPluginManager().isPluginEnabled("packetevents");
            sender.sendMessage(msg("status-title"));
            sender.sendMessage(msg("status-packetevents", "{state}", tr("messages." + (pe ? "state-yes" : "state-no-install"), "")));
            sender.sendMessage(msg("status-lore", "{state}", tr("messages." + (worthLore != null ? "state-yes" : "state-no"), "")));
            sender.sendMessage(msg("status-lore-count", "{count}", String.valueOf(loreApplied.get())));
            sender.sendMessage(msg("status-last-error", "{error}", MM.escapeTags(lastLoreError)));
            if (sender instanceof Player pl) {
                sender.sendMessage(msg("status-creative", "{state}",
                        tr("messages." + (creative.contains(pl.getUniqueId()) ? "creative-yes" : "creative-no"), "")));
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
            sender.sendMessage(msg("players-only"));
            return true;
        }
        if (sub.equals("history")) {
            if (!player.hasPermission("sell.history")) { player.sendMessage(msg("no-permission")); return true; }
            Bukkit.getScheduler().runTask(this, () -> player.openInventory(buildHistory(player, 0)));
            return true;
        }
        if (sub.equals("multiplier")) {
            if (!player.hasPermission("sell.multiplier")) { player.sendMessage(msg("no-permission")); return true; }
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

        if (!player.hasPermission("sell.use")) { player.sendMessage(msg("no-permission")); return true; }
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
                sender.sendMessage(msg("usage-worth"));
            }
            return true;
        }

        Player player = sender instanceof Player pl ? pl : null;

        if (args[0].equalsIgnoreCase("hand")) {
            if (player == null) { sender.sendMessage(msg("players-only")); return true; }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) { player.sendMessage(msg("hold-item")); return true; }
            double[] mults = computeMultipliers(player);
            double total = stackValue(hand, mults);
            if (total < 0) { player.sendMessage(msg("not-sellable")); return true; }
            double mult = mults[categoryIndex(hand.getType())];
            player.sendMessage(msg("worth-hand",
                    "{item}", hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "{multiplier}", fmt(mult),
                    "{stack}", fmt(total)));
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
        if ((cmd.getName().equalsIgnoreCase("worth") || (args.length == 2 && args[0].equalsIgnoreCase("worth")))
                && sender.hasPermission("sell.worth")) {
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
        if (args.length == 2 && args[0].equalsIgnoreCase("language") && sender.hasPermission("sell.admin")) {
            List<String> codes = availableLanguages();
            codes.removeIf(c -> !c.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return codes;
        }
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        if (sender.hasPermission("sell.worth")) out.add("worth");
        if (sender.hasPermission("sell.multiplier")) out.add("multiplier");
        if (sender.hasPermission("sell.history")) out.add("history");
        if (sender.hasPermission("sell.admin")) out.addAll(List.of("reload", "global", "status", "language"));
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
        if (holder instanceof ProgressHolder || holder instanceof ItemsHolder || holder instanceof WorthHolder
                || holder instanceof HistoryHolder) {
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
        Map<Material, double[]> tallied = new EnumMap<>(Material.class);
        for (int i = 0; i < ITEM_SLOTS && i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            double[] part = new double[n];
            if (!addValue(item, part, 0)) {   // item of iets in de shulker kan niet verkocht worden: alles terug
                giveBack(player, item);
                continue;
            }
            for (int c = 0; c < n; c++) base[c] += part[c];
            tally(item, tallied, 0);
            count += item.getAmount();
            for (ItemStack in : innerItems(item)) count += in.getAmount();
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
        recordSale(id, money, count, money / baseTotal, tallied);
        player.sendMessage(msg("sold",
                "{items}", String.valueOf(count),
                "{money}", fmt(money)));
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

    /** Zet op false in config (number-format.short) om volledige getallen te tonen. */
    private static volatile boolean shortNumbers = true;
    static volatile boolean smallCaps = true;

    private static String fmtExact(double d) {
        String s = String.format(Locale.US, "%,.2f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** 999 -> 999, 1500 -> 1.5k, 2500000 -> 2.5m, 3e9 -> 3b, 4e12 -> 4t */
    private static String fmt(double d) {
        if (!shortNumbers || Math.round(Math.abs(d) * 100) / 100.0 < 1000) return fmtExact(d);
        String[] suffix = {"k", "m", "b", "t"};
        double v = d;
        int idx = -1;
        while (Math.abs(v) >= 1000 && idx < suffix.length - 1) {
            v /= 1000;
            idx++;
        }
        // 999.999k wordt afgerond naar 1000k: dan door naar de volgende letter
        if (Math.round(Math.abs(v) * 100) / 100.0 >= 1000 && idx < suffix.length - 1) {
            v /= 1000;
            idx++;
        }
        return fmtExact(v) + suffix[idx];
    }
}
