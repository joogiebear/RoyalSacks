package com.mystipixel.royalsacks.sack;

import java.util.HashMap;
import java.util.Map;

/**
 * This server's view of one player's sacks: what the database held when it was read, plus every change made
 * here since. Main thread only.
 */
public final class SackState {

    private final Map<String, Long> contents;
    private final Map<String, Integer> tiers;

    public SackState(Map<String, Long> contents, Map<String, Integer> tiers) {
        this.contents = new HashMap<>(contents);
        this.tiers = new HashMap<>(tiers);
    }

    public long amount(String itemId) {
        return contents.getOrDefault(itemId, 0L);
    }

    public void add(String itemId, long qty) {
        contents.merge(itemId, qty, Long::sum);
        if (contents.get(itemId) <= 0) {
            contents.remove(itemId);
        }
    }

    /** The tier stored for this sack, 0 when none was bought. See {@link SackDefinition#effectiveTier}. */
    public int storedTier(String sackId) {
        return tiers.getOrDefault(sackId, 0);
    }

    public int tier(SackDefinition sack) {
        return sack.effectiveTier(storedTier(sack.id()));
    }

    public void setTier(String sackId, int tier) {
        tiers.put(sackId, tier);
    }

    /** How many more of {@code itemId} fit, given its sack's capacity at the unlocked tier. */
    public long room(SackDefinition sack, String itemId) {
        return Math.max(0, sack.capacity(tier(sack)) - amount(itemId));
    }

    /** Total stored across a sack's items. */
    public long total(SackDefinition sack) {
        long sum = 0;
        for (String item : sack.items()) {
            sum += amount(item);
        }
        return sum;
    }
}
