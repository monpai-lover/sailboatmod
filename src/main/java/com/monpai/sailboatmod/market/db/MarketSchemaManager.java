package com.monpai.sailboatmod.market.db;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MarketSchemaManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<SchemaPatch> PATCHES = List.of(
            new SchemaPatch(
                    "001_base_market_schema",
                    List.of(
                            """
                            CREATE TABLE IF NOT EXISTS market_schema_patch (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                patch_key TEXT NOT NULL UNIQUE,
                                applied_at INTEGER NOT NULL
                            )
                            """,
                            """
                            CREATE TABLE IF NOT EXISTS commodity_definition (
                                commodity_key TEXT PRIMARY KEY,
                                item_id TEXT NOT NULL,
                                variant_key TEXT NOT NULL DEFAULT '',
                                display_name TEXT NOT NULL DEFAULT '',
                                unit_size INTEGER NOT NULL DEFAULT 1,
                                category TEXT NOT NULL DEFAULT '',
                                trade_enabled INTEGER NOT NULL DEFAULT 1
                            )
                            """,
                            """
                            CREATE TABLE IF NOT EXISTS commodity_market_state (
                                commodity_key TEXT PRIMARY KEY,
                                base_price INTEGER NOT NULL,
                                current_stock INTEGER NOT NULL DEFAULT 0,
                                volatility INTEGER NOT NULL DEFAULT 100,
                                spread_bp INTEGER NOT NULL DEFAULT 500,
                                stock_floor INTEGER NOT NULL DEFAULT -2147483648,
                                stock_ceil INTEGER NOT NULL DEFAULT 2147483647,
                                price_floor INTEGER NOT NULL DEFAULT 0,
                                price_ceil INTEGER NOT NULL DEFAULT 2147483647,
                                last_trade_at INTEGER NOT NULL DEFAULT 0,
                                updated_at INTEGER NOT NULL DEFAULT 0,
                                version INTEGER NOT NULL DEFAULT 0,
                                FOREIGN KEY (commodity_key) REFERENCES commodity_definition (commodity_key) ON DELETE CASCADE
                            )
                            """,
                            """
                            CREATE TABLE IF NOT EXISTS commodity_trade_history (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                commodity_key TEXT NOT NULL,
                                trade_side TEXT NOT NULL,
                                quantity INTEGER NOT NULL,
                                unit_price INTEGER NOT NULL,
                                total_price INTEGER NOT NULL,
                                source_market_pos TEXT NOT NULL DEFAULT '',
                                source_nation_id TEXT NOT NULL DEFAULT '',
                                target_nation_id TEXT NOT NULL DEFAULT '',
                                actor_uuid TEXT NOT NULL DEFAULT '',
                                actor_name TEXT NOT NULL DEFAULT '',
                                created_at INTEGER NOT NULL,
                                FOREIGN KEY (commodity_key) REFERENCES commodity_definition (commodity_key) ON DELETE CASCADE
                            )
                            """,
                            "CREATE INDEX IF NOT EXISTS idx_trade_history_commodity_time ON commodity_trade_history (commodity_key, created_at DESC)",
                            "CREATE INDEX IF NOT EXISTS idx_trade_history_created_at ON commodity_trade_history (created_at DESC)"
                    )
            ),
            new SchemaPatch(
                    "002_add_commodity_attributes",
                    List.of(
                            "ALTER TABLE commodity_definition ADD COLUMN rarity INTEGER NOT NULL DEFAULT 0",
                            "ALTER TABLE commodity_definition ADD COLUMN importance INTEGER NOT NULL DEFAULT 1",
                            "ALTER TABLE commodity_definition ADD COLUMN volume INTEGER NOT NULL DEFAULT 2",
                            "ALTER TABLE commodity_definition ADD COLUMN elasticity INTEGER NOT NULL DEFAULT 1",
                            "ALTER TABLE commodity_definition ADD COLUMN base_volatility INTEGER NOT NULL DEFAULT 100"
                    )
            ),
            new SchemaPatch(
                    "003_player_market_settings",
                    List.of(
                            """
                            CREATE TABLE IF NOT EXISTS player_market_settings (
                                player_uuid TEXT PRIMARY KEY,
                                buy_price_adjustment_bp INTEGER NOT NULL DEFAULT 0,
                                sell_price_adjustment_bp INTEGER NOT NULL DEFAULT 0
                            )
                            """
                    )
            ),
            new SchemaPatch(
                    "004_buy_orders",
                    List.of(
                            """
                            CREATE TABLE IF NOT EXISTS buy_order (
                                order_id TEXT PRIMARY KEY,
                                buyer_uuid TEXT NOT NULL,
                                buyer_name TEXT NOT NULL,
                                commodity_key TEXT NOT NULL,
                                quantity INTEGER NOT NULL,
                                min_price_bp INTEGER NOT NULL,
                                max_price_bp INTEGER NOT NULL,
                                created_at INTEGER NOT NULL,
                                status TEXT NOT NULL DEFAULT 'ACTIVE'
                            )
                            """,
                            "CREATE INDEX IF NOT EXISTS idx_buy_order_commodity ON buy_order (commodity_key, status)",
                            "CREATE INDEX IF NOT EXISTS idx_buy_order_buyer ON buy_order (buyer_uuid, status)"
                    )
            )
    );

    private MarketSchemaManager() {
    }

    static void applyPatches(Connection connection) throws SQLException {
        ensurePatchTable(connection);
        Set<String> applied = getAppliedPatches(connection);
        for (SchemaPatch patch : PATCHES) {
            if (applied.contains(patch.key())) {
                continue;
            }
            applyPatch(connection, patch);
        }
    }

    private static void ensurePatchTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS market_schema_patch (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        patch_key TEXT NOT NULL UNIQUE,
                        applied_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    private static Set<String> getAppliedPatches(Connection connection) throws SQLException {
        Set<String> applied = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT patch_key FROM market_schema_patch")) {
            while (resultSet.next()) {
                applied.add(resultSet.getString("patch_key"));
            }
        }
        return applied;
    }

    private static void applyPatch(Connection connection, SchemaPatch patch) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : patch.statements()) {
                statement.execute(sql);
            }
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "INSERT INTO market_schema_patch (patch_key, applied_at) VALUES (?, ?)")) {
                preparedStatement.setString(1, patch.key());
                preparedStatement.setLong(2, System.currentTimeMillis());
                preparedStatement.executeUpdate();
            }
            connection.commit();
            LOGGER.info("Applied market schema patch {}", patch.key());
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private record SchemaPatch(String key, List<String> statements) {
    }
}