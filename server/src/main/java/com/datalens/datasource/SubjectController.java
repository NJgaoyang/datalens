package com.datalens.datasource;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.datasource.SourceModels.*;
@RestController
public class SubjectController {
    private final SubjectService service;
    public SubjectController(SubjectService service) {this.service=service;}
    @GetMapping("/api/v1/admin/subjects") public ApiResponse<List<SubjectView>> admin(HttpServletRequest r) {return ApiResponse.success(service.adminList(),RequestIdFilter.id(r));}
    @PostMapping("/api/v1/admin/subjects") public ApiResponse<SubjectView> create(@Valid @RequestBody SubjectInput in,HttpServletRequest r) {
        return ApiResponse.success(service.save(null,in,RequestIdFilter.id(r)),RequestIdFilter.id(r));
    }
    @PutMapping("/api/v1/admin/subjects/{id}") public ApiResponse<SubjectView> update(@PathVariable String id,@Valid @RequestBody SubjectInput in,HttpServletRequest r) {
        return ApiResponse.success(service.save(id,in,RequestIdFilter.id(r)),RequestIdFilter.id(r));
    }
    @GetMapping("/api/v1/subjects") public ApiResponse<List<SubjectView>> list(HttpServletRequest r) {return ApiResponse.success(service.list(),RequestIdFilter.id(r));}
    @GetMapping("/api/v1/subjects/{id}/metadata") public ApiResponse<List<ColumnView>> metadata(@PathVariable String id,HttpServletRequest r) {
        return ApiResponse.success(service.metadata(id),RequestIdFilter.id(r));
    }
}
