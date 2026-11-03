package com.datalens.common.api;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public PageResult {
        items = List.copyOf(items);
        if (total < 0 || page < 1 || size < 1 || size > 200 || items.size() > size) {
            throw new IllegalArgumentException("Invalid page result");
        }
    }
}
