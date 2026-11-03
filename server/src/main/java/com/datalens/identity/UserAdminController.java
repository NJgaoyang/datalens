package com.datalens.identity;
import com.datalens.common.api.*;
import com.datalens.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserAdminController {
    private final IdentityService identities;
    public UserAdminController(IdentityService identities) {this.identities=identities;}
    public record CreateInput(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) String password,@NotBlank String role) {
        @Override public String toString(){return "CreateInput[REDACTED]";}
    }
    public record ResetInput(@NotBlank @Size(max=72) String newPassword) {
        @Override public String toString(){return "ResetInput[REDACTED]";}
    }
    public record EnabledInput(@NotNull Boolean enabled) {}
    @GetMapping public ApiResponse<PageResult<UserView>> list(@Valid @ModelAttribute PageQuery page,HttpServletRequest request) {
        return ApiResponse.success(identities.list(page.page(),page.size()),RequestIdFilter.id(request));
    }
    @PostMapping public ApiResponse<UserView> create(@Valid @RequestBody CreateInput input,HttpServletRequest request) {
        return ApiResponse.success(identities.create(input.username(),input.password(),input.role(),RequestIdFilter.id(request)),RequestIdFilter.id(request));
    }
    @PutMapping("/{id}/enabled") public ApiResponse<Void> enabled(@PathVariable long id,@Valid @RequestBody EnabledInput input,HttpServletRequest request) {
        identities.setEnabled(id,input.enabled(),RequestIdFilter.id(request));
        return ApiResponse.success(null,RequestIdFilter.id(request));
    }
    @PostMapping("/{id}/password") public ApiResponse<Void> reset(@PathVariable long id,@Valid @RequestBody ResetInput input,HttpServletRequest request) {
        identities.resetPassword(id,input.newPassword(),RequestIdFilter.id(request));
        return ApiResponse.success(null,RequestIdFilter.id(request));
    }
}
