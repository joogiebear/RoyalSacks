package com.mystipixel.royalsacks.sack;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SackRegistryTest {

    private static SackDefinition parse(String id, String yaml, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return SackRegistry.parse(id, config, warnings::add);
    }

    @Test
    void readsItemsTiersAndCosts() throws Exception {
        List<String> warnings = new ArrayList<>();
        SackDefinition sack = parse("agronomy", """
                display: "&aAgronomy"
                items: [Wheat, minecraft:carrot, ecoitems:enchanted_wheat, wheat]
                tiers:
                  small: { capacity: 100 }
                  medium: { display: Medium, capacity: 1000, cost: { coins: 50, items: ["ecoitems:enchanted_leather 4", "diamond"] } }
                """, warnings);
        assertEquals(List.of("wheat", "carrot", "ecoitems:enchanted_wheat"), sack.items());
        assertEquals("wheat", sack.icon(), "the icon defaults to the first item");
        assertEquals(2, sack.tiers().size());
        SackDefinition.Tier medium = sack.tiers().get(1);
        assertEquals(50, medium.coins());
        assertEquals(List.of(new SackDefinition.ItemCost("ecoitems:enchanted_leather", 4),
                new SackDefinition.ItemCost("diamond", 1)), medium.items());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aTierThatDoesNotGrowIsSkipped() throws Exception {
        List<String> warnings = new ArrayList<>();
        SackDefinition sack = parse("x", """
                items: [stone]
                tiers:
                  a: { capacity: 100 }
                  b: { capacity: 100 }
                  c: { capacity: 200 }
                """, warnings);
        assertEquals(List.of(100L, 200L), sack.tiers().stream().map(SackDefinition.Tier::capacity).toList());
        assertEquals(1, warnings.size());
    }

    @Test
    void aSackWithoutItemsOrTiersIsSkipped() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertNull(parse("a", "tiers: { s: { capacity: 1 } }", warnings));
        assertNull(parse("b", "items: [stone]", warnings));
        assertEquals(2, warnings.size());
    }

    @Test
    void anItemBelongsToTheFirstSackThatListsIt() {
        SackDefinition.Tier tier = new SackDefinition.Tier("S", 10, 0, List.of());
        SackDefinition first = new SackDefinition("first", "First", "stone", List.of("stone", "dirt"), List.of(tier));
        SackDefinition second = new SackDefinition("second", "Second", "stone", List.of("stone", "sand"), List.of(tier));
        List<String> warnings = new ArrayList<>();
        SackRegistry registry = SackRegistry.of(List.of(first, second), warnings::add);
        assertEquals("first", registry.sackOf("stone").id());
        assertEquals(List.of("sand"), registry.get("second").items());
        assertSame(registry.get("second"), registry.sackOf("sand"));
        assertEquals(1, warnings.size());
    }

    @Test
    void vanillaIdsLoseTheirNamespaceAndCase() {
        assertEquals("wheat", SackRegistry.normalize(" Minecraft:WHEAT "));
        assertEquals("ecoitems:Enchanted_Wheat", SackRegistry.normalize("ecoitems:Enchanted_Wheat"));
    }

    @Test
    void aFreeFirstTierIsUnlockedForEveryone() {
        SackDefinition.Tier free = new SackDefinition.Tier("S", 10, 0, List.of());
        SackDefinition.Tier paid = new SackDefinition.Tier("M", 100, 5, List.of());
        SackDefinition freeStart = new SackDefinition("a", "A", "stone", List.of("stone"), List.of(free, paid));
        SackDefinition paidStart = new SackDefinition("b", "B", "dirt", List.of("dirt"), List.of(paid));
        assertEquals(1, freeStart.effectiveTier(0));
        assertEquals(10, freeStart.capacity(freeStart.effectiveTier(0)));
        assertEquals(0, paidStart.effectiveTier(0));
        assertEquals(0, paidStart.capacity(0));
        assertSame(paid, freeStart.next(1));
        assertNull(freeStart.next(2));
        assertEquals(2, freeStart.effectiveTier(7), "a stored tier above the configured ones is capped");
    }

    @Test
    void roomIsCapacityLessWhatIsStored() {
        SackDefinition.Tier small = new SackDefinition.Tier("S", 10, 0, List.of());
        SackDefinition sack = new SackDefinition("a", "A", "stone", List.of("stone", "dirt"), List.of(small));
        SackState state = new SackState(java.util.Map.of("stone", 7L), java.util.Map.of());
        assertEquals(3, state.room(sack, "stone"));
        assertEquals(10, state.room(sack, "dirt"));
        state.add("stone", 5);
        assertEquals(0, state.room(sack, "stone"));
        assertEquals(12, state.total(sack));
        state.add("stone", -12);
        assertEquals(0, state.amount("stone"));
    }
}
