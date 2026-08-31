package com.offcanon.infrastructure.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Application-owned SQLite persistence. There is no external database to configure. */
@Configuration
public class SqlitePersistenceConfiguration {
    @Bean(destroyMethod = "close")
    public DataSource dataSource(@Value("${offcanon.data-root}") String dataRoot,
                                 ApplicationInstanceLock instanceLock) {
        try {
            java.util.Objects.requireNonNull(instanceLock, "instanceLock");
            Path root = Path.of(dataRoot).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Offcanon data directory must not be a symbolic link");
            }
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Offcanon data directory must be a real directory");
            }
            try {
                Files.setPosixFilePermissions(root, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException | java.io.IOException ignored) {
                // Windows and other providers rely on inherited ACLs.
            }
            HikariConfig config = new HikariConfig();
            config.setPoolName("offcanon-sqlite");
            config.setDriverClassName("org.sqlite.JDBC");
            // SQLite connection properties must be part of the URL.  The
            // xerial driver executes only one statement from Hikari's
            // connectionInitSql, so a semicolon-separated PRAGMA string would
            // silently leave pooled connections in DELETE-journal mode.
            String database = root.resolve("offcanon.sqlite").toString().replace('\\', '/');
            config.setJdbcUrl("jdbc:sqlite:" + database
                    + "?foreign_keys=on&busy_timeout=10000&journal_mode=WAL");
            config.setMinimumIdle(1);
            config.setMaximumPoolSize(8);
            config.setConnectionTimeout(10_000);
            config.setValidationTimeout(3_000);
            config.setInitializationFailTimeout(10_000);
            return new HikariDataSource(config);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialise Offcanon data directory", error);
        }
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setExceptionTranslator(new SqliteSQLExceptionTranslator());
        return template;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
