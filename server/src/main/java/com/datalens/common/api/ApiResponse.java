package com.datalens.common.api;

import java.time.Instant;

/** Stable envelope for application APIs. Actuator keeps its native health format. */
public record ApiResponse<T>(String code, String message, T data, String requestId, Instant timestamp) {
    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>("OK", "成功", data, requestId, Instant.now());
    }

    public static ApiResponse<Void> error(String code, String message, String requestId) {
        return new ApiResponse<>(code, message, null, requestId, Instant.now());
    }
}
