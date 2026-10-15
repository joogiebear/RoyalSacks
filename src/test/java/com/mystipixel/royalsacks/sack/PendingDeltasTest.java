package com.mystipixel.royalsacks.sack;

import com.mystipixel.royalsacks.storage.SackStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingDeltasTest {

    private final UUID owner = UUID.randomUUID();
    private final SackKey a = new SackKey(owner, "a");
    private final SackKey b = new SackKey(owner, "b");

    @Test
    void changesToOneRowAreSummed() {
        PendingDeltas pending = new PendingDeltas();
        pending.add(a, "stone", 5);
        pending.add(a, "stone", 3);
        pending.add(a, "dirt", 1);
        assertEquals(8, pending.of(a, "stone"));
        assertEquals(2, pending.drainAll().size());
        assertTrue(pending.isEmpty());
    }

    @Test
    void changesThatCancelOutLeaveNothing() {
        PendingDeltas pending = new PendingDeltas();
        pending.add(a, "stone", 5);
        pending.add(a, "stone", -5);
        assertTrue(pending.isEmpty());
    }

    @Test
    void drainingOneKeyLeavesTheOthers() {
        PendingDeltas pending = new PendingDeltas();
        pending.add(a, "stone", 5);
        pending.add(b, "stone", 2);
        assertEquals(List.of(new SackStorage.Delta(owner, "a", "stone", 5)), pending.drain(a));
        assertEquals(2, pending.of(b, "stone"));
        assertEquals(0, pending.of(a, "stone"));
    }

    @Test
    void aFailedWriteIsAddedBackToNewerChanges() {
        PendingDeltas pending = new PendingDeltas();
        pending.add(a, "stone", 5);
        List<SackStorage.Delta> failed = pending.drainAll();
        pending.add(a, "stone", 2);
        pending.restore(failed);
        assertEquals(7, pending.of(a, "stone"));
    }
}
