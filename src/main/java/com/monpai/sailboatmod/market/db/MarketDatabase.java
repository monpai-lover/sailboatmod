package com.monpai.sailboatmod.market.db;

import com.monpai.sailboatmod.ModConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class MarketDatabase {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STANDARD_SQLITE_DRIVER = "org.sqlite.JDBC";

    private static Connection activeConnection;
    private static Path activeDatabasePath;

    private MarketDatabase() {
    }

    public static synchronized void initialize(MinecraftServer server) throws SQLException, IOException, ClassNotFoundException {
        if (server == null || !ModConfig.marketSqliteEnabled()) {
            shutdown();
            return;
        }

        Path databasePath = resolveDatabasePath(server);
        if (activeConnection != null && !isClosed(activeConnection) && databasePath.equals(activeDatabasePath)) {
            return;
        }

        shutdown();

        Files.createDirectories(databasePath.getParent());
        ensureSqliteDriverLoaded();
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        configureConnection(opened);
        MarketSchemaManager.applyPatches(opened);

        activeConnection = opened;
        activeDatabasePath = databasePath;
        LOGGER.info("Initialized market SQLite database at {}", databasePath.toAbsolutePath());
    }

    public static synchronized void shutdown() {
        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (SQLException exception) {
                LOGGER.warn("Failed to close market SQLite database cleanly", exception);
            }
        }
        activeConnection = null;
        activeDatabasePath = null;
    }

    public static synchronized boolean isInitialized() {
        return activeConnection != null && !isClosed(activeConnection);
    }

    public static synchronized Connection getConnection() throws SQLException {
        if (!isInitialized()) {
            throw new SQLException("Market SQLite database is not initialized");
        }
        return activeConnection;
    }

    public static synchronized Path getDatabasePath() {
        return activeDatabasePath;
    }

    private static Path resolveDatabasePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("sailboatmod_market")
                .resolve(ModConfig.marketSqliteFileName());
    }

    private static void configureConnection(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static boolean isClosed(Connection connection) {
        try {
            return connection == null || connection.isClosed();
        } catch (SQLException ignored) {
            return true;
        }
    }

    private static void ensureSqliteDriverLoaded() throws ClassNotFoundException {
        Class.forName(STANDARD_SQLITE_DRIVER);
    }
}
