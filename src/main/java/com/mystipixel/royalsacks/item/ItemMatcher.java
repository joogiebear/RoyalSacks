package com.mystipixel.royalsacks.item;

import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Turns item stacks into sack item ids and back. Vanilla ids ({@code wheat}) match plain stacks of that
 * material only; any other id is an eco lookup key ({@code ecoitems:enchanted_wheat}). eco classes are only
 * touched when eco is enabled, so without it the plugin works vanilla-only.
 */
public final class ItemMatcher {

    private record Custom(String id, TestableItem test) {
    }

    private final boolean eco;
    private final Map<Material, String> vanilla = new EnumMap<>(Material.class);
    private final Map<String, Material> vanillaById = new HashMap<>();
    private final Map<Material, List<Custom>> custom = new EnumMap<>(Material.class);
    private final Map<String, TestableItem> customById = new HashMap<>();
    private final int unknown;

    public ItemMatcher(Collection<String> ids, Consumer<String> warn) {
        this.eco = Bukkit.getPluginManager().isPluginEnabled("eco");
        List<String> unknown = new ArrayList<>();
        for (String id : ids) {
            Material material = vanillaMaterial(id);
            if (material != null) {
                vanilla.put(material, id);
                vanillaById.put(id, material);
                continue;
            }
            TestableItem test = eco ? lookup(id) : null;
            ItemStack sample = test == null ? null : test.getItem();
            if (sample == null || sample.getType().isAir()) {
                unknown.add(id);
                continue;
            }
            customById.put(id, test);
            custom.computeIfAbsent(sample.getType(), m -> new ArrayList<>()).add(new Custom(id, test));
        }
        this.unknown = unknown.size();
        if (!unknown.isEmpty()) {
            // one line, not one per item: the bundled sacks list every EcoItems enchanted item
            warn.accept(unknown.size() + " sack item(s) don't exist on this server"
                    + (eco ? "" : " (eco is not installed)") + " and won't be stored: " + String.join(", ", unknown));
        }
    }

    /** How many of the ids given didn't resolve to an item. */
    public int unknownCount() {
        return unknown;
    }

    /** The sack item id {@code stack} counts as, or null when no sack holds it. Cheap for unrelated stacks. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        List<Custom> candidates = custom.get(stack.getType());
        if (candidates != null) {
            for (Custom c : candidates) {
                if (matchesCustom(c.test(), stack)) {
                    return c.id();
                }
            }
        }
        String id = vanilla.get(stack.getType());
        if (id != null && isPlain(stack) && !isEcoItem(stack)) {
            return id;
        }
        return null;
    }

    public boolean matches(String id, ItemStack stack) {
        return id.equals(idOf(stack));
    }

    /** A fresh stack of {@code amount} (at most one stack) of {@code id}, or null if the id is unknown. */
    public ItemStack create(String id, int amount) {
        Material material = vanillaById.get(id);
        ItemStack item;
        if (material != null) {
            item = new ItemStack(material);
        } else {
            TestableItem test = customById.get(id);
            if (test == null) {
                return null;
            }
            item = test.getItem().clone();
        }
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }

    /** A name for messages and menus, with {@code &} colour codes. */
    public String displayName(String id) {
        Material material = vanillaById.get(id);
        if (material != null) {
            return titleCase(material.name());
        }
        TestableItem test = customById.get(id);
        if (test != null) {
            ItemMeta meta = test.getItem().getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName());
            }
            return titleCase(test.getItem().getType().name());
        }
        return id;
    }

    // DIAMOND_ORE -> Diamond Ore
    static String titleCase(String constant) {
        StringBuilder out = new StringBuilder();
        for (String word : constant.toLowerCase(java.util.Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public boolean known(String id) {
        return vanillaById.containsKey(id) || customById.containsKey(id);
    }

    /** How many of {@code id} the player's main inventory holds. */
    public int count(Player player, String id) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && matches(id, stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Removes {@code amount} of {@code id} from the player's main inventory and returns what was taken, or
     * nothing when they hold fewer. Main thread.
     */
    public List<ItemStack> take(Player player, String id, int amount) {
        if (count(player, id) < amount) {
            return List.of();
        }
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        int left = amount;
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !matches(id, stack)) {
                continue;
            }
            int from = Math.min(left, stack.getAmount());
            ItemStack part = stack.clone();
            part.setAmount(from);
            taken.add(part);
            left -= from;
            if (from == stack.getAmount()) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - from);
            }
        }
        player.getInventory().setStorageContents(contents);
        return taken;
    }

    // a vanilla id stands for the plain item, so a renamed, enchanted or plugin-tagged stack is not it
    static boolean isPlain(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasDisplayName() || meta.hasItemName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasCustomModelData() || !meta.getPersistentDataContainer().isEmpty()) {
            return false;
        }
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            return false;
        }
        if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
            return false;
        }
        return !(meta instanceof BlockStateMeta blockState && blockState.hasBlockState());
    }

    private boolean isEcoItem(ItemStack stack) {
        if (!eco) {
            return false;
        }
        try {
            return Items.isCustomItem(stack);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static TestableItem lookup(String id) {
        try {
            return Items.lookup(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean matchesCustom(TestableItem test, ItemStack stack) {
        try {
            return test.matches(stack);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ids with a namespace other than minecraft go to eco
    static Material vanillaMaterial(String id) {
        if (id.contains(":")) {
            return null;
        }
        Material material = Material.matchMaterial(id);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }
}
