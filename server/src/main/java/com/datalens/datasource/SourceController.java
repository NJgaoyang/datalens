package com.datalens.datasource;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.datasource.SourceModels.*;

@RestController
@RequestMapping("/api/v1/admin/sources")
public class SourceController {
    private final SourceService service;
    public SourceController(SourceService service) {this.service=service;}
    @GetMapping public ApiResponse<List<SourceView>> list(HttpServletRequest r) {return ApiResponse.success(service.list(),RequestIdFilter.id(r));}
    @PostMapping public ApiResponse<SourceView> create(@Valid @RequestBody SourceInput in,HttpServletRequest r) {
        return ApiResponse.success(service.save(null,in,RequestIdFilter.id(r)),RequestIdFilter.id(r));
    }
    @PutMapping("/{id}") public ApiResponse<SourceView> update(@PathVariable String id,@Valid @RequestBody SourceInput in,HttpServletRequest r) {
        return ApiResponse.success(service.save(id,in,RequestIdFilter.id(r)),RequestIdFilter.id(r));
    }
    @PostMapping("/{id}/test") public ApiResponse<Map<String,Boolean>> test(@PathVariable String id,HttpServletRequest r) {
        service.test(id,RequestIdFilter.id(r));return ApiResponse.success(Map.of("success",true),RequestIdFilter.id(r));
    }
    @PostMapping("/{id}/harvest") public ApiResponse<SourceView> harvest(@PathVariable String id,HttpServletRequest r) {
        return ApiResponse.success(service.harvest(id,RequestIdFilter.id(r)),RequestIdFilter.id(r));
    }
    @GetMapping("/{id}/metadata") public ApiResponse<List<ColumnView>> metadata(@PathVariable String id,HttpServletRequest r) {
        return ApiResponse.success(service.metadata(id),RequestIdFilter.id(r));
    }
}
