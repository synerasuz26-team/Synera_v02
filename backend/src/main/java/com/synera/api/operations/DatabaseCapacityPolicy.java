package com.synera.api.operations;

import com.synera.api.auth.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseCapacityPolicy {
    public static final long WRITE_BUDGET_BYTES = 300_000_000L;

    private DatabaseCapacityPolicy() {
    }

    public static void requireCapacityForHeavyWrite(JdbcTemplate jdbc) {
        Long bytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
        if (bytes != null && bytes >= WRITE_BUDGET_BYTES) {
            throw new ApiProblem("DATABASE_CAPACITY_LIMIT",
                    "Tạm dừng ảnh và nhập dữ liệu lớn: database đã chạm ngân sách 300 MB. "
                            + "Đối chiếu Railway Volume usage và xử lý dung lượng trước khi tiếp tục.",
                    HttpStatus.INSUFFICIENT_STORAGE,
                    java.util.Map.of("databaseBytes", bytes, "writeBudgetBytes", WRITE_BUDGET_BYTES));
        }
    }
}
