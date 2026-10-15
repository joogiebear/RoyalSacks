package com.mystipixel.royalsacks.storage;

import com.mystipixel.royalcommon.storage.Dialect;
import com.mystipixel.royalcommon.storage.SchemaVersion;
import com.mystipixel.royalcommon.storage.TxRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Sack contents over HikariCP (SQLite or MySQL). The rows are the truth: every change is a delta applied in
 * SQL ({@code qty = qty + ?}) and a withdrawal only commits when the row still holds enough, so servers
 * sharing one MySQL database can never both spend the same items. Call everything on the plugin's database
 * thread.
 */
public final class SackStorage {

    static final int SCHEMA_VERSION = 1;

    /** Contents and unlocked tiers of one player's sacks in one scope. */
    public record Snapshot(Map<String, Long> contents, Map<String, Integer> tiers) {
    }

    /** One pending change to a row, positive for a deposit. */
    public record Delta(UUID owner, String scope, String itemId, long qty) {
    }

    public record MySqlSettings(String host, int port, String database, String username, String password,
                                String properties, int poolSize) {
    }

    private final HikariDataSource dataSource;
    private final TxRunner db;
    private final Dialect dialect;

    private SackStorage(HikariDataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        this.db = new TxRunner(dataSource);
        this.dialect = db.dialect();
    }

    public static SackStorage sqlite(File file) throws SQLException {
        loadDriver("org.sqlite.JDBC");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RoyalSacks");
        hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(SqliteSettings.POOL_SIZE);
        hikari.setDataSourceProperties(SqliteSettings.properties());
        return open(hikari);
    }

    public static SackStorage mysql(MySqlSettings my) throws SQLException {
        loadDriver("com.mysql.cj.jdbc.Driver");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("RoyalSacks");
        hikari.setJdbcUrl("jdbc:mysql://" + my.host() + ":" + my.port() + "/" + my.database() + "?" + my.properties());
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikari.setUsername(my.username());
        hikari.setPassword(my.password());
        hikari.setMaximumPoolSize(Math.max(1, my.poolSize()));
        return open(hikari);
    }

    private static SackStorage open(HikariConfig hikari) throws SQLException {
        HikariDataSource dataSource = new HikariDataSource(hikari);
        try {
            SackStorage storage = new SackStorage(dataSource);
            storage.migrate();
            return storage;
        } catch (SQLException | RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    // the plugin's class loader holds the drivers Paper's library loader fetched
    private static void loadDriver(String driverClass) {
        try {
            Class.forName(driverClass, true, SackStorage.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            Logger.getLogger("RoyalSacks").warning("JDBC driver not found: " + driverClass);
        }
    }

    public Dialect dialect() {
        return dialect;
    }

    // servers sharing a MySQL database that start together take turns here; the second finds nothing to do
    private void migrate() throws SQLException {
        new SchemaVersion(db, "sacks").ensure(SCHEMA_VERSION, (c, d, from, to) -> {
            if (from < 1) {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS sack_contents ("
                            + "owner VARCHAR(36) NOT NULL, "
                            + "scope VARCHAR(64) NOT NULL, "
                            + "item_id VARCHAR(128) NOT NULL, "
                            + "qty BIGINT NOT NULL DEFAULT 0, "
                            + "PRIMARY KEY (owner, scope, item_id))");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS sack_tiers ("
                            + "owner VARCHAR(36) NOT NULL, "
                            + "scope VARCHAR(64) NOT NULL, "
                            + "sack_id VARCHAR(64) NOT NULL, "
                            + "tier INT NOT NULL DEFAULT 0, "
                            + "PRIMARY KEY (owner, scope, sack_id))");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS sack_players ("
                            + "owner VARCHAR(36) NOT NULL PRIMARY KEY, "
                            + "pickup INT NOT NULL DEFAULT 1)");
                }
            }
        });
    }

    public Snapshot load(UUID owner, String scope) throws SQLException {
        Map<String, Long> contents = new HashMap<>();
        Map<String, Integer> tiers = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT item_id, qty FROM sack_contents WHERE owner = ? AND scope = ? AND qty > 0")) {
                st.setString(1, owner.toString());
                st.setString(2, scope);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        contents.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT sack_id, tier FROM sack_tiers WHERE owner = ? AND scope = ?")) {
                st.setString(1, owner.toString());
                st.setString(2, scope);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        tiers.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
        }
        return new Snapshot(contents, tiers);
    }

    /** Applies every delta in one transaction, or none of them. */
    public void apply(List<Delta> deltas) throws SQLException {
        if (deltas.isEmpty()) {
            return;
        }
        String upsert = dialect == Dialect.MYSQL
                ? "INSERT INTO sack_contents (owner, scope, item_id, qty) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE qty = qty + VALUES(qty)"
                : "INSERT INTO sack_contents (owner, scope, item_id, qty) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(owner, scope, item_id) DO UPDATE SET qty = qty + excluded.qty";
        db.inTransaction(tx -> {
            try (PreparedStatement st = tx.connection().prepareStatement(upsert)) {
                for (Delta d : deltas) {
                    st.setString(1, d.owner().toString());
                    st.setString(2, d.scope());
                    st.setString(3, d.itemId());
                    st.setLong(4, d.qty());
                    st.addBatch();
                }
                st.executeBatch();
            }
            return null;
        });
    }

    /** Takes {@code qty} out of a sack if it still holds that many. Returns whether it did. */
    public boolean withdraw(UUID owner, String scope, String itemId, long qty) throws SQLException {
        return db.inTransaction(tx -> {
            try (PreparedStatement st = tx.connection().prepareStatement(
                    "UPDATE sack_contents SET qty = qty - ? WHERE owner = ? AND scope = ? AND item_id = ? AND qty >= ?")) {
                st.setLong(1, qty);
                st.setString(2, owner.toString());
                st.setString(3, scope);
                st.setString(4, itemId);
                st.setLong(5, qty);
                return st.executeUpdate() == 1;
            }
        });
    }

    /**
     * Raises a sack from {@code expected} to {@code tier}. Refused when another server already changed it,
     * so a tier is never paid for twice.
     */
    public boolean upgrade(UUID owner, String scope, String sackId, int expected, int tier) throws SQLException {
        return db.inTransaction(tx -> {
            Connection c = tx.connection();
            try (PreparedStatement st = c.prepareStatement(tx.dialect().insertIgnore()
                    + " INTO sack_tiers (owner, scope, sack_id, tier) VALUES (?, ?, ?, 0)")) {
                st.setString(1, owner.toString());
                st.setString(2, scope);
                st.setString(3, sackId);
                st.executeUpdate();
            }
            try (PreparedStatement st = c.prepareStatement(
                    "UPDATE sack_tiers SET tier = ? WHERE owner = ? AND scope = ? AND sack_id = ? AND tier = ?")) {
                st.setInt(1, tier);
                st.setString(2, owner.toString());
                st.setString(3, scope);
                st.setString(4, sackId);
                st.setInt(5, expected);
                return st.executeUpdate() == 1;
            }
        });
    }

    public boolean pickupEnabled(UUID owner, boolean fallback) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT pickup FROM sack_players WHERE owner = ?")) {
            st.setString(1, owner.toString());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) == 1 : fallback;
            }
        }
    }

    public void setPickup(UUID owner, boolean enabled) throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "INSERT INTO sack_players (owner, pickup) VALUES (?, ?) ON DUPLICATE KEY UPDATE pickup = VALUES(pickup)"
                : "INSERT INTO sack_players (owner, pickup) VALUES (?, ?) ON CONFLICT(owner) DO UPDATE SET pickup = excluded.pickup";
        db.inTransaction(tx -> {
            try (PreparedStatement st = tx.connection().prepareStatement(sql)) {
                st.setString(1, owner.toString());
                st.setInt(2, enabled ? 1 : 0);
                st.executeUpdate();
            }
            return null;
        });
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
