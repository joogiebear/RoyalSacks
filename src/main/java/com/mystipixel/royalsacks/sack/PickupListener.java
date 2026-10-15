package com.mystipixel.royalsacks.sack;

import org.bukkit.GameMode;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/** Sends picked-up items to sacks, and loads and saves a player's sacks as they join and leave. */
public final class PickupListener implements Listener {

    private final SackService service;

    public PickupListener(SackService service) {
        this.service = service;
    }

    // HIGHEST, after protection and anything else that may cancel the pickup; a cancelled one is never stored
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.getGameMode() == GameMode.SPECTATOR
                || !player.hasPermission("royalsacks.use")) {
            return;
        }
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        String itemId = service.matcher().idOf(stack);
        if (itemId == null) {
            return;
        }
        int amount = stack.getAmount();
        int stored = service.pickup(player, itemId, amount);
        if (stored <= 0) {
            return;
        }
        event.setCancelled(true);
        player.playPickupItemAnimation(entity, stored);
        if (stored >= amount) {
            entity.remove();
        } else {
            stack.setAmount(amount - stored);
            entity.setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.quit(event.getPlayer());
    }
}
