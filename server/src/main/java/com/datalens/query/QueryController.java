package com.datalens.query;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.query.QueryModels.*;
@RestController
@RequestMapping("/api/v1/query-tasks")
public class QueryController {
    private final QueryService service;
    public QueryController(QueryService service){this.service=service;}
    @PostMapping public ApiResponse<TaskView> submit(@Valid @RequestBody Submit input,HttpServletRequest r){return ApiResponse.success(service.submit(input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
    @GetMapping("/{id}") public ApiResponse<TaskView> get(@PathVariable String id,HttpServletRequest r){return ApiResponse.success(service.get(id),RequestIdFilter.id(r));}
    @GetMapping("/{id}/result") public ApiResponse<Result> result(@PathVariable String id,HttpServletRequest r){return ApiResponse.success(service.result(id),RequestIdFilter.id(r));}
    @PostMapping("/{id}/cancel") public ApiResponse<TaskView> cancel(@PathVariable String id,HttpServletRequest r){return ApiResponse.success(service.cancel(id),RequestIdFilter.id(r));}
}
