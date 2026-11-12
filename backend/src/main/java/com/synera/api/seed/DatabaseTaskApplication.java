package com.synera.api.seed;

import com.synera.api.SyneraApiApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.Map;

public final class DatabaseTaskApplication {
    private DatabaseTaskApplication() {
    }

    public static void main(String[] args) {
        String task = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--app.task="))
                .map(argument -> argument.substring("--app.task=".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Thiếu --app.task."));
        DatabaseTaskGuard.validate(System.getenv(), task);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SyneraApiApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            int exitCode = SpringApplication.exit(context);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        }
    }
}
