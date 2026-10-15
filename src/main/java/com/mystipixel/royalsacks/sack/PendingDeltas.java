package com.mystipixel.royalsacks.sack;

import com.mystipixel.royalsacks.storage.SackStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes made on this server that haven't been written yet, summed per row. Pickups land here so mining
 * doesn't cost a database write per block. Main thread only.
 */
public final class PendingDeltas {

    private final Map<SackKey, Map<String, Long>> pending = new LinkedHashMap<>();

    public void add(SackKey key, String itemId, long qty) {
        if (qty == 0) {
            return;
        }
        Map<String, Long> items = pending.computeIfAbsent(key, k -> new HashMap<>());
        items.merge(itemId, qty, Long::sum);
        if (items.get(itemId) == 0) {
            items.remove(itemId);
            if (items.isEmpty()) {
                pending.remove(key);
            }
        }
    }

    /** Pending change for one row, already counted in this server's {@link SackState}. */
    public long of(SackKey key, String itemId) {
        Map<String, Long> items = pending.get(key);
        return items == null ? 0 : items.getOrDefault(itemId, 0L);
    }

    public Map<String, Long> of(SackKey key) {
        Map<String, Long> items = pending.get(key);
        return items == null ? Map.of() : Map.copyOf(items);
    }

    /** Removes and returns everything pending. */
    public List<SackStorage.Delta> drainAll() {
        List<SackStorage.Delta> out = new ArrayList<>();
        for (Iterator<Map.Entry<SackKey, Map<String, Long>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SackKey, Map<String, Long>> e = it.next();
            collect(e.getKey(), e.getValue(), out);
            it.remove();
        }
        return out;
    }

    /** Removes and returns what is pending for one key. */
    public List<SackStorage.Delta> drain(SackKey key) {
        List<SackStorage.Delta> out = new ArrayList<>();
        Map<String, Long> items = pending.remove(key);
        if (items != null) {
            collect(key, items, out);
        }
        return out;
    }

    /** Puts deltas back after a failed write; they are summed with anything added since. */
    public void restore(List<SackStorage.Delta> deltas) {
        for (SackStorage.Delta d : deltas) {
            add(new SackKey(d.owner(), d.scope()), d.itemId(), d.qty());
        }
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private static void collect(SackKey key, Map<String, Long> items, List<SackStorage.Delta> out) {
        items.forEach((item, qty) -> out.add(new SackStorage.Delta(key.owner(), key.scope(), item, qty)));
    }
}
