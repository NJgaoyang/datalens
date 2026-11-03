package com.datalens.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Reusable request contract; null means default, invalid numbers are not silently clamped. */
public record PageQuery(@Min(1) Integer page, @Min(1) @Max(200) Integer size) {
    public PageQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
    }
}
