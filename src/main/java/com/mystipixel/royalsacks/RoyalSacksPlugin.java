package com.mystipixel.royalsacks;

import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalcommon.storage.MainThread;
import com.mystipixel.royalsacks.api.SacksApi;
import com.mystipixel.royalsacks.command.SacksCommand;
import com.mystipixel.royalsacks.gui.SackHolder;
import com.mystipixel.royalsacks.gui.SackMenu;
import com.mystipixel.royalsacks.gui.SackMenuListener;
import com.mystipixel.royalsacks.hooks.SacksExpansion;
import com.mystipixel.royalsacks.hooks.SkyblockSave;
import com.mystipixel.royalsacks.hooks.VaultHook;
import com.mystipixel.royalsacks.item.ItemMatcher;
import com.mystipixel.royalsacks.message.MessageManager;
import com.mystipixel.royalsacks.sack.PickupListener;
import com.mystipixel.royalsacks.sack.SackDefinition;
import com.mystipixel.royalsacks.sack.SackRegistry;
import com.mystipixel.royalsacks.sack.SackService;
import com.mystipixel.royalsacks.scope.ScopeResolver;
import com.mystipixel.royalsacks.storage.SackStorage;
import com.mystipixel.royalsacks.util.BundledYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * RoyalSacks: per-profile material storage that items go into when picked up. Scoped per RoyalSkyblock
 * profile when PlaceholderAPI resolves one (see {@link ScopeResolver}), otherwise one set of sacks per player.
 */
public final class RoyalSacksPlugin extends JavaPlugin {

    private static final List<String> BUNDLED_SACKS = List.of("agronomy", "mining", "combat", "foraging", "fishing");

    private SackStorage storage;
    private DbExecutor db;
    private ScopeResolver scopes;
    private MessageManager messages;
    private SackService service;
    private SackMenu menu;
    private BukkitTask flushTask;
    private Set<String> itemIds = Set.of();
    // typed as Object so this class loads without PlaceholderAPI
    private Object expansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "menus.yml").exists()) {
            saveResource("menus.yml", false);
        }
        File sackFolder = new File(getDataFolder(), "sacks");
        if (!sackFolder.exists()) {
            for (String sack : BUNDLED_SACKS) {
                saveResource("sacks/" + sack + ".yml", false);
            }
        }
        try {
            this.storage = openStorage();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Storage failed to initialise, disabling RoyalSacks.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.db = new DbExecutor("RoyalSacks", MainThread.bukkit(this), getLogger());
        this.messages = new MessageManager(this);
        VaultHook vault = new VaultHook();
        if (!vault.setup()) {
            getLogger().info("No Vault economy found; sack tiers that cost coins can't be bought.");
        }
        this.service = new SackService(db, storage, messages, vault, new SkyblockSave(), getLogger(), () -> scopes);
        this.menu = new SackMenu(service, messages, BundledYaml.load(this, "menus.yml"));
        // eco plugins register their items a few ticks after the server finishes starting, so EcoItems ids
        // don't resolve yet: load quietly now, then retry until they appear
        boolean eco = getServer().getPluginManager().isPluginEnabled("eco");
        reload(!eco);
        if (eco && service.matcher().unknownCount() > 0) {
            getServer().getScheduler().runTaskLater(this, () -> resolveItems(1, service.matcher().unknownCount()), 1L);
        }

        getServer().getPluginManager().registerEvents(new PickupListener(service), this);
        getServer().getPluginManager().registerEvents(new SackMenuListener(this, service, menu), this);
        SacksCommand command = new SacksCommand(this);
        getCommand("sacks").setExecutor(command);
        getCommand("sacks").setTabCompleter(command);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            SacksExpansion placeholders = new SacksExpansion(service, getPluginMeta().getVersion());
            placeholders.register();
            this.expansion = placeholders;
        }
        SacksApi.bind(service);

        // a reload with players online
        for (Player player : getServer().getOnlinePlayers()) {
            service.join(player);
        }
        getLogger().info("RoyalSacks enabled with " + service.registry().all().size() + " sacks holding "
                + service.registry().items().size() + " items.");
    }

    @Override
    public void onDisable() {
        SacksApi.bind(null);
        if (flushTask != null) {
            flushTask.cancel();
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder) {
                player.closeInventory();
            }
        }
        if (expansion != null) {
            ((SacksExpansion) expansion).unregister();
        }
        if (service != null) {
            service.shutdown(Duration.ofSeconds(10));
        } else if (db != null) {
            db.shutdown(Duration.ofSeconds(10));
        }
        if (storage != null) {
            storage.close();
        }
    }

    /** Re-reads config.yml, messages.yml, menus.yml and sacks/. Storage settings need a restart. */
    public void reload() {
        reload(true);
    }

    private static final int ITEM_RETRIES = 30;

    // once a retry resolves more ids than the last, eco has loaded its items; whatever is still unknown is
    // reported then (or after ITEM_RETRIES seconds if nothing ever resolves)
    private void resolveItems(int attempt, int unknownBefore) {
        if (!isEnabled()) {
            return;
        }
        ItemMatcher quiet = new ItemMatcher(itemIds, warning -> { });
        boolean settled = quiet.unknownCount() == 0 || quiet.unknownCount() < unknownBefore || attempt >= ITEM_RETRIES;
        if (!settled) {
            getServer().getScheduler().runTaskLater(this, () -> resolveItems(attempt + 1, unknownBefore), 20L);
            return;
        }
        ItemMatcher matcher = quiet.unknownCount() == 0 ? quiet : new ItemMatcher(itemIds, getLogger()::warning);
        service.configure(service.registry(), matcher, service.settings());
    }

    private void reload(boolean warnUnknownItems) {
        reloadConfig();
        messages.reload();
        this.scopes = new ScopeResolver(getConfig().getString("sacks.scope-placeholder", "%royalskyblock_profile_id%"),
                scopes);
        SackRegistry registry = SackRegistry.load(new File(getDataFolder(), "sacks"), getLogger()::warning);
        Set<String> ids = new LinkedHashSet<>(registry.items());
        for (SackDefinition sack : registry.all()) {
            ids.add(SackRegistry.normalize(sack.icon()));
            sack.tiers().forEach(t -> t.items().forEach(cost -> ids.add(cost.itemId())));
        }
        this.itemIds = ids;
        ItemMatcher matcher = new ItemMatcher(ids, warnUnknownItems ? getLogger()::warning : warning -> { });
        ConfigurationSection pickup = getConfig().getConfigurationSection("pickup");
        SackService.Settings settings = new SackService.Settings(
                pickup == null || pickup.getBoolean("enabled-by-default", true),
                pickup == null || pickup.getBoolean("action-bar", true),
                Math.max(1, pickup == null ? 60 : pickup.getLong("full-warning-seconds", 60)) * 1000L);
        if (flushTask != null) {
            flushTask.cancel();
            service.flush();
        }
        service.configure(registry, matcher, settings);
        menu.reload(BundledYaml.load(this, "menus.yml"));
        long flushTicks = Math.max(1, pickup == null ? 3 : pickup.getLong("flush-seconds", 3)) * 20L;
        this.flushTask = getServer().getScheduler().runTaskTimer(this, service::flush, flushTicks, flushTicks);
    }

    private SackStorage openStorage() throws Exception {
        ConfigurationSection s = getConfig().getConfigurationSection("storage");
        if (s == null) {
            s = getConfig().createSection("storage");
        }
        if ("MYSQL".equals(s.getString("type", "SQLITE").toUpperCase(Locale.ROOT))) {
            ConfigurationSection my = s.getConfigurationSection("mysql");
            if (my == null) {
                my = s.createSection("mysql");
            }
            SackStorage opened = SackStorage.mysql(new SackStorage.MySqlSettings(my.getString("host", "localhost"),
                    my.getInt("port", 3306), my.getString("database", "royalsacks"), my.getString("username", "root"),
                    my.getString("password", ""), my.getString("properties", "useSSL=false"), my.getInt("pool-size", 10)));
            getLogger().info("Connected to MySQL storage.");
            return opened;
        }
        File folder = getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create " + folder.getAbsolutePath());
        }
        return SackStorage.sqlite(new File(folder, s.getString("sqlite-file", "sacks.db")));
    }

    public MessageManager messages() {
        return messages;
    }

    public SackService service() {
        return service;
    }

    public SackMenu menu() {
        return menu;
    }
}
