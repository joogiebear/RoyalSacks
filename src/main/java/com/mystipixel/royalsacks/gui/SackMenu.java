package com.mystipixel.royalsacks.gui;

import com.mystipixel.royalsacks.item.ItemMatcher;
import com.mystipixel.royalsacks.message.MessageManager;
import com.mystipixel.royalsacks.sack.SackDefinition;
import com.mystipixel.royalsacks.sack.SackKey;
import com.mystipixel.royalsacks.sack.SackService;
import com.mystipixel.royalsacks.sack.SackState;
import com.mystipixel.royalsacks.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The overview of a player's sacks and the view of one sack. Layout and text come from {@code menus.yml}. */
public final class SackMenu {

    private static final Pattern TOKEN = Pattern.compile("\\{([a-z_]+)}");

    private final SackService service;
    private final MessageManager messages;
    private YamlConfiguration menus;

    public SackMenu(SackService service, MessageManager messages, YamlConfiguration menus) {
        this.service = service;
        this.messages = messages;
        this.menus = menus;
    }

    public void reload(YamlConfiguration menus) {
        this.menus = menus;
    }

    /** Opens the overview once the player's sacks are read. */
    public void openMain(Player player) {
        SackKey key = service.keyFor(player);
        if (key == null) {
            messages.send(player, "no-profile");
            return;
        }
        service.load(key).whenComplete((state, error) -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                messages.send(player, "failed");
            } else if (key.equals(service.keyFor(player))) {
                showMain(player, key, state);
            }
        });
    }

    public void openSack(Player player, String sackId, int page) {
        SackKey key = service.keyFor(player);
        SackState state = key == null ? null : service.state(key);
        if (state == null) {
            openMain(player);
            return;
        }
        showSack(player, key, state, sackId, page);
    }

    /** Redraws whatever sacks menu the player has open, from the current state. */
    public void refresh(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof SackHolder holder)) {
            return;
        }
        SackState state = service.state(holder.key());
        if (state == null) {
            return;
        }
        if (holder.sackId() == null) {
            showMain(player, holder.key(), state);
        } else {
            showSack(player, holder.key(), state, holder.sackId(), holder.page());
        }
    }

    private void showMain(Player player, SackKey key, SackState state) {
        ConfigurationSection cfg = section("main");
        SackHolder holder = new SackHolder(key, null, 0);
        Inventory inv = create(holder, cfg, Map.of());
        List<Integer> slots = inside(cfg.getIntegerList("sack-slots"), inv.getSize());
        int i = 0;
        for (SackDefinition sack : service.registry().all()) {
            if (i >= slots.size()) {
                break;
            }
            int tier = state.tier(sack);
            Map<String, String> values = Map.of(
                    "sack", sack.display(),
                    "tier", tier == 0 ? text("locked-tier") : sack.tiers().get(tier - 1).display(),
                    "stored", String.format("%,d", state.total(sack)),
                    "capacity", String.format("%,d", sack.capacity(tier)));
            ItemStack icon = icon(sack.icon());
            String lore = !sack.usableBy(player) ? "sack-icon.no-permission-lore"
                    : tier == 0 ? "sack-icon.locked-lore" : "sack-icon.lore";
            decorate(icon, cfg.getString("sack-icon.name", "{sack}"), cfg.getStringList(lore), values);
            int slot = slots.get(i++);
            inv.setItem(slot, icon);
            holder.bind(slot, new SackHolder.Open(sack.id()));
        }
        boolean on = service.pickupEnabled(player);
        button(inv, holder, cfg, on ? "pickup-on" : "pickup-off", new SackHolder.TogglePickup(), Map.of());
        button(inv, holder, cfg, "close", new SackHolder.Close(), Map.of());
        player.openInventory(inv);
    }

    private void showSack(Player player, SackKey key, SackState state, String sackId, int page) {
        SackDefinition sack = service.registry().get(sackId);
        if (sack == null) {
            showMain(player, key, state);
            return;
        }
        if (!sack.usableBy(player)) {
            messages.send(player, "sack-locked", Map.of("sack", sack.display()));
            showMain(player, key, state);
            return;
        }
        ConfigurationSection cfg = section("sack");
        List<Integer> slots = inside(cfg.getIntegerList("item-slots"), Math.max(1, Math.min(6, cfg.getInt("rows", 6))) * 9);
        int perPage = Math.max(1, slots.size());
        // items whose plugin isn't installed (an EcoItems id without EcoItems) leave no gap
        ItemMatcher matcher = service.matcher();
        List<String> items = sack.items().stream().filter(matcher::known).toList();
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));
        int tier = state.tier(sack);
        long capacity = sack.capacity(tier);

        SackHolder holder = new SackHolder(key, sackId, page);
        Inventory inv = create(holder, cfg, Map.of("sack", sack.display()));
        for (int i = 0; i < perPage && page * perPage + i < items.size(); i++) {
            String itemId = items.get(page * perPage + i);
            ItemStack icon = matcher.create(itemId, 1);
            if (icon == null) {
                continue;
            }
            long amount = state.amount(itemId);
            decorate(icon, cfg.getString("item.name", "{item}"), cfg.getStringList("item.lore"), Map.of(
                    "item", matcher.displayName(itemId),
                    "amount", String.format("%,d", amount),
                    "capacity", String.format("%,d", capacity)));
            inv.setItem(slots.get(i), icon);
            holder.bind(slots.get(i), new SackHolder.Take(itemId));
        }

        SackDefinition.Tier next = sack.next(tier);
        if (next == null) {
            button(inv, holder, cfg, "upgrade-maxed", null, Map.of());
        } else {
            List<String> cost = new ArrayList<>();
            if (next.coins() > 0) {
                cost.add(fill(text("cost-coins"), Map.of("cost", service.vault().format(next.coins()))));
            }
            for (SackDefinition.ItemCost c : next.items()) {
                cost.add(fill(text("cost-item"), Map.of("amount", String.valueOf(c.amount()),
                        "item", matcher.displayName(c.itemId()))));
            }
            if (cost.isEmpty()) {
                cost.add(text("cost-free"));
            }
            button(inv, holder, cfg, "upgrade", new SackHolder.Upgrade(sackId), Map.of(
                    "tier", next.display(),
                    "capacity", String.format("%,d", next.capacity()),
                    "cost", String.join("\n", cost)));
        }
        if (page > 0) {
            button(inv, holder, cfg, "previous-page", new SackHolder.Page(page - 1), Map.of());
        }
        if (page < pages - 1) {
            button(inv, holder, cfg, "next-page", new SackHolder.Page(page + 1), Map.of());
        }
        button(inv, holder, cfg, "back", new SackHolder.Back(), Map.of());
        player.openInventory(inv);
    }

    // slots outside the menu are dropped rather than failing every open
    static List<Integer> inside(List<Integer> slots, int size) {
        return slots.stream().filter(slot -> slot >= 0 && slot < size).toList();
    }

    private Inventory create(SackHolder holder, ConfigurationSection cfg, Map<String, String> values) {
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", 6)));
        Inventory inv = Bukkit.createInventory(holder, rows * 9, Text.of(fill(cfg.getString("title", ""), values)));
        holder.setInventory(inv);
        Material filler = Material.matchMaterial(cfg.getString("filler", ""));
        if (filler != null && filler.isItem() && !filler.isAir()) {
            ItemStack pane = new ItemStack(filler);
            ItemMeta meta = pane.getItemMeta();
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            pane.setItemMeta(meta);
            for (int slot = 0; slot < inv.getSize(); slot++) {
                inv.setItem(slot, pane);
            }
        }
        return inv;
    }

    // a button is skipped when its slot is negative or outside the menu
    private void button(Inventory inv, SackHolder holder, ConfigurationSection cfg, String name,
                        SackHolder.Action action, Map<String, String> values) {
        ConfigurationSection b = cfg.getConfigurationSection(name);
        if (b == null) {
            return;
        }
        int slot = b.getInt("slot", -1);
        if (slot < 0 || slot >= inv.getSize()) {
            return;
        }
        ItemStack item = icon(b.getString("material", "stone"));
        decorate(item, b.getString("name", ""), b.getStringList("lore"), values);
        inv.setItem(slot, item);
        if (action != null) {
            holder.bind(slot, action);
        }
    }

    private ItemStack icon(String id) {
        ItemStack item = service.matcher().create(id, 1);
        if (item != null) {
            return item;
        }
        Material material = Material.matchMaterial(id);
        return new ItemStack(material != null && material.isItem() && !material.isAir() ? material : Material.CHEST);
    }

    // a value holding line breaks (a list of costs) expands into several lore lines
    private static void decorate(ItemStack item, String name, List<String> lore, Map<String, String> values) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.of(fill(name, values)));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            for (String part : fill(line, values).split("\n", -1)) {
                lines.add(Text.of(part));
            }
        }
        meta.lore(lines);
        item.setItemMeta(meta);
    }

    private String text(String key) {
        return menus.getString(key, "");
    }

    private ConfigurationSection section(String name) {
        ConfigurationSection s = menus.getConfigurationSection(name);
        return s != null ? s : new YamlConfiguration();
    }

    static String fill(String raw, Map<String, String> values) {
        if (raw == null) {
            return "";
        }
        Matcher m = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
