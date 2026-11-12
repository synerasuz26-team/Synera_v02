package com.synera.api.seed;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DatabaseTaskGuard {
    private static final Set<String> TASKS = Set.of("migrate", "seed:system", "seed:demo", "reset:dev");

    private DatabaseTaskGuard() {
    }

    public static void validate(Map<String, String> environment, String task) {
        if (!TASKS.contains(task)) {
            throw new IllegalArgumentException("Lệnh cơ sở dữ liệu không được hỗ trợ.");
        }
        requireDevTarget(environment);
        if (task.equals("seed:demo")) {
            requireDemoPassword(environment);
        }
        if (task.equals("reset:dev")) {
            if (!"YES".equals(environment.get("RESET_DEV_CONFIRM"))) {
                throw new IllegalStateException("reset:dev yêu cầu RESET_DEV_CONFIRM=YES.");
            }
            if (Boolean.parseBoolean(environment.getOrDefault("SEED_DEMO", "false"))) {
                requireDemoPassword(environment);
            }
        }
    }

    public static void requireDevTarget(Map<String, String> environment) {
        if (!"dev".equals(environment.get("APP_ENV"))
                || !"dev".equals(environment.get("DB_ENVIRONMENT"))) {
            throw new IllegalStateException("Tác vụ chỉ được chạy khi APP_ENV và DB_ENVIRONMENT đều là dev.");
        }
        String databaseUrl = environment.get("DB_URL");
        if (databaseUrl == null || !databaseUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("DB_URL phải trỏ tới PostgreSQL.");
        }
        if (databaseUrl.toLowerCase(Locale.ROOT).matches(".*(prod|production).*")) {
            throw new IllegalStateException("Đích database có dấu hiệu production; đã từ chối tác vụ.");
        }
    }

    private static void requireDemoPassword(Map<String, String> environment) {
        if (environment.getOrDefault("SEED_DEMO_PASSWORD", "").isBlank()) {
            throw new IllegalStateException("Thiếu biến môi trường SEED_DEMO_PASSWORD.");
        }
    }
}
