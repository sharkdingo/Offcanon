package com.pico.infrastructure.mysql;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@Profile("mysql")
public class JdbcPersistenceConfiguration {
    @Bean
    public DataSource dataSource(
            @Value("${pico.mysql.url:${PICO_MYSQL_URL:jdbc:mysql://localhost:3306/pico?createDatabaseIfNotExist=true&serverTimezone=UTC}}") String url,
            @Value("${pico.mysql.username:${PICO_MYSQL_USERNAME:pico}}") String username,
            @Value("${pico.mysql.password:${PICO_MYSQL_PASSWORD:}}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
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
        return ignored -> new ResourceDatabasePopulator(new ClassPathResource("schema-mysql.sql")).execute(dataSource);
    }
}
