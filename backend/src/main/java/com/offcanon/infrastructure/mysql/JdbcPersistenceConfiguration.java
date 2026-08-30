package com.offcanon.infrastructure.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@Profile("mysql")
public class JdbcPersistenceConfiguration {
    @Bean(destroyMethod = "close")
    public DataSource dataSource(
            @Value("${offcanon.mysql.url:${OFFCANON_MYSQL_URL:jdbc:mysql://localhost:3306/offcanon?createDatabaseIfNotExist=true&serverTimezone=UTC}}") String url,
            @Value("${offcanon.mysql.username:${OFFCANON_MYSQL_USERNAME:offcanon}}") String username,
            @Value("${offcanon.mysql.password:${OFFCANON_MYSQL_PASSWORD:}}") String password,
            @Value("${offcanon.mysql.maximum-pool-size:${OFFCANON_MYSQL_POOL_SIZE:8}}") int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("offcanon-mysql");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMinimumIdle(1);
        config.setMaximumPoolSize(Math.max(2, maximumPoolSize));
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.setInitializationFailTimeout(10_000);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public org.springframework.boot.CommandLineRunner schemaInitializer(DataSource dataSource) {
        return ignored -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer tableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_type IN ('BASE TABLE', 'VIEW')",
                    Integer.class);
            // Bootstrap only a genuinely empty development database.  Once any
            // table exists, CREATE IF NOT EXISTS would hide a partial/stale
            // schema; the validator must see it unchanged and fail fast.
            if (tableCount == null || tableCount == 0) {
                new ResourceDatabasePopulator(new ClassPathResource("schema-mysql.sql")).execute(dataSource);
            }
            MysqlSchemaValidator.validate(dataSource);
        };
    }
}
