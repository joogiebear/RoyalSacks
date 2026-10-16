package com.mystipixel.royalsacks.hooks;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.function.UnaryOperator;

/**
 * RoyalItems dresses plain vanilla drops (wheat, cobblestone...) with a name and lore. Those dressed stacks are
 * still the vanilla item, so sacks must accept them and hand the same dressing back. Reached by reflection
 * through RoyalItems' registered {@code FormattedItemService}; without it stacks pass through unchanged.
 */
public final class RoyalItemsBridge implements UnaryOperator<ItemStack> {

    private Object service;
    private Method formatIfSupported;
    private boolean failed;

    /** {@code plain} as RoyalItems dresses it, or {@code plain} itself when RoyalItems doesn't. */
    @Override
    public ItemStack apply(ItemStack plain) {
        if (!resolve()) {
            return plain;
        }
        try {
            Object out = formatIfSupported.invoke(service, plain);
            return out instanceof ItemStack dressed ? dressed : plain;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return plain;
        }
    }

    // RoyalItems may register its service after we enable, so keep looking until it is there or broken
    private boolean resolve() {
        if (service != null) {
            return true;
        }
        if (failed || !Bukkit.getPluginManager().isPluginEnabled("RoyalItems")) {
            return false;
        }
        try {
            Class<?> type = Class.forName("com.mystipixel.royalitems.FormattedItemService");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(type);
            if (rsp == null || rsp.getProvider() == null) {
                return false;
            }
            formatIfSupported = type.getMethod("formatIfSupported", ItemStack.class);
            service = rsp.getProvider();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            return false;
        }
    }
}
