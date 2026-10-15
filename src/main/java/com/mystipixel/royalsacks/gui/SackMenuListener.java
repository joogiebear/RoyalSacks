package com.mystipixel.royalsacks.gui;

import com.mystipixel.royalsacks.sack.SackService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

/**
 * Every click in a sacks menu is cancelled and acted on by slot. Nothing can be moved into the menu: a sack
 * only takes items through pickup, so a click that would move items across is blocked as well.
 */
public final class SackMenuListener implements Listener {

    private final Plugin plugin;
    private final SackService service;
    private final SackMenu menu;

    public SackMenuListener(Plugin plugin, SackService service, SackMenu menu) {
        this.plugin = plugin;
        this.service = service;
        this.menu = menu;
    }

    // HIGHEST so no later listener un-cancels a click into the menu
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof SackHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder(false) instanceof SackHolder)
                || event.getAction() == InventoryAction.NOTHING) {
            return;
        }
        // the profile changed under the open menu: its contents belong to the old one
        if (!holder.key().equals(service.keyFor(player))) {
            player.closeInventory();
            return;
        }
        SackHolder.Action action = holder.action(event.getSlot());
        if (action == null) {
            return;
        }
        ClickType click = event.getClick();
        // next tick: opening another inventory inside a click event leaves the client out of sync
        plugin.getServer().getScheduler().runTask(plugin, () -> act(player, holder, action, click));
    }

    private void act(Player player, SackHolder holder, SackHolder.Action action, ClickType click) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
            return;
        }
        switch (action) {
            case SackHolder.Open open -> menu.openSack(player, open.sackId(), 0);
            case SackHolder.Take take -> {
                if (click.isLeftClick() || click.isRightClick()) {
                    SackService.Withdraw mode = click.isRightClick() || click.isShiftClick()
                            ? SackService.Withdraw.FILL : SackService.Withdraw.STACK;
                    service.withdraw(player, take.itemId(), mode, () -> menu.refresh(player));
                }
            }
            case SackHolder.Upgrade upgrade -> service.upgrade(player, upgrade.sackId(), () -> menu.refresh(player));
            case SackHolder.Page page -> menu.openSack(player, holder.sackId(), page.page());
            case SackHolder.Back back -> menu.openMain(player);
            case SackHolder.TogglePickup toggle -> {
                service.setPickup(player, !service.pickupEnabled(player));
                menu.refresh(player);
            }
            case SackHolder.Close close -> player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof SackHolder) {
            event.setCancelled(true);
        }
    }
}
