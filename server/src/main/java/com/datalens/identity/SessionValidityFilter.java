package com.datalens.identity;
import com.datalens.permission.PermissionService;
import com.datalens.common.web.ApiErrorWriter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
/** Must run after context loading and before authorization. Not registered as a servlet filter. */
public final class SessionValidityFilter extends OncePerRequestFilter {
    private final PermissionService permissions;
    private final ApiErrorWriter errors;
    public SessionValidityFilter(PermissionService permissions,ApiErrorWriter errors) {this.permissions=permissions;this.errors=errors;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof CurrentActor actor) {
            try {permissions.requireActive(actor);}
            catch(AuthenticationException ex) {
                if(request.getSession(false)!=null) request.getSession(false).invalidate();
                SecurityContextHolder.clearContext();
                errors.write(request,response,401,"UNAUTHENTICATED","会话已失效，请重新登录");return;
            } catch(DataAccessException ex) {
                errors.write(request,response,503,"DEPENDENCY_UNAVAILABLE","身份验证服务暂不可用");return;
            }
        }
        chain.doFilter(request,response);
    }
}
