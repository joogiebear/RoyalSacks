package com.mystipixel.royalsacks.sack;

import java.util.List;

/**
 * One sack from {@code sacks/<id>.yml}: the items it holds and the capacity tiers a player buys in order.
 * Tier 0 means not unlocked; tier {@code n} is {@code tiers.get(n - 1)}.
 */
public record SackDefinition(String id, String display, String icon, List<String> items, List<Tier> tiers) {

    public record ItemCost(String itemId, int amount) {
    }

    public record Tier(String display, long capacity, double coins, List<ItemCost> items) {

        public Tier {
            items = List.copyOf(items);
        }

        public boolean free() {
            return coins <= 0 && items.isEmpty();
        }
    }

    public SackDefinition {
        items = List.copyOf(items);
        tiers = List.copyOf(tiers);
    }

    /** The tier a player has, given the tier stored for them: a first tier that costs nothing is free for everyone. */
    public int effectiveTier(int stored) {
        if (stored == 0 && !tiers.isEmpty() && tiers.get(0).free()) {
            return 1;
        }
        return Math.min(stored, tiers.size());
    }

    /** Capacity per item at {@code tier}; 0 when the sack isn't unlocked. */
    public long capacity(int tier) {
        if (tier <= 0 || tiers.isEmpty()) {
            return 0;
        }
        return tiers.get(Math.min(tier, tiers.size()) - 1).capacity();
    }

    /** The tier bought after {@code current}, or null when {@code current} is the last. */
    public Tier next(int current) {
        return current >= 0 && current < tiers.size() ? tiers.get(current) : null;
    }
}
