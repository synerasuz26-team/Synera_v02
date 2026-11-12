package com.synera.api.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Order(10)
@ConditionalOnProperty(name = "app.task", matchIfMissing = true)
public class SeedBootstrapRunner implements ApplicationRunner {
    private final Environment environment;
    private final SystemSeedService systemSeedService;
    private final DemoSeedService demoSeedService;

    public SeedBootstrapRunner(
            Environment environment,
            SystemSeedService systemSeedService,
            DemoSeedService demoSeedService) {
        this.environment = environment;
        this.systemSeedService = systemSeedService;
        this.demoSeedService = demoSeedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        systemSeedService.seed();
        if (environment.getProperty("SEED_DEMO", Boolean.class, false)) {
            String appEnvironment = environment.getProperty("APP_ENV", "");
            if (!appEnvironment.equals("dev") && !appEnvironment.equals("staging")) {
                throw new IllegalStateException("SEED_DEMO chỉ được bật trong dev hoặc staging.");
            }
            String demoPassword = environment.getProperty("SEED_DEMO_PASSWORD", "");
            if (demoPassword.isBlank()) {
                throw new IllegalStateException("Thiếu biến môi trường SEED_DEMO_PASSWORD.");
            }
            demoSeedService.seed(demoPassword);
        }
    }
}
