package com.synera.api.seed;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
@Order(20)
@ConditionalOnProperty(name = "app.task")
public class DatabaseTaskRunner implements ApplicationRunner {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(DatabaseTaskRunner.class);

    private final String task;
    private final Flyway flyway;
    private final SystemSeedService systemSeedService;
    private final DemoSeedService demoSeedService;
    private final String demoPassword;
    private final DemoTenantCleanup demoCleanup;

    public DatabaseTaskRunner(
            @Value("${app.task}") String task,
            Flyway flyway,
            SystemSeedService systemSeedService,
            DemoSeedService demoSeedService,
            @Value("${SEED_DEMO_PASSWORD:}") String demoPassword,
            DemoTenantCleanup demoCleanup) {
        this.task = task;
        this.flyway = flyway;
        this.systemSeedService = systemSeedService;
        this.demoSeedService = demoSeedService;
        this.demoPassword = demoPassword;
        this.demoCleanup = demoCleanup;
    }

    @Override
    public void run(ApplicationArguments args) {
        DatabaseTaskGuard.validate(System.getenv(), task);
        switch (task) {
            case "purge:demo:plan" -> System.out.println(demoCleanup.plan());
            case "purge:demo" -> System.out.println(demoCleanup.purge(System.getenv()));
            case "migrate" -> flyway.migrate();
            case "seed:system" -> systemSeedService.seed();
            case "seed:demo" -> {
                systemSeedService.seed();
                try {
                    demoSeedService.seed(demoPassword);
                } catch (RuntimeException exception) {
                    logConstraintDetails(exception);
                    throw exception;
                }
            }
            case "reset:dev" -> {
                flyway.clean();
                flyway.migrate();
                systemSeedService.seed();
                if (Boolean.parseBoolean(System.getenv().getOrDefault("SEED_DEMO", "false"))) {
                    demoSeedService.seed(demoPassword);
                }
            }
            default -> throw new IllegalArgumentException("Lệnh cơ sở dữ liệu không được hỗ trợ.");
        }
    }

    private void logConstraintDetails(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sqlException) {
                LOGGER.error("demo_seed_sql_failure sqlState={} vendorCode={}",
                        sqlException.getSQLState(), sqlException.getErrorCode());
                return;
            }
        }
    }
}
