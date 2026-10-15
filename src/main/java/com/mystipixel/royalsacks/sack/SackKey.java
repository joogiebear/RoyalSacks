package com.mystipixel.royalsacks.sack;

import java.util.UUID;

/** One player's sacks in one scope (a RoyalSkyblock profile, or "global"). */
public record SackKey(UUID owner, String scope) {
}
