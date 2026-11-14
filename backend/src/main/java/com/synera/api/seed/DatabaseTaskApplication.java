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
        if(task.startsWith("purge:demo")) {
            // A purge plan must not start web/auth/JPA or trigger Flyway migrations and seed runners.
            try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext(PurgeConfiguration.class)) {
                var cleanup=context.getBean(DemoTenantCleanup.class);
                System.out.println(task.equals("purge:demo:plan")?cleanup.plan():cleanup.purge(System.getenv()));
            }
            return;
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SyneraApiApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            int exitCode = SpringApplication.exit(context);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        }
    }
    // Programmatically registered lite configuration, deliberately not component-scanned by the web app.
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class PurgeConfiguration {
        @org.springframework.context.annotation.Bean(destroyMethod="close")
        javax.sql.DataSource dataSource() {
            var source=new com.zaxxer.hikari.HikariDataSource();
            source.setJdbcUrl(System.getenv("DB_URL"));source.setUsername(System.getenv("DB_USER"));source.setPassword(System.getenv("DB_PASSWORD"));source.setMaximumPoolSize(2);
            return source;
        }
        @org.springframework.context.annotation.Bean
        org.springframework.jdbc.core.JdbcTemplate jdbc(javax.sql.DataSource source){return new org.springframework.jdbc.core.JdbcTemplate(source);}
        @org.springframework.context.annotation.Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager(javax.sql.DataSource source){return new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);}
        @org.springframework.context.annotation.Bean
        DemoTenantCleanup cleanup(org.springframework.jdbc.core.JdbcTemplate jdbc){return new DemoTenantCleanup(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());}
    }
}
