package com.synera.api.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DemoSeedSafetyTest {
    @Test void explicitCliTasksDoNotRunBootstrapSeeds() {
        var system=mock(SystemSeedService.class);var demo=mock(DemoSeedService.class);
        var environment=new MockEnvironment().withProperty("app.task","purge:demo:plan").withProperty("SEED_DEMO","true");
        new SeedBootstrapRunner(environment,system,demo).run(new org.springframework.boot.DefaultApplicationArguments());
        verifyNoInteractions(system,demo);
    }
    @Test void webBootstrapRejectsProductionDemoFlagBeforeSystemSeed() {
        var system=mock(SystemSeedService.class);var demo=mock(DemoSeedService.class);
        var environment=new MockEnvironment().withProperty("SEED_DEMO","true").withProperty("APP_ENV","production").withProperty("DB_ENVIRONMENT","production").withProperty("DB_URL","jdbc:postgresql://localhost/database");
        assertThatThrownBy(()->new SeedBootstrapRunner(environment,system,demo).run(new org.springframework.boot.DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(system,demo);
    }
    @Test void refusesProductionBeforeAnyDatabaseOrPasswordOperation() {
        var jdbc=mock(JdbcTemplate.class);var passwords=mock(PasswordEncoder.class);
        var environment=new MockEnvironment().withProperty("APP_ENV","production").withProperty("DB_ENVIRONMENT","production").withProperty("DB_URL","jdbc:postgresql://localhost/database");
        var seed=new DemoSeedService(jdbc,new ObjectMapper(),passwords,environment);
        assertThatThrownBy(()->seed.seed("test-only-password")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc,passwords);
    }
    @Test void refusesMismatchedDatabaseEnvironmentEvenWhenAppSaysDev() {
        var jdbc=mock(JdbcTemplate.class);var passwords=mock(PasswordEncoder.class);
        var environment=new MockEnvironment().withProperty("APP_ENV","dev").withProperty("DB_ENVIRONMENT","production").withProperty("DB_URL","jdbc:postgresql://localhost/database");
        assertThatThrownBy(()->new DemoSeedService(jdbc,new ObjectMapper(),passwords,environment).seed("test-only-password")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc,passwords);
    }
}
