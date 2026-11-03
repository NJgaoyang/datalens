package com.datalens.export;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.export.ExportModels.*;
@RestController @RequestMapping("/api/v1/exports")
public class ExportController {
 @ModelAttribute public void privateResponse(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");response.setHeader("X-Content-Type-Options","nosniff");}
 private final ExportService service;public ExportController(ExportService service){this.service=service;}
 @PostMapping public ApiResponse<Job> create(@Valid @RequestBody Create input,HttpServletRequest r){return ApiResponse.success(service.create(input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
 @GetMapping("/{id}") public ApiResponse<Job> get(@PathVariable String id,HttpServletRequest r){return ApiResponse.success(service.get(id),RequestIdFilter.id(r));}
 @GetMapping("/charts/{chart}/permission") public ApiResponse<Permission> permission(@PathVariable String chart,HttpServletRequest r){return ApiResponse.success(service.permission(chart),RequestIdFilter.id(r));}
 @GetMapping("/charts/{chart}/grants") public ApiResponse<java.util.List<GrantView>> grants(@PathVariable String chart,HttpServletRequest r){return ApiResponse.success(service.grants(chart),RequestIdFilter.id(r));}
 @PutMapping("/charts/{chart}/grant") public ApiResponse<Permission> grant(@PathVariable String chart,@Valid @RequestBody Grant input,HttpServletRequest r){return ApiResponse.success(service.grant(chart,input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
 @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable String id,HttpServletRequest r){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store, private").header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"datalens-export.csv\"").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(service.download(id,RequestIdFilter.id(r)));}
}
