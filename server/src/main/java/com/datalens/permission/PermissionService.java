package com.datalens.permission;
import com.datalens.identity.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
@Service
public class PermissionService {
    private final IdentityMapper users;
    public PermissionService(IdentityMapper users) { this.users=users; }
    /** Re-read MySQL, including at async result delivery. Cached roles are never authoritative. */
    public CurrentActor requireActive(CurrentActor actor) {
        UserAccount current=users.find(actor.id());
        if(current==null || !current.enabled() || current.authVersion()!=actor.authVersion())
            throw new AuthenticationCredentialsNotFoundException("Session invalid");
        return CurrentActor.from(current);
    }
    public CurrentActor current() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof CurrentActor actor))
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        return requireActive(actor);
    }
    public CurrentActor requireSuperAdmin() {
        CurrentActor actor=current();
        if(!"SUPER_ADMIN".equals(actor.role())) throw new AccessDeniedException("Administrator required");
        return actor;
    }
}
