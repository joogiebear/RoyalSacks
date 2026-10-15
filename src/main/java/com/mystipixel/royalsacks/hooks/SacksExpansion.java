package com.mystipixel.royalsacks.hooks;

import com.mystipixel.royalsacks.sack.SackDefinition;
import com.mystipixel.royalsacks.sack.SackKey;
import com.mystipixel.royalsacks.sack.SackRegistry;
import com.mystipixel.royalsacks.sack.SackService;
import com.mystipixel.royalsacks.sack.SackState;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * {@code %royalsacks_amount_<item>%}, {@code %royalsacks_capacity_<item>%}, {@code %royalsacks_tier_<sack>%},
 * {@code %royalsacks_stored_<sack>%} and {@code %royalsacks_pickup%}, for online players. Item ids keep their
 * namespace: {@code %royalsacks_amount_ecoitems:enchanted_wheat%}. Only loaded when PlaceholderAPI is.
 */
public final class SacksExpansion extends PlaceholderExpansion {

    private final SackService service;
    private final String version;

    public SacksExpansion(SackService service, String version) {
        this.service = service;
        this.version = version;
    }

    @Override
    public String getIdentifier() {
        return "royalsacks";
    }

    @Override
    public String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        if (!(offline instanceof Player player) || !player.isOnline()) {
            return "";
        }
        if (params.equals("pickup")) {
            return String.valueOf(service.pickupEnabled(player));
        }
        SackKey key = service.keyFor(player);
        SackState state = key == null ? null : service.state(key);
        if (state == null) {
            return "";
        }
        int split = params.indexOf('_');
        if (split <= 0) {
            return null;
        }
        String kind = params.substring(0, split);
        String arg = params.substring(split + 1);
        SackRegistry registry = service.registry();
        switch (kind) {
            case "amount" -> {
                return String.valueOf(state.amount(SackRegistry.normalize(arg)));
            }
            case "capacity" -> {
                SackDefinition sack = registry.sackOf(SackRegistry.normalize(arg));
                return sack == null ? "0" : String.valueOf(sack.capacity(state.tier(sack)));
            }
            case "tier" -> {
                SackDefinition sack = registry.get(arg);
                return sack == null ? "0" : String.valueOf(state.tier(sack));
            }
            case "stored" -> {
                SackDefinition sack = registry.get(arg);
                return sack == null ? "0" : String.valueOf(state.total(sack));
            }
            default -> {
                return null;
            }
        }
    }
}
