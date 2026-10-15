package com.mystipixel.royalsacks.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SackStorageTest {

    @TempDir
    Path dir;

    private SackStorage storage;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        storage = SackStorage.sqlite(new File(dir.toFile(), "sacks.db"));
    }

    @AfterEach
    void close() {
        storage.close();
    }

    @Test
    void depositsAddUpPerScope() throws Exception {
        storage.apply(List.of(new SackStorage.Delta(owner, "p1", "wheat", 10),
                new SackStorage.Delta(owner, "p1", "carrot", 3),
                new SackStorage.Delta(owner, "p2", "wheat", 1)));
        storage.apply(List.of(new SackStorage.Delta(owner, "p1", "wheat", 5)));
        assertEquals(15L, storage.load(owner, "p1").contents().get("wheat"));
        assertEquals(3L, storage.load(owner, "p1").contents().get("carrot"));
        assertEquals(1L, storage.load(owner, "p2").contents().get("wheat"));
        assertTrue(storage.load(UUID.randomUUID(), "p1").contents().isEmpty());
    }

    @Test
    void aWithdrawalNeverTakesMoreThanIsStored() throws Exception {
        storage.apply(List.of(new SackStorage.Delta(owner, "s", "wheat", 10)));
        assertFalse(storage.withdraw(owner, "s", "wheat", 11));
        assertTrue(storage.withdraw(owner, "s", "wheat", 10));
        assertFalse(storage.withdraw(owner, "s", "wheat", 1));
        assertFalse(storage.withdraw(owner, "s", "carrot", 1));
        assertTrue(storage.load(owner, "s").contents().isEmpty(), "empty rows are not loaded");
    }

    @Test
    void twoServersCannotSpendTheSameItems() throws Exception {
        storage.apply(List.of(new SackStorage.Delta(owner, "s", "wheat", 100)));
        SackStorage other = SackStorage.sqlite(new File(dir.toFile(), "sacks.db"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                SackStorage which = i % 2 == 0 ? storage : other;
                tries.add(() -> which.withdraw(owner, "s", "wheat", 7));
            }
            int succeeded = 0;
            for (Future<Boolean> f : pool.invokeAll(tries)) {
                if (f.get()) {
                    succeeded++;
                }
            }
            assertEquals(14, succeeded, "100 / 7 = 14 withdrawals fit");
            assertEquals(2L, storage.load(owner, "s").contents().get("wheat"));
        } finally {
            pool.shutdown();
            other.close();
        }
    }

    @Test
    void aTierIsOnlyRaisedFromTheOneExpected() throws Exception {
        assertTrue(storage.upgrade(owner, "s", "agronomy", 0, 1));
        assertFalse(storage.upgrade(owner, "s", "agronomy", 0, 1), "a second purchase of the same tier is refused");
        assertTrue(storage.upgrade(owner, "s", "agronomy", 1, 2));
        assertEquals(2, storage.load(owner, "s").tiers().get("agronomy"));
        assertTrue(storage.load(owner, "other").tiers().isEmpty());
    }

    @Test
    void pickupSettingFallsBackUntilSet() throws Exception {
        assertTrue(storage.pickupEnabled(owner, true));
        assertFalse(storage.pickupEnabled(owner, false));
        storage.setPickup(owner, false);
        assertFalse(storage.pickupEnabled(owner, true));
        storage.setPickup(owner, true);
        assertTrue(storage.pickupEnabled(owner, false));
    }

    @Test
    void reopeningKeepsTheSchema() throws Exception {
        storage.apply(List.of(new SackStorage.Delta(owner, "s", "wheat", 4)));
        storage.close();
        storage = SackStorage.sqlite(new File(dir.toFile(), "sacks.db"));
        assertEquals(4L, storage.load(owner, "s").contents().get("wheat"));
    }
}
