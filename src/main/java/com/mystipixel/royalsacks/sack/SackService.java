package com.mystipixel.royalsacks.sack;

import com.mystipixel.royalcommon.storage.BusyGuard;
import com.mystipixel.royalcommon.storage.DbExecutor;
import com.mystipixel.royalsacks.hooks.SkyblockSave;
import com.mystipixel.royalsacks.hooks.VaultHook;
import com.mystipixel.royalsacks.item.ItemMatcher;
import com.mystipixel.royalsacks.message.MessageManager;
import com.mystipixel.royalsacks.scope.ScopeResolver;
import com.mystipixel.royalsacks.storage.SackStorage;
import com.mystipixel.royalsacks.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.sql.SQLException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything players do with their sacks. Main thread, apart from the work it hands to the database thread.
 *
 * <p>Items only ever leave a sack after the database has taken them out ({@link SackStorage#withdraw}), and
 * anything that can't be handed over goes back in, so a double click, a lag spike or a second server can lose
 * a pending pickup at worst but never duplicate one.
 */
public final class SackService {

    public enum Withdraw { STACK, FILL }

    private static final long LOAD_RETRY_MILLIS = 5000;

    public record Settings(boolean pickupByDefault, boolean notifyActionBar, long fullWarnMillis) {
    }

    private final DbExecutor db;
    private final SackStorage storage;
    private final MessageManager messages;
    private final VaultHook vault;
    private final SkyblockSave skyblock;
    private final Logger log;
    private final Supplier<ScopeResolver> scopes;
    private SackRegistry registry;
    private ItemMatcher matcher;
    private Settings settings;

    private final Map<SackKey, SackState> states = new HashMap<>();
    private final Map<SackKey, CompletableFuture<SackState>> loading = new HashMap<>();
    private final Map<UUID, Boolean> pickup = new HashMap<>();
    private final PendingDeltas pending = new PendingDeltas();
    private final Map<UUID, Long> picked = new HashMap<>();
    private final Map<String, Long> fullWarned = new HashMap<>();
    private final BusyGuard<UUID> busy = new BusyGuard<>();
    // writes handed to the database thread and not yet confirmed, by sequence; a load counts those queued after it
    private final Map<Long, Unconfirmed> unconfirmed = new LinkedHashMap<>();
    private long writeSeq;
    private final Map<SackKey, Long> loadFailedAt = new HashMap<>();
    // withdrawals and upgrades whose database result hasn't been acted on yet; finished in shutdown() if need be
    private final Map<Long, Finisher> finishing = new LinkedHashMap<>();
    private long finishSeq;
    private boolean closing;

    private record Unconfirmed(List<SackStorage.Delta> deltas, CompletableFuture<Void> result) {
    }

    private record Finisher(CompletableFuture<Boolean> result, BiConsumer<Boolean, Throwable> finish) {
    }

    public SackService(DbExecutor db, SackStorage storage, MessageManager messages, VaultHook vault, SkyblockSave skyblock,
                       Logger log, Supplier<ScopeResolver> scopes) {
        this.db = db;
        this.storage = storage;
        this.messages = messages;
        this.vault = vault;
        this.skyblock = skyblock;
        this.log = log;
        this.scopes = scopes;
    }

    public void configure(SackRegistry registry, ItemMatcher matcher, Settings settings) {
        this.registry = registry;
        this.matcher = matcher;
        this.settings = settings;
    }

    public SackRegistry registry() {
        return registry;
    }

    public ItemMatcher matcher() {
        return matcher;
    }

    public VaultHook vault() {
        return vault;
    }

    /** The key for the player's current scope, or null when per-profile and they have no profile. */
    public SackKey keyFor(Player player) {
        String scope = scopes.get().scopeFor(player);
        return scope == null ? null : new SackKey(player.getUniqueId(), scope);
    }

    /** The loaded state for a key, or null when it hasn't been read yet. */
    public SackState state(SackKey key) {
        return states.get(key);
    }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        boolean fallback = settings.pickupByDefault();
        db.submit("pickup setting " + id, () -> storage.pickupEnabled(id, fallback))
                .thenAcceptAsync(on -> {
                    if (Bukkit.getPlayer(id) != null) {
                        pickup.putIfAbsent(id, on);
                    }
                }, db.mainThread());
        SackKey key = keyFor(player);
        if (key != null) {
            load(key);
        }
    }

    /** Writes what is pending for the player and forgets their sacks on this server. */
    public void quit(Player player) {
        UUID id = player.getUniqueId();
        List<SackKey> keys = new ArrayList<>();
        for (SackKey key : states.keySet()) {
            if (key.owner().equals(id)) {
                keys.add(key);
            }
        }
        for (SackKey key : keys) {
            write(pending.drain(key));
            states.remove(key);
        }
        pickup.remove(id);
        picked.remove(id);
    }

    /** Reads the key's sacks from the database (writing anything pending first). Completes on the main thread. */
    public CompletableFuture<SackState> load(SackKey key) {
        if (closing) {
            return CompletableFuture.failedFuture(new IllegalStateException("RoyalSacks is shutting down"));
        }
        CompletableFuture<SackState> inFlight = loading.get(key);
        if (inFlight != null) {
            return inFlight;
        }
        write(pending.drain(key));
        long readAfter = writeSeq;
        CompletableFuture<SackState> future = db.submit("load " + key, () -> storage.load(key.owner(), key.scope()))
                .thenApplyAsync(snapshot -> {
                    SackState state = new SackState(snapshot.contents(), snapshot.tiers());
                    // changes made after the read was queued: still pending, or written but not confirmed yet
                    pending.of(key).forEach(state::add);
                    unconfirmed.forEach((seq, write) -> {
                        if (seq > readAfter) {
                            for (SackStorage.Delta d : write.deltas()) {
                                if (d.owner().equals(key.owner()) && d.scope().equals(key.scope())) {
                                    state.add(d.itemId(), d.qty());
                                }
                            }
                        }
                    });
                    if (Bukkit.getPlayer(key.owner()) != null) {
                        states.put(key, state);
                    }
                    return state;
                }, db.mainThread());
        loading.put(key, future);
        future.whenCompleteAsync((state, error) -> {
            loading.remove(key, future);
            if (error != null) {
                loadFailedAt.put(key, System.currentTimeMillis());
            } else {
                loadFailedAt.remove(key);
            }
        }, db.mainThread());
        return future;
    }

    // pickups ask for a load on every matched item; after a failure, wait before asking the database again
    private void loadSoon(SackKey key) {
        Long failed = loadFailedAt.get(key);
        if (failed == null || System.currentTimeMillis() - failed > LOAD_RETRY_MILLIS) {
            load(key);
        }
    }

    public boolean pickupEnabled(Player player) {
        return pickup.getOrDefault(player.getUniqueId(), settings.pickupByDefault());
    }

    public void setPickup(Player player, boolean on) {
        UUID id = player.getUniqueId();
        pickup.put(id, on);
        db.submit("set pickup " + id, () -> {
            storage.setPickup(id, on);
            return null;
        });
    }

    /**
     * Puts up to {@code amount} of an item the player picked up into their sack. Returns how many went in;
     * the rest stays as it was. Never blocks: sacks not read yet take nothing this time.
     */
    public int pickup(Player player, String itemId, int amount) {
        if (!pickupEnabled(player)) {
            return 0;
        }
        return deposit(player, itemId, amount, true);
    }

    /** As {@link #pickup}, for other plugins, regardless of the player's pickup setting. */
    public int deposit(Player player, String itemId, int amount, boolean notify) {
        SackDefinition sack = registry.sackOf(itemId);
        if (sack == null || amount <= 0) {
            return 0;
        }
        SackKey key = keyFor(player);
        if (key == null) {
            return 0;
        }
        SackState state = states.get(key);
        if (state == null) {
            loadSoon(key);
            return 0;
        }
        long room = state.room(sack, itemId);
        if (room <= 0) {
            if (notify) {
                warnFull(player, sack);
            }
            return 0;
        }
        int accepted = (int) Math.min(room, amount);
        state.add(itemId, accepted);
        pending.add(key, itemId, accepted);
        if (notify) {
            picked.merge(player.getUniqueId(), (long) accepted, Long::sum);
        }
        return accepted;
    }

    private void warnFull(Player player, SackDefinition sack) {
        String marker = player.getUniqueId() + "/" + sack.id();
        long now = System.currentTimeMillis();
        Long last = fullWarned.get(marker);
        if (last != null && now - last < settings.fullWarnMillis()) {
            return;
        }
        fullWarned.put(marker, now);
        messages.send(player, "sack-full", Map.of("sack", sack.display()));
    }

    /** Writes everything pending and shows players what their sacks picked up since the last flush. */
    public void flush() {
        write(pending.drainAll());
        if (settings.notifyActionBar()) {
            picked.forEach((id, qty) -> {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    String line = messages.format("pickup-actionbar", Map.of("amount", String.format("%,d", qty)));
                    if (!line.isBlank()) {
                        player.sendActionBar(Text.of(line));
                    }
                }
            });
        }
        picked.clear();
        long cutoff = System.currentTimeMillis() - settings.fullWarnMillis();
        fullWarned.values().removeIf(at -> at < cutoff);
    }

    private CompletableFuture<Void> write(List<SackStorage.Delta> deltas) {
        if (deltas.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (closing) {
            pending.restore(deltas);
            return CompletableFuture.completedFuture(null);
        }
        long seq = ++writeSeq;
        CompletableFuture<Void> result = db.submit("write " + deltas.size() + " sack changes", () -> {
            storage.apply(deltas);
            return null;
        });
        unconfirmed.put(seq, new Unconfirmed(deltas, result));
        return result.whenCompleteAsync((ok, error) -> {
            unconfirmed.remove(seq);
            if (error != null) {
                log.log(Level.WARNING, "Could not write " + deltas.size() + " sack changes; retrying with the next flush", error);
                pending.restore(deltas);
            }
        }, db.mainThread()).thenApply(ok -> null);
    }

    /** Takes a stack, or as much as fits, of {@code itemId} out of the player's sack into their inventory. */
    public void withdraw(Player player, String itemId, Withdraw mode, Runnable refresh) {
        SackKey key = keyFor(player);
        SackState state = key == null ? null : states.get(key);
        if (state == null) {
            messages.send(player, key == null ? "no-profile" : "loading");
            return;
        }
        long stored = state.amount(itemId);
        if (stored <= 0) {
            messages.send(player, "empty");
            return;
        }
        ItemStack sample = matcher.create(itemId, 1);
        if (sample == null) {
            messages.send(player, "unknown-item");
            return;
        }
        int space = space(player.getInventory(), sample);
        long want = Math.min(stored, mode == Withdraw.STACK ? Math.min(space, sample.getMaxStackSize()) : space);
        if (want <= 0) {
            messages.send(player, "inventory-full");
            return;
        }
        UUID id = player.getUniqueId();
        if (!busy.tryEnter(id)) {
            messages.send(player, "busy");
            return;
        }
        write(pending.drain(key));
        track(db.submit("withdraw " + want + " " + itemId + " for " + key,
                () -> storage.withdraw(key.owner(), key.scope(), itemId, want)), (taken, error) -> {
            busy.exit(id);
            if (error != null) {
                messages.send(player, "failed");
                return;
            }
            if (!taken) {
                messages.send(player, "changed");
                load(key).thenRun(refresh);
                return;
            }
            SackState current = states.get(key);
            if (current != null) {
                current.add(itemId, -want);
            }
            hand(player, key, itemId, want);
            refresh.run();
        });
    }

    // runs finish on the main thread when the database answers, or from shutdown() if the plugin is disabled first
    private void track(CompletableFuture<Boolean> result, BiConsumer<Boolean, Throwable> finish) {
        long seq = ++finishSeq;
        finishing.put(seq, new Finisher(result, finish));
        result.whenCompleteAsync((value, error) -> {
            Finisher f = finishing.remove(seq);
            if (f != null) {
                f.finish().accept(value, error);
            }
        }, db.mainThread());
    }

    // gives items the database already took out; whatever can't be given goes back into the sack
    private void hand(Player player, SackKey key, String itemId, long qty) {
        if (!player.isOnline() || !key.equals(keyFor(player))) {
            returnToSack(key, itemId, qty);
            return;
        }
        long left = qty;
        long overflow = 0;
        while (left > 0) {
            ItemStack stack = matcher.create(itemId, (int) Math.min(left, Integer.MAX_VALUE));
            if (stack == null) {
                // removed from the config by a reload meanwhile
                overflow += left;
                break;
            }
            left -= stack.getAmount();
            for (ItemStack rest : player.getInventory().addItem(stack).values()) {
                overflow += rest.getAmount();
            }
        }
        if (overflow > 0) {
            returnToSack(key, itemId, overflow);
        }
        long given = qty - overflow;
        if (given > 0) {
            skyblock.save(player.getUniqueId());
            messages.send(player, "withdrew", Map.of("amount", String.format("%,d", given),
                    "item", matcher.displayName(itemId)));
        }
    }

    private void returnToSack(SackKey key, String itemId, long qty) {
        SackState state = states.get(key);
        if (state != null) {
            state.add(itemId, qty);
        }
        pending.add(key, itemId, qty);
    }

    /** Buys the next tier of a sack with coins and items from the player's inventory. */
    public void upgrade(Player player, String sackId, Runnable refresh) {
        SackDefinition sack = registry.get(sackId);
        SackKey key = keyFor(player);
        SackState state = key == null ? null : states.get(key);
        if (sack == null || state == null) {
            messages.send(player, key == null ? "no-profile" : "loading");
            return;
        }
        int stored = state.storedTier(sackId);
        int current = sack.effectiveTier(stored);
        SackDefinition.Tier next = sack.next(current);
        if (next == null) {
            messages.send(player, "sack-maxed");
            return;
        }
        if (next.coins() > 0 && !vault.isReady()) {
            messages.send(player, "economy-missing");
            return;
        }
        if (next.coins() > 0 && !vault.has(player, next.coins())) {
            messages.send(player, "need-coins", Map.of("cost", vault.format(next.coins())));
            return;
        }
        for (SackDefinition.ItemCost cost : next.items()) {
            if (matcher.count(player, cost.itemId()) < cost.amount()) {
                messages.send(player, "need-items", Map.of("amount", String.valueOf(cost.amount()),
                        "item", matcher.displayName(cost.itemId())));
                return;
            }
        }
        UUID id = player.getUniqueId();
        if (!busy.tryEnter(id)) {
            messages.send(player, "busy");
            return;
        }
        List<ItemStack> taken = new ArrayList<>();
        for (SackDefinition.ItemCost cost : next.items()) {
            taken.addAll(matcher.take(player, cost.itemId(), cost.amount()));
        }
        if (next.coins() > 0 && !vault.withdraw(player, next.coins())) {
            giveBack(player, key, taken);
            busy.exit(id);
            messages.send(player, "need-coins", Map.of("cost", vault.format(next.coins())));
            return;
        }
        int target = current + 1;
        track(db.submit("upgrade " + sackId + " to " + target + " for " + key,
                () -> storage.upgrade(key.owner(), key.scope(), sackId, stored, target)), (ok, error) -> {
            busy.exit(id);
            if (error == null && ok) {
                SackState now = states.get(key);
                if (now != null) {
                    now.setTier(sackId, target);
                }
                messages.send(player, "upgraded", Map.of("sack", sack.display(),
                        "tier", next.display(), "capacity", String.format("%,d", next.capacity())));
                refresh.run();
                return;
            }
            if (next.coins() > 0) {
                vault.deposit(player, next.coins());
            }
            giveBack(player, key, taken);
            messages.send(player, error == null ? "changed" : "failed");
            load(key).thenRun(refresh);
        });
    }

    // refunds items taken for a failed purchase into the inventory they came from, or else into the paying
    // profile's sack; only items no sack holds can't be returned, and those are logged for an admin
    private void giveBack(Player player, SackKey key, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        if (player.isOnline() && key.equals(keyFor(player))) {
            for (ItemStack rest : player.getInventory().addItem(items.toArray(new ItemStack[0])).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            skyblock.save(player.getUniqueId());
            return;
        }
        for (ItemStack item : items) {
            String itemId = matcher.idOf(item);
            if (itemId != null && registry.sackOf(itemId) != null) {
                returnToSack(key, itemId, item.getAmount());
            } else {
                log.severe("Could not refund " + item + " to " + player.getName() + " (" + key.scope()
                        + "): they left or switched profile before the sack upgrade failed.");
            }
        }
    }

    /** Admin: puts items into an online player's current sack, ignoring capacity. */
    public boolean give(Player player, String itemId, long qty) {
        SackKey key = keyFor(player);
        if (key == null || registry.sackOf(itemId) == null) {
            return false;
        }
        returnToSack(key, itemId, qty);
        return true;
    }

    /** Admin: takes items out of an online player's current sack. Reports through {@code done} on the main thread. */
    public void take(Player player, String itemId, long qty, Consumer<Boolean> done) {
        SackKey key = keyFor(player);
        if (key == null) {
            done.accept(false);
            return;
        }
        write(pending.drain(key));
        db.submit("admin take " + qty + " " + itemId + " from " + key, () -> storage.withdraw(key.owner(), key.scope(), itemId, qty))
                .whenCompleteAsync((ok, error) -> {
                    boolean taken = error == null && ok;
                    SackState state = states.get(key);
                    if (taken && state != null) {
                        state.add(itemId, -qty);
                    }
                    done.accept(taken);
                }, db.mainThread());
    }

    /**
     * Writes everything pending, drains the database thread, then finishes withdrawals and upgrades whose
     * main-thread callback the disabled plugin can no longer run. For onDisable, on the main thread.
     */
    public void shutdown(Duration timeout) {
        write(pending.drainAll());
        db.shutdown(timeout);
        closing = true;
        List<Finisher> left = new ArrayList<>(finishing.values());
        finishing.clear();
        for (Finisher f : left) {
            CompletableFuture<Boolean> result = f.result();
            if (result.isDone() && !result.isCompletedExceptionally()) {
                f.finish().accept(result.join(), null);
            } else {
                f.finish().accept(null, new IllegalStateException("not run before shutdown"));
            }
        }
        // items handed back by the finishers above, and writes that failed or were cancelled by the drain
        List<SackStorage.Delta> rest = pending.drainAll();
        for (Unconfirmed write : unconfirmed.values()) {
            if (write.result().isCompletedExceptionally()) {
                rest.addAll(write.deltas());
            }
        }
        unconfirmed.clear();
        if (!rest.isEmpty()) {
            try {
                storage.apply(rest);
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not write sack changes at shutdown; these are lost: " + rest, e);
            }
        }
    }

    static int space(PlayerInventory inventory, ItemStack sample) {
        int max = sample.getMaxStackSize();
        int free = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free += max;
            } else if (stack.isSimilar(sample)) {
                free += Math.max(0, max - stack.getAmount());
            }
        }
        return free;
    }
}
