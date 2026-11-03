package com.datalens.identity;
import com.datalens.audit.AuditService;
import com.datalens.common.api.ApiResponse;
import com.datalens.common.web.RequestIdFilter;
import com.datalens.permission.PermissionService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identities;
    private final PermissionService permissions;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrf;
    private final AuditService audit;
    private final LoginAdmission admission;
    public AuthController(IdentityService identities,PermissionService permissions,SecurityContextRepository contexts,
            CsrfTokenRepository csrf,AuditService audit,LoginAdmission admission) {
        this.identities=identities;this.permissions=permissions;this.contexts=contexts;this.csrf=csrf;this.audit=audit;this.admission=admission;
    }
    public record LoginInput(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=72) String password) {
        @Override public String toString(){return "LoginInput[REDACTED]";}
    }
    public record PasswordInput(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(max=72) String newPassword) {
        @Override public String toString(){return "PasswordInput[REDACTED]";}
    }
    public record CsrfView(String token,String headerName) {}
    @GetMapping("/csrf") public ApiResponse<CsrfView> csrf(CsrfToken token,HttpServletRequest request) {
        return ApiResponse.success(new CsrfView(token.getToken(),token.getHeaderName()),RequestIdFilter.id(request));
    }
    @PostMapping("/login") public ApiResponse<UserView> login(@Valid @RequestBody LoginInput input,
            HttpServletRequest request,HttpServletResponse response) {
        UserAccount user;
        try(var permit=admission.enter(input.username())) {
            user=identities.authenticate(input.username(),input.password(),RequestIdFilter.id(request));
        }
        // Discard anonymous and previously authenticated session state, preventing fixation.
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        request.getSession(true);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(CurrentActor.from(user),null,
                List.of(new SimpleGrantedAuthority("ROLE_"+user.role()))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
        csrf.saveToken(null,request,response);
        return ApiResponse.success(user.view(),RequestIdFilter.id(request));
    }
    @GetMapping("/me") public ApiResponse<UserView> me(HttpServletRequest request) {
        CurrentActor actor=permissions.current();
        return ApiResponse.success(new UserView(actor.id(),actor.username(),actor.role(),true),RequestIdFilter.id(request));
    }
    @PostMapping("/logout") public ApiResponse<Void> logout(HttpServletRequest request,HttpServletResponse response) {
        CurrentActor actor=permissions.current();
        audit.append(actor.id(),"LOGOUT","USER",Long.toString(actor.id()),"SUCCESS",RequestIdFilter.id(request));
        clear(request);
        return ApiResponse.success(null,RequestIdFilter.id(request));
    }
    @PostMapping("/password") public ApiResponse<Void> password(@Valid @RequestBody PasswordInput input,HttpServletRequest request) {
        identities.changePassword(input.currentPassword(),input.newPassword(),RequestIdFilter.id(request));
        clear(request);
        return ApiResponse.success(null,RequestIdFilter.id(request));
    }
    private void clear(HttpServletRequest request) {
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
    }
}
