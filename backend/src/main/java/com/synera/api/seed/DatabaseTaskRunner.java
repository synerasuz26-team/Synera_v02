package com.synera.api.seed;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
@ConditionalOnProperty(name = "app.task")
public class DatabaseTaskRunner implements ApplicationRunner {
    private final String task;
    private final Flyway flyway;
    private final SystemSeedService systemSeedService;
    private final DemoSeedService demoSeedService;
    private final String demoPassword;

    public DatabaseTaskRunner(
            @Value("${app.task}") String task,
            Flyway flyway,
            SystemSeedService systemSeedService,
            DemoSeedService demoSeedService,
            @Value("${SEED_DEMO_PASSWORD:}") String demoPassword) {
        this.task = task;
        this.flyway = flyway;
        this.systemSeedService = systemSeedService;
        this.demoSeedService = demoSeedService;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        DatabaseTaskGuard.validate(System.getenv(), task);
        switch (task) {
            case "migrate" -> flyway.migrate();
            case "seed:system" -> systemSeedService.seed();
            case "seed:demo" -> {
                systemSeedService.seed();
                demoSeedService.seed(demoPassword);
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
}
