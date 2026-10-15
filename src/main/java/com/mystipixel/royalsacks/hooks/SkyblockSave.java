package com.mystipixel.royalsacks.hooks;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Asks RoyalSkyblock to save a player's profile now, after items moved between a sack and their inventory,
 * so a crash can't roll the inventory back behind the sack. A no-op without RoyalSkyblock.
 */
public final class SkyblockSave {

    private final Method savePlayerNow;

    public SkyblockSave() {
        Method found = null;
        try {
            found = Class.forName("com.mystipixel.royalskyblock.api.RoyalSkyblockApi")
                    .getMethod("savePlayerNow", UUID.class);
        } catch (ReflectiveOperationException | LinkageError notInstalled) {
            // RoyalSkyblock absent or too old
        }
        this.savePlayerNow = found;
    }

    public void save(UUID player) {
        if (savePlayerNow == null) {
            return;
        }
        try {
            savePlayerNow.invoke(null, player);
        } catch (ReflectiveOperationException e) {
            // RoyalSkyblock disabled since startup; its next autosave covers it
        }
    }
}
