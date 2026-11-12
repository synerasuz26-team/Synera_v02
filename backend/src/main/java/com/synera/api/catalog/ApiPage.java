package com.synera.api.catalog;

import java.util.List;

public record ApiPage<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> ApiPage<T> of(List<T> items, int page, int size, long totalElements) {
        int pages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new ApiPage<>(items, page, size, totalElements, pages);
    }
}
