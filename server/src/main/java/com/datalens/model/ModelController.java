package com.datalens.model;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import static com.datalens.model.ModelModels.*;
@RestController
@RequestMapping("/api/v1")
public class ModelController {
    private final ModelConfigurationService config;private final ModelGateway gateway;
    public ModelController(ModelConfigurationService config,ModelGateway gateway){this.config=config;this.gateway=gateway;}
    @GetMapping("/admin/model") public ApiResponse<Settings> read(HttpServletRequest r){return ApiResponse.success(config.read(),RequestIdFilter.id(r));}
    @PutMapping("/admin/model") public ApiResponse<Settings> save(@Valid @RequestBody Configure input,HttpServletRequest r){return ApiResponse.success(config.save(input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
    @PostMapping("/model/proposals") public ApiResponse<Proposal> generate(@Valid @RequestBody Generate input,HttpServletRequest r){return ApiResponse.success(gateway.generate(input,RequestIdFilter.id(r)),RequestIdFilter.id(r));}
}
