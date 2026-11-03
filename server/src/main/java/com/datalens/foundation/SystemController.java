package com.datalens.foundation;

import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final SystemInfoService service;

    public SystemController(SystemInfoService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public ApiResponse<SystemInfo> info(HttpServletRequest request) {
        return ApiResponse.success(service.getInfo(), RequestIdFilter.id(request));
    }
}
