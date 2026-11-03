package com.datalens.chart;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.*;
import com.datalens.common.web.RequestIdFilter;
import com.datalens.query.QueryModels.*;
import static com.datalens.chart.ChartModels.*;
@RestController
@RequestMapping("/api/v1/charts")
public class ChartController {
    private final ChartService service;
    public ChartController(ChartService service){this.service=service;}
    private <T> ApiResponse<T> response(T value,HttpServletRequest r){return ApiResponse.success(value,RequestIdFilter.id(r));}
    @GetMapping public ApiResponse<PageResult<Summary>> list(@Valid @ModelAttribute PageQuery page,HttpServletRequest r){return response(service.list(page.page(),page.size()),r);}
    @PostMapping("/previews") public ApiResponse<Preview> preview(@Valid @RequestBody PreviewInput input,HttpServletRequest r){return response(service.preview(input,RequestIdFilter.id(r)),r);}
    @GetMapping("/previews/{id}") public ApiResponse<Preview> preview(@PathVariable String id,HttpServletRequest r){return response(service.preview(id),r);}
    @GetMapping("/previews/{id}/result") public ApiResponse<Result> previewResult(@PathVariable String id,HttpServletRequest r){return response(service.previewResult(id),r);}
    @PostMapping public ApiResponse<Summary> save(@Valid @RequestBody Save input,HttpServletRequest r){return response(service.save(input,RequestIdFilter.id(r)),r);}
    @GetMapping("/{id}") public ApiResponse<Detail> get(@PathVariable String id,HttpServletRequest r){return response(service.detail(id),r);}
    @PostMapping("/{id}/revisions") public ApiResponse<Summary> publish(@PathVariable String id,@Valid @RequestBody Publish input,HttpServletRequest r){return response(service.publish(id,input,RequestIdFilter.id(r)),r);}
    @PostMapping("/{id}/refreshes") public ApiResponse<TaskView> refresh(@PathVariable String id,@Valid @RequestBody Refresh input,HttpServletRequest r){return response(service.refresh(id,input,RequestIdFilter.id(r)),r);}
    @GetMapping("/{id}/refreshes/{taskId}/result") public ApiResponse<Result> result(@PathVariable String id,@PathVariable String taskId,HttpServletRequest r){return response(service.result(id,taskId),r);}
}
