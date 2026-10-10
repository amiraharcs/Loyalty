package com.amiraharcs.loyalty.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Fail before datasource creation, reporting missing names without logging secrets. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDatabaseConfiguration {

    @Bean
    static BeanFactoryPostProcessor requireProductionDatabaseSettings(Environment environment) {
        return beanFactory -> {
            for (String name : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD"}) {
                require(environment, name);
            }
            requirePostgresUrl(environment, "DB_URL");
            String[] migrationSettings = {"DB_MIGRATION_URL", "DB_MIGRATION_USERNAME", "DB_MIGRATION_PASSWORD"};
            boolean separateMigrationAccount = false;
            for (String name : migrationSettings) {
                separateMigrationAccount |= environment.containsProperty(name);
            }
            if (separateMigrationAccount) {
                for (String name : migrationSettings) {
                    require(environment, name);
                }
                requirePostgresUrl(environment, "DB_MIGRATION_URL");
            }
        };
    }

    private static void require(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Production database configuration requires " + name);
        }
    }

    private static void requirePostgresUrl(Environment environment, String name) {
        if (!environment.getRequiredProperty(name).startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException(name + " must be a PostgreSQL JDBC URL");
        }
    }
}
