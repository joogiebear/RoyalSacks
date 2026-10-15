package com.mystipixel.royalsacks.gui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SackMenuTest {

    @Test
    void tokensAreFilledOnceAndUnknownOnesKept() {
        assertEquals("Wheat: 5/{cap}", SackMenu.fill("{item}: {amount}/{cap}", Map.of("item", "Wheat", "amount", "5")));
        assertEquals("{amount}", SackMenu.fill("{item}", Map.of("item", "{amount}", "amount", "x")));
        assertEquals("", SackMenu.fill(null, Map.of()));
    }
}
