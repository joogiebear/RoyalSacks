package com.mystipixel.royalsacks.api;

import com.mystipixel.royalsacks.sack.SackKey;
import com.mystipixel.royalsacks.sack.SackService;
import com.mystipixel.royalsacks.sack.SackState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * RoyalSacks for other plugins. Static methods with JDK and Bukkit types only, so a caller can reach them by
 * reflection without compiling against RoyalSacks. Main thread.
 *
 * <pre>{@code
 * Method deposit = Class.forName("com.mystipixel.royalsacks.api.SacksApi")
 *         .getMethod("deposit", Player.class, ItemStack.class);
 * int stored = (int) deposit.invoke(null, player, stack);
 * }</pre>
 */
public final class SacksApi {

    private static volatile SackService service;

    private SacksApi() {
    }

    public static void bind(SackService bound) {
        service = bound;
    }

    /** Whether RoyalSacks is enabled. */
    public static boolean available() {
        return service != null;
    }

    /**
     * Puts as much of {@code stack} as fits into the player's sack, ignoring their pickup setting, and returns
     * how many went in. The stack itself is not changed: take that many from wherever it came from. Returns 0
     * when no sack holds the item, it is full, or the player's sacks haven't been read yet.
     */
    public static int deposit(Player player, ItemStack stack) {
        SackService s = service;
        if (s == null || stack == null) {
            return 0;
        }
        String itemId = s.matcher().idOf(stack);
        return itemId == null ? 0 : s.deposit(player, itemId, stack.getAmount(), false);
    }

    /** How many of a sack item id the player holds in their current sacks, or -1 when not known yet. */
    public static long amount(Player player, String itemId) {
        SackService s = service;
        if (s == null) {
            return -1;
        }
        SackKey key = s.keyFor(player);
        SackState state = key == null ? null : s.state(key);
        return state == null ? -1 : state.amount(itemId);
    }

    /** The sack item id a stack counts as, or null when no sack holds it. */
    public static String itemId(ItemStack stack) {
        SackService s = service;
        return s == null ? null : s.matcher().idOf(stack);
    }
}
