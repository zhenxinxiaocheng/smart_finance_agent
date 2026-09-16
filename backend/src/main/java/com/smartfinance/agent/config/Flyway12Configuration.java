package com.smartfinance.agent.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

// Boot 3.2 calls Flyway APIs removed in Flyway 12; keep this compatibility bridge.
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true")
public class Flyway12Configuration {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource,
                         @Value("${spring.flyway.locations:classpath:db/migration}") String locations,
                         @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(splitLocations(locations))
                .baselineOnMigrate(baselineOnMigrate)
                .load();
    }

    private static String[] splitLocations(String locations) {
        return java.util.Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
    }
}
