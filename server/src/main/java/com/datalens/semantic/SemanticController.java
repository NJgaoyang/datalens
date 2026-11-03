package com.datalens.semantic;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.semantic.SemanticModels.*;

@RestController
@RequestMapping("/api/v1/semantics/{subjectId}")
public class SemanticController {
    private final SemanticService service;
    public SemanticController(SemanticService service){this.service=service;}
    private <T> ApiResponse<T> response(T value,HttpServletRequest r){return ApiResponse.success(value,RequestIdFilter.id(r));}
    @GetMapping("/draft") public ApiResponse<Draft> draft(@PathVariable String subjectId,HttpServletRequest r){return response(service.draft(subjectId),r);}
    @PutMapping("/draft") public ApiResponse<Draft> save(@PathVariable String subjectId,@Valid @RequestBody Save input,HttpServletRequest r){return response(service.save(subjectId,input,RequestIdFilter.id(r)),r);}
    @PostMapping("/review") public ApiResponse<Draft> review(@PathVariable String subjectId,@Valid @RequestBody Review input,HttpServletRequest r){return response(service.review(subjectId,input,RequestIdFilter.id(r)),r);}
    @PostMapping("/publish") public ApiResponse<Publication> publish(@PathVariable String subjectId,@Valid @RequestBody Version input,HttpServletRequest r){return response(service.publish(subjectId,input,RequestIdFilter.id(r)),r);}
    @PostMapping("/withdraw") public ApiResponse<Draft> withdraw(@PathVariable String subjectId,@Valid @RequestBody Version input,HttpServletRequest r){return response(service.withdraw(subjectId,input,RequestIdFilter.id(r)),r);}
    @GetMapping("/published") public ApiResponse<Publication> published(@PathVariable String subjectId,HttpServletRequest r){return response(service.published(subjectId,RequestIdFilter.id(r)),r);}
    @GetMapping("/search") public ApiResponse<Search> search(@PathVariable String subjectId,@RequestParam(defaultValue="") String q,HttpServletRequest r){return response(service.search(subjectId,q,RequestIdFilter.id(r)),r);}
    @GetMapping("/releases") public ApiResponse<List<Publication>> releases(@PathVariable String subjectId,HttpServletRequest r){return response(service.releases(subjectId),r);}
}
