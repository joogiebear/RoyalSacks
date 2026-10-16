package com.mystipixel.royalsacks.sack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every sack loaded from {@code sacks/*.yml}, and which sack holds each item. An item belongs to at most one
 * sack: a pickup has to know where to go, so a second sack listing it is warned about and skipped.
 */
public final class SackRegistry {

    private final Map<String, SackDefinition> sacks;
    private final Map<String, SackDefinition> byItem;

    private SackRegistry(Map<String, SackDefinition> sacks, Map<String, SackDefinition> byItem) {
        this.sacks = Collections.unmodifiableMap(sacks);
        this.byItem = Collections.unmodifiableMap(byItem);
    }

    public static SackRegistry empty() {
        return new SackRegistry(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** Reads every {@code .yml} in {@code folder}, sorted by file name so menu order is stable. */
    public static SackRegistry load(File folder, Consumer<String> warn) {
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        List<SackDefinition> parsed = new ArrayList<>();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                SackDefinition sack = parse(id, YamlConfiguration.loadConfiguration(file), warn);
                if (sack != null) {
                    parsed.add(sack);
                }
            }
        }
        return of(parsed, warn);
    }

    static SackRegistry of(Collection<SackDefinition> parsed, Consumer<String> warn) {
        Map<String, SackDefinition> sacks = new LinkedHashMap<>();
        Map<String, SackDefinition> byItem = new LinkedHashMap<>();
        for (SackDefinition sack : parsed) {
            List<String> kept = new ArrayList<>();
            for (String item : sack.items()) {
                SackDefinition owner = byItem.putIfAbsent(item, sack);
                if (owner != null && owner != sack) {
                    warn.accept("Sack '" + sack.id() + "' lists " + item + ", which is already in sack '"
                            + owner.id() + "'; skipped.");
                    continue;
                }
                kept.add(item);
            }
            sacks.put(sack.id(), new SackDefinition(sack.id(), sack.display(), sack.icon(), kept, sack.tiers(), sack.permission()));
        }
        // byItem must point at the stored copies, whose item lists are the deduplicated ones
        byItem.replaceAll((item, sack) -> sacks.get(sack.id()));
        return new SackRegistry(sacks, byItem);
    }

    static SackDefinition parse(String id, ConfigurationSection yaml, Consumer<String> warn) {
        List<String> items = new ArrayList<>();
        for (String raw : yaml.getStringList("items")) {
            String item = normalize(raw);
            if (!item.isEmpty() && !items.contains(item)) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            warn.accept("Sack '" + id + "' has no items; skipped.");
            return null;
        }
        List<SackDefinition.Tier> tiers = new ArrayList<>();
        ConfigurationSection tierSection = yaml.getConfigurationSection("tiers");
        if (tierSection != null) {
            long previous = 0;
            for (String key : tierSection.getKeys(false)) {
                ConfigurationSection t = tierSection.getConfigurationSection(key);
                if (t == null) {
                    continue;
                }
                long capacity = t.getLong("capacity", 0);
                if (capacity <= previous) {
                    warn.accept("Sack '" + id + "' tier '" + key + "' must hold more than the tier before it; skipped.");
                    continue;
                }
                previous = capacity;
                tiers.add(new SackDefinition.Tier(t.getString("display", key), capacity,
                        Math.max(0, t.getDouble("cost.coins", 0)), itemCosts(id, key, t.getStringList("cost.items"), warn)));
            }
        }
        if (tiers.isEmpty()) {
            warn.accept("Sack '" + id + "' has no tiers; skipped.");
            return null;
        }
        return new SackDefinition(id, yaml.getString("display", id), yaml.getString("icon", items.get(0)), items, tiers,
                yaml.getString("permission"));
    }

    // "ecoitems:enchanted_leather 64" -> (ecoitems:enchanted_leather, 64)
    private static List<SackDefinition.ItemCost> itemCosts(String sack, String tier, List<String> lines, Consumer<String> warn) {
        List<SackDefinition.ItemCost> costs = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    amount = 0;
                }
            }
            if (parts[0].isEmpty() || amount <= 0) {
                warn.accept("Sack '" + sack + "' tier '" + tier + "' has an unreadable item cost '" + line + "'; ignored.");
                continue;
            }
            costs.add(new SackDefinition.ItemCost(normalize(parts[0]), amount));
        }
        return costs;
    }

    // ids are matched as written except that vanilla ones lose the "minecraft:" prefix and case
    public static String normalize(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (id.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        return id.contains(":") ? id : id.toLowerCase(Locale.ROOT);
    }

    public Collection<SackDefinition> all() {
        return sacks.values();
    }

    public SackDefinition get(String id) {
        return sacks.get(id);
    }

    /** The sack that holds {@code itemId}, or null. */
    public SackDefinition sackOf(String itemId) {
        return byItem.get(itemId);
    }

    public Collection<String> items() {
        return byItem.keySet();
    }
}
