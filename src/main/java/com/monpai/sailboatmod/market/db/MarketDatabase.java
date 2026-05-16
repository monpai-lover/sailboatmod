package com.monpai.sailboatmod.market.db;

import com.monpai.sailboatmod.ModConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class MarketDatabase {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STANDARD_SQLITE_DRIVER = "org.sqlite.JDBC";
    private static final String SQLITE_JDBC_VERSION = "3.46.1.3";
    private static final String SQLITE_JDBC_JAR_NAME = "sqlite-jdbc-" + SQLITE_JDBC_VERSION + ".jar";
    private static final String EMBEDDED_SQLITE_DRIVER_RESOURCE = "/META-INF/sailboatmod-libs/sqlite-jdbc-" + SQLITE_JDBC_VERSION + ".jar";

    private static Connection activeConnection;
    private static Path activeDatabasePath;
    private static Driver embeddedDriverShim;
    @SuppressWarnings("resource")
    private static URLClassLoader embeddedDriverClassLoader;

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

    private static void ensureSqliteDriverLoaded() throws ClassNotFoundException, IOException, SQLException {
        try {
            Class.forName(STANDARD_SQLITE_DRIVER);
        } catch (ClassNotFoundException missingFromEnvironment) {
            loadEmbeddedSqliteDriver(missingFromEnvironment);
        }
    }

    private static void loadEmbeddedSqliteDriver(ClassNotFoundException missingFromEnvironment) throws ClassNotFoundException, IOException, SQLException {
        if (embeddedDriverShim != null) {
            return;
        }

        Path driverJar = extractEmbeddedSqliteDriverJar();
        URLClassLoader loader = new URLClassLoader(new URL[]{driverJar.toUri().toURL()}, MarketDatabase.class.getClassLoader());
        try {
            Class<?> driverClass = Class.forName(STANDARD_SQLITE_DRIVER, true, loader);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            Driver shim = new DriverShim(driver);
            DriverManager.registerDriver(shim);
            embeddedDriverShim = shim;
            embeddedDriverClassLoader = loader;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError exception) {
            try {
                loader.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            ClassNotFoundException failure = new ClassNotFoundException("Unable to load bundled sqlite-jdbc driver", exception);
            failure.addSuppressed(missingFromEnvironment);
            throw failure;
        }
    }

    private static Path extractEmbeddedSqliteDriverJar() throws IOException {
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "sailboatmod", "sqlite");
        Path target = cacheDir.resolve(SQLITE_JDBC_JAR_NAME);
        try (InputStream input = MarketDatabase.class.getResourceAsStream(EMBEDDED_SQLITE_DRIVER_RESOURCE)) {
            if (input == null) {
                throw new FileNotFoundException("Bundled sqlite-jdbc resource not found: " + EMBEDDED_SQLITE_DRIVER_RESOURCE);
            }
            Files.createDirectories(cacheDir);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static final class DriverShim implements Driver {
        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
