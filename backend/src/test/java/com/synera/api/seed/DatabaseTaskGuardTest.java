package com.synera.api.seed;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseTaskGuardTest {
    private static Map<String, String> devEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("APP_ENV", "dev");
        environment.put("DB_ENVIRONMENT", "dev");
        environment.put("DB_URL", "jdbc:postgresql://dev-db.internal:5432/synera");
        return environment;
    }

    @Test
    void allowsMigrationOnlyForExplicitDevTarget() {
        assertDoesNotThrow(() -> DatabaseTaskGuard.validate(devEnvironment(), "migrate"));
    }

    @Test
    void rejectsProductionEnvironmentAndProductionLookingDatabaseUrl() {
        Map<String, String> environment = devEnvironment();
        environment.put("DB_ENVIRONMENT", "production");
        assertThrows(IllegalStateException.class,
                () -> DatabaseTaskGuard.validate(environment, "migrate"));

        environment.put("DB_ENVIRONMENT", "dev");
        environment.put("DB_URL", "jdbc:postgresql://production-db.internal:5432/synera");
        assertThrows(IllegalStateException.class,
                () -> DatabaseTaskGuard.validate(environment, "migrate"));
    }

    @Test
    void resetRequiresExplicitConfirmationAndDemoPasswordWhenDemoSeedEnabled() {
        Map<String, String> environment = devEnvironment();
        assertThrows(IllegalStateException.class,
                () -> DatabaseTaskGuard.validate(environment, "reset:dev"));

        environment.put("RESET_DEV_CONFIRM", "YES");
        environment.put("SEED_DEMO", "true");
        assertThrows(IllegalStateException.class,
                () -> DatabaseTaskGuard.validate(environment, "reset:dev"));

        environment.put("SEED_DEMO_PASSWORD", "configured-out-of-band");
        assertDoesNotThrow(() -> DatabaseTaskGuard.validate(environment, "reset:dev"));
    }

    @Test
    void demoSeedRequiresPassword() {
        Map<String, String> environment = devEnvironment();
        assertThrows(IllegalStateException.class,
                () -> DatabaseTaskGuard.validate(environment, "seed:demo"));
        environment.put("SEED_DEMO_PASSWORD", "configured-out-of-band");
        assertDoesNotThrow(() -> DatabaseTaskGuard.validate(environment, "seed:demo"));
    }
    @Test
    void demoCleanupRequiresConfirmationAndRefusesProductionEvenWhenConfirmed() {
        var environment=devEnvironment();
        assertDoesNotThrow(()->DatabaseTaskGuard.validate(environment,"purge:demo:plan"));
        assertThrows(IllegalStateException.class,()->DatabaseTaskGuard.validate(environment,"purge:demo"));
        environment.put("PURGE_DEMO_CONFIRM","DELETE_DEMO_ONLY");
        assertDoesNotThrow(()->DatabaseTaskGuard.validate(environment,"purge:demo"));
        environment.put("APP_ENV","production");environment.put("DB_ENVIRONMENT","production");
        assertThrows(IllegalStateException.class,()->DatabaseTaskGuard.validate(environment,"purge:demo"));
        environment.put("SEED_DEMO_PASSWORD","configured-out-of-band");
        assertThrows(IllegalStateException.class,()->DatabaseTaskGuard.validate(environment,"seed:demo"));
    }
}
