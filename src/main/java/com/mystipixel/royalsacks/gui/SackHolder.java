package com.mystipixel.royalsacks.gui;

import com.mystipixel.royalsacks.sack.SackKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * Carried on an open sacks menu: whose sacks it shows, which sack ({@code null} for the overview) and what
 * each slot does, so the listener never matches titles or item names.
 */
public final class SackHolder implements InventoryHolder {

    public sealed interface Action permits Open, Take, Upgrade, Back, Page, TogglePickup, Close {
    }

    public record Open(String sackId) implements Action {
    }

    public record Take(String itemId) implements Action {
    }

    public record Upgrade(String sackId) implements Action {
    }

    public record Back() implements Action {
    }

    public record Page(int page) implements Action {
    }

    public record TogglePickup() implements Action {
    }

    public record Close() implements Action {
    }

    private final SackKey key;
    private final String sackId;
    private final int page;
    private final Map<Integer, Action> actions = new HashMap<>();
    private Inventory inventory;

    public SackHolder(SackKey key, String sackId, int page) {
        this.key = key;
        this.sackId = sackId;
        this.page = page;
    }

    public SackKey key() {
        return key;
    }

    public String sackId() {
        return sackId;
    }

    public int page() {
        return page;
    }

    public Action action(int slot) {
        return actions.get(slot);
    }

    void bind(int slot, Action action) {
        actions.put(slot, action);
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
