package com.mystipixel.royalsacks.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemMatcherTest {

    @Test
    void materialNamesReadAsWords() {
        assertEquals("Diamond Ore", ItemMatcher.titleCase("DIAMOND_ORE"));
        assertEquals("Wheat", ItemMatcher.titleCase("WHEAT"));
    }
}
