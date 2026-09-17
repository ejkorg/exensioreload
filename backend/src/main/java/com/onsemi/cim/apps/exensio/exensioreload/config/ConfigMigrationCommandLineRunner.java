package com.onsemi.cim.apps.exensio.exensioreload.config;

import com.onsemi.cim.apps.exensio.exensioreload.service.ConfigMigrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

/**
 * CommandLineRunner for YAML to database configuration migration.
 * Enables migration via CLI argument: --migrate-config or --migrate-config-overwrite
 *
 * Usage:
 *   java -jar app.jar --migrate-config              # Skip existing entries
 *   java -jar app.jar --migrate-config-overwrite    # Overwrite existing entries
 */
@Configuration
@Slf4j
public class ConfigMigrationCommandLineRunner implements CommandLineRunner {

    private final ConfigMigrationService configMigrationService;

    public ConfigMigrationCommandLineRunner(ConfigMigrationService configMigrationService) {
        this.configMigrationService = configMigrationService;
    }

    @Override
    public void run(String... args) throws Exception {
        // Check for migration arguments
        boolean hasMigrate = false;
        boolean overwriteExisting = false;

        for (String arg : args) {
            if (arg.equals("--migrate-config")) {
                hasMigrate = true;
                overwriteExisting = false;
                break;
            } else if (arg.equals("--migrate-config-overwrite")) {
                hasMigrate = true;
                overwriteExisting = true;
                break;
            }
        }

        if (hasMigrate) {
            log.info("Configuration migration triggered via CLI. Overwrite existing: {}", overwriteExisting);
            try {
                ConfigMigrationService.MigrationReport report = configMigrationService.migrateYamlToDatabase(overwriteExisting);
                log.info("Configuration migration completed successfully. Total imported: {}", report.getTotalImported());
            } catch (Exception e) {
                log.error("Configuration migration failed", e);
                System.exit(1);
            }
        }
    }
}
